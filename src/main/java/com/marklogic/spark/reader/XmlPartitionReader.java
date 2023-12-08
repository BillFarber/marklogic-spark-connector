/*
 * Copyright 2023 MarkLogic Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.marklogic.spark.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.marklogic.client.row.RowManager;
import com.marklogic.spark.Options;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.unsafe.types.UTF8String;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.namespace.QName;
import javax.xml.stream.*;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;

class XmlPartitionReader implements PartitionReader<InternalRow> {

    private static final Logger logger = LoggerFactory.getLogger(XmlPartitionReader.class);

    private final ReadContext readContext;
    private final PlanAnalysis.Partition partition;
    private final RowManager rowManager;

    private JsonRowDeserializer jsonRowDeserializer;

    private Iterator<String> rowIterator;
    private int nextBucketIndex;
    private int currentBucketRowCount;

    // Used solely for logging metrics
    private long totalRowCount;
    private long totalDuration;

    // Used solely for testing purposes; is never expected to be used in production. Intended to provide a way for
    // a test to get the count of rows returned from MarkLogic, which is important for ensuring that pushdown operations
    // are working correctly.
    static Consumer<Long> totalRowCountListener;

    XmlPartitionReader(ReadContext readContext, PlanAnalysis.Partition partition) {
        this.readContext = readContext;
        this.partition = partition;
        this.rowManager = readContext.connectToMarkLogic().newRowManager();
        // Nested values won't work with the JacksonParser used by JsonRowDeserializer, so we ask for type info to not
        // be in the rows.
        this.rowManager.setDatatypeStyle(RowManager.RowSetPart.HEADER);
        this.jsonRowDeserializer = new JsonRowDeserializer(readContext.getSchema());
    }

    @Override
    public boolean next() throws IOException {
        if (rowIterator != null) {
            if (rowIterator.hasNext()) {
                return true;
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("Count of rows for partition {} and bucket {}: {}", this.partition,
                        this.partition.getBuckets().get(nextBucketIndex - 1), currentBucketRowCount);
                }
                currentBucketRowCount = 0;
            }
        }

        // Iterate through buckets until we find one with at least one row.
        while (true) {
            boolean noBucketsLeftToQuery = nextBucketIndex == partition.getBuckets().size();
            if (noBucketsLeftToQuery) {
                return false;
            }

            nextBucketIndex++;
            long start = System.currentTimeMillis();

            Map<String, String> readProperties = readContext.getProperties();
            List<String> elementStrings = new ArrayList<>();
            XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
            try {
                XMLEventReader reader = xmlInputFactory.createXMLEventReader(new FileInputStream(readProperties.get(Options.READ_XML_FILE)));
                String splitElementName = readProperties.get(Options.READ_XML_SPLIT);
                while (reader.hasNext()) {
                    XMLEvent nextEvent = reader.nextEvent();
                    if (nextEvent.isStartElement()) {
                        StartElement startElement = nextEvent.asStartElement();
                        if (splitElementName.equals(startElement.getName().getLocalPart())) {
                            elementStrings.add(writeToString(reader, nextEvent));
                        }
                    }
                }
            } catch (XMLStreamException | FileNotFoundException e) {
                throw new RuntimeException(e);
            }
            this.rowIterator = elementStrings.iterator();

            if (logger.isDebugEnabled()) {
                this.totalDuration += System.currentTimeMillis() - start;
            }
            boolean bucketHasAtLeastOneRow = this.rowIterator.hasNext();
            if (bucketHasAtLeastOneRow) {
                return true;
            }
        }
    }

    private String writeToString(XMLEventReader reader,
                             XMLEvent startEvent )
        throws XMLStreamException, IOException {
        StartElement element = startEvent.asStartElement();
        QName name = element.getName();
        int stack = 1;
        XMLOutputFactory outputFactory = XMLOutputFactory.newInstance();
        StringWriter elementWriter = new StringWriter();
        XMLEventWriter writer = outputFactory.createXMLEventWriter( elementWriter);
        writer.add(element);
        while (true) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()
                && event.asStartElement().getName().equals(name))
                stack++;
            if (event.isEndElement()) {
                EndElement end = event.asEndElement();
                if (end.getName().equals(name)) {
                    stack--;
                    if (stack == 0) {
                        writer.add(event);
                        break;
                    }
                }
            }
            writer.add(event);
        }
        writer.close();
        return elementWriter.toString();
    }


    @Override
    public InternalRow get() {
        this.currentBucketRowCount++;
        this.totalRowCount++;
        String row = rowIterator.next();
        return new GenericInternalRow(new Object[]{UTF8String.fromString(row)});
    }

    @Override
    public void close() {
        if (totalRowCountListener != null) {
            totalRowCountListener.accept(totalRowCount);
        }

        // Not yet certain how to make use of CustomTaskMetric, so just logging metrics of interest for now.
        logMetrics();
    }

    private void logMetrics() {
        if (logger.isDebugEnabled()) {
            double rowsPerSecond = totalRowCount > 0 ? totalRowCount / ((double) totalDuration / 1000) : 0;
            ObjectNode metrics = new ObjectMapper().createObjectNode()
                .put("partitionId", this.partition.getIdentifier())
                .put("totalRequests", this.partition.getBuckets().size())
                .put("totalRowCount", this.totalRowCount)
                .put("totalDuration", this.totalDuration)
                .put("rowsPerSecond", String.format("%.2f", rowsPerSecond));
            logger.debug(metrics.toString());
        }
    }
}
