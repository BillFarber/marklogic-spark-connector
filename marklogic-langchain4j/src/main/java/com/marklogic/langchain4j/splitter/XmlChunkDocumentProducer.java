/*
 * Copyright © 2025 MarkLogic Corporation. All Rights Reserved.
 */
package com.marklogic.langchain4j.splitter;

import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.client.impl.DocumentWriteOperationImpl;
import com.marklogic.client.io.DOMHandle;
import com.marklogic.client.io.Format;
import com.marklogic.langchain4j.MarkLogicLangchainException;
import com.marklogic.langchain4j.classifier.TextClassifier;
import com.marklogic.langchain4j.embedding.Chunk;
import com.marklogic.langchain4j.embedding.DOMChunk;
import com.marklogic.langchain4j.embedding.DocumentAndChunks;
import com.marklogic.langchain4j.embedding.XmlChunkConfig;
import com.marklogic.spark.Util;
import com.marklogic.spark.dom.DOMHelper;
import dev.langchain4j.data.segment.TextSegment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class XmlChunkDocumentProducer extends AbstractChunkDocumentProducer {

    private static final String DEFAULT_CHUNKS_ELEMENT_NAME = "chunks";

    private final DOMHelper domHelper;
    private final XmlChunkConfig xmlChunkConfig;
    private final XPathFactory xPathFactory = XPathFactory.newInstance();
    private final DocumentBuilderFactory documentBuilderFactory;

    XmlChunkDocumentProducer(DocumentWriteOperation sourceDocument, Format sourceDocumentFormat,
                             List<String> textSegments, ChunkConfig chunkConfig, List<byte[]> classifications) {
        super(sourceDocument, sourceDocumentFormat, textSegments, chunkConfig, classifications);

        // Namespaces aren't needed for producing chunks.
        this.domHelper = new DOMHelper(null);
        this.xmlChunkConfig = new XmlChunkConfig(chunkConfig.getEmbeddingXmlNamespace());
        documentBuilderFactory = DocumentBuilderFactory.newInstance();
    }

    @Override
    protected DocumentWriteOperation makeChunkDocument() {
        Document doc = this.domHelper.newDocument();
        Element root = doc.createElementNS(
            chunkConfig.getXmlNamespace(),
            chunkConfig.getRootName() != null ? chunkConfig.getRootName() : "root"
        );

        doc.appendChild(root);
        Element sourceUri = doc.createElementNS(chunkConfig.getXmlNamespace(), "source-uri");
        sourceUri.setTextContent(sourceDocument.getUri());
        root.appendChild(sourceUri);

        Element chunksElement = doc.createElementNS(chunkConfig.getXmlNamespace(), DEFAULT_CHUNKS_ELEMENT_NAME);
        root.appendChild(chunksElement);

        List<Chunk> chunks = new ArrayList<>();
        AtomicInteger ct = new AtomicInteger(0);
        for (int i = 0; i < this.maxChunksPerDocument && hasNext(); i++) {
            Element classificationReponseNode = getNthClassificationResponseElement(ct.getAndIncrement());
            addChunk(doc, textSegments.get(listIndex++), chunksElement, chunks, classificationReponseNode);
        }

        final String chunkDocumentUri = makeChunkDocumentUri(sourceDocument, "xml");
        return new DocumentAndChunks(
            new DocumentWriteOperationImpl(chunkDocumentUri, chunkConfig.getMetadata(), new DOMHandle(doc)),
            chunks
        );
    }

    protected DocumentWriteOperation addChunksToSourceDocument() {
        Document doc = domHelper.extractDocument(super.sourceDocument);

        Element chunksElement = doc.createElementNS(chunkConfig.getXmlNamespace(), determineChunksElementName(doc));
        doc.getDocumentElement().appendChild(chunksElement);

        List<Chunk> chunks = new ArrayList<>();
        AtomicInteger ct = new AtomicInteger(0);
        for (String textSegment : textSegments) {
            Element classificationReponseNode = getNthClassificationResponseElement(ct.getAndIncrement());
            addChunk(doc, textSegment, chunksElement, chunks, classificationReponseNode);
        }

        return new DocumentAndChunks(
            new DocumentWriteOperationImpl(sourceDocument.getUri(), sourceDocument.getMetadata(), new DOMHandle(doc)),
            chunks
        );
    }

    private Element getNthClassificationResponseElement(int n) {
        if (classifications != null && !classifications.isEmpty()) {
            byte[] classificationBytes = classifications.get(n);
            try {
                DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
                Document classificationResponse = builder.parse(new ByteArrayInputStream(classificationBytes));
                return classificationResponse.getDocumentElement();
            } catch (Exception e) {
                throw new MarkLogicLangchainException(String.format("Unable to classify data from document with URI: %s; cause: %s", sourceDocument.getUri(), e.getMessage()), e);
            }
        } else {
            return null;
        }
    }

    private void addChunk(Document doc, String textSegment, Element chunksElement, List<Chunk> chunks, Element classificationReponseNode) {
        Element chunk = doc.createElementNS(chunkConfig.getXmlNamespace(), "chunk");
        chunksElement.appendChild(chunk);
        Element text = doc.createElementNS(chunkConfig.getXmlNamespace(), "text");
        text.setTextContent(textSegment);
        chunk.appendChild(text);
        if (classificationReponseNode != null) {
            Node classificationNode = doc.createElement("classification");
            NodeList structuredDocumentNodeChildNodes = classificationReponseNode.getElementsByTagName(TextClassifier.CLASSIFICATION_MAIN_ELEMENT).item(0).getChildNodes();
            for (int i = 0; i < structuredDocumentNodeChildNodes.getLength(); i++) {
                Node importedChildNode = doc.importNode(structuredDocumentNodeChildNodes.item(i), true);
                classificationNode.appendChild(importedChildNode);
            }
            chunk.appendChild(classificationNode);
        }
        chunks.add(new DOMChunk(super.sourceDocument.getUri(), doc, chunk, this.xmlChunkConfig, this.xPathFactory));
    }

    private String determineChunksElementName(Document doc) {
        return doc.getDocumentElement().getElementsByTagNameNS(Util.DEFAULT_XML_NAMESPACE, DEFAULT_CHUNKS_ELEMENT_NAME).getLength() == 0 ?
            DEFAULT_CHUNKS_ELEMENT_NAME : "splitter-chunks";
    }
}
