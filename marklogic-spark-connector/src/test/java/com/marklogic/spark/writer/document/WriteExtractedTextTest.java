/*
 * Copyright © 2025 MarkLogic Corporation. All Rights Reserved.
 */
package com.marklogic.spark.writer.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.marklogic.junit5.XmlNode;
import com.marklogic.spark.AbstractIntegrationTest;
import com.marklogic.spark.Options;
import com.marklogic.spark.udf.TextExtractor;
import org.apache.spark.SparkException;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.expressions.UserDefinedFunction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WriteExtractedTextTest extends AbstractIntegrationTest {

    private static final UserDefinedFunction TEXT_EXTRACTOR = TextExtractor.build();

    @Test
    void defaultToJson() {
        Dataset<Row> dataset = newSparkSession()
            .read().format(CONNECTOR_IDENTIFIER)
            .load("src/test/resources/extraction-files")
            .withColumn("extractedText", TEXT_EXTRACTOR.apply(new Column("content")));

        assertEquals(2, dataset.count(), "Expecting 2 files from the directory");
        assertEquals(9, dataset.collectAsList().get(0).size(), "Expecting the 8 standard columns for representing a " +
            "column, plus the 'extractedText' column.");

        dataset.write().format(CONNECTOR_IDENTIFIER)
            .option(Options.CLIENT_URI, makeClientUri())
            .option(Options.WRITE_PERMISSIONS, DEFAULT_PERMISSIONS)
            .option(Options.WRITE_URI_REPLACE, ".*/extraction-files,'/extract-test'")
            .mode(SaveMode.Append)
            .save();

        JsonNode doc = readJsonDocument("/extract-test/hello-world.docx-extracted-text.json");
        assertEquals("Hello world.\n\nThis file is used for testing text extraction.\n", doc.get("content").asText());

        doc = readJsonDocument("/extract-test/marklogic-getting-started.pdf-extracted-text.json");
        String content = doc.get("content").asText();
        assertTrue(content.contains("MarkLogic Server Table of Contents"), "Unexpected text: " + content);
    }

    @Test
    void extractToXml() {
        newSparkSession()
            .read().format(CONNECTOR_IDENTIFIER)
            .load("src/test/resources/extraction-files")
            .withColumn("extractedText", TEXT_EXTRACTOR.apply(new Column("content")))
            .write().format(CONNECTOR_IDENTIFIER)
            .option(Options.CLIENT_URI, makeClientUri())
            .option(Options.WRITE_PERMISSIONS, DEFAULT_PERMISSIONS)
            .option(Options.WRITE_URI_REPLACE, ".*/extraction-files,'/extract-test'")
            .option(Options.WRITE_EXTRACTED_TEXT_FORMAT, "xml")
            .mode(SaveMode.Append)
            .save();

        XmlNode doc = readXmlDocument("/extract-test/hello-world.docx-extracted-text.xml");
        doc.assertElementValue("/model:root/model:content", "Hello world.\n\nThis file is used for testing text extraction.\n");

        doc = readXmlDocument("/extract-test/marklogic-getting-started.pdf-extracted-text.xml");
        String content = doc.getElementValue("/model:root/model:content");
        assertTrue(content.contains("MarkLogic Server Table of Contents"), "Unexpected text: " + content);
    }

    @Test
    void invalidColumn() {
        Dataset<Row> dataset = newSparkSession()
            .read().format(CONNECTOR_IDENTIFIER)
            .load("src/test/resources/extraction-files")
            .withColumn("extractedText", TEXT_EXTRACTOR.apply(new Column("uri")));

        SparkException ex = assertThrows(SparkException.class, () -> dataset.collectAsList());
        assertTrue(ex.getMessage().contains("Text extraction UDF must be run against a column containing non-null byte arrays."),
            "Unexpected error: " + ex.getMessage());
    }

}
