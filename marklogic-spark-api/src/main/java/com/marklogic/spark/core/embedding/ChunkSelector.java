/*
 * Copyright © 2024 MarkLogic Corporation. All Rights Reserved.
 */
package com.marklogic.spark.core.embedding;

import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.client.io.marker.AbstractWriteHandle;

/**
 * Abstracts how chunks are selected from a JSON or XML document.
 */
public interface ChunkSelector {

    /**
     * @param sourceDocument
     * @return Selecting chunks may involve deserializing a string or byte array into an e.g. JsonNode, in which case
     * the document to be written will not be the sourceDocument that is passed in.
     */
    DocumentAndChunks selectChunks(DocumentWriteOperation sourceDocument);

    // The new way, above method will be removed soon.
    DocumentAndChunks selectChunks(String uri, AbstractWriteHandle content);
}
