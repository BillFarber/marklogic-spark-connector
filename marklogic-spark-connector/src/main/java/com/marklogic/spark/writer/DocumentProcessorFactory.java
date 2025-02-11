/*
 * Copyright © 2024 MarkLogic Corporation. All Rights Reserved.
 */
package com.marklogic.spark.writer;

import com.marklogic.client.document.DocumentWriteOperation;
import com.marklogic.spark.ConnectorException;
import com.marklogic.spark.Context;
import com.marklogic.spark.Options;
import com.marklogic.spark.Util;
import com.marklogic.spark.core.splitter.TextSplitter;
import com.marklogic.spark.core.splitter.TextSplitterFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.function.Function;

/**
 * This class exists in preparation for two events. First, upgrading to langchain4j 0.36.x will involve requiring Java
 * 17, and we don't yet want to force Flux users to use Java 17. Second, we may want to have marklogic-langchain4j be
 * a separate module that is available as a dependency. This class acts as a bridge, trying to instantiate the
 * langchain4j-specific class. If that's not on the classpath, that's fine - the splitter/embedder options simply won't
 * work.
 * <p>
 * At least for the 2.5.0 connector release, everything is being packaged together as we're sticking with langchain4j
 * 0.35.0 which only requires Java 11.
 */
abstract class DocumentProcessorFactory {

    private static final String FACTORY_CLASS_NAME = "com.marklogic.spark.langchain4j.Langchain4jDocumentProcessorFactory";

    static TextSplitter newTextSplitter(Context context) {
        boolean shouldSplit = context.getProperties().keySet().stream().anyMatch(key -> key.startsWith(Options.WRITE_SPLITTER_PREFIX));
        if (!shouldSplit) {
            return null;
        }
        @SuppressWarnings("unchecked")
        TextSplitterFactory factory = (TextSplitterFactory) newLangchain4jProcessorFactory();
        return factory != null ? factory.newTextSplitter(context) : null;
    }

    static Function<DocumentWriteOperation, Iterator<DocumentWriteOperation>> buildDocumentProcessor(Context context) {
        // We'll need to remove some of the abstraction here, as we need to determine if the user has configured either
        // the splitter or embedder, both of which depend on langchain4j and thus Java 17.
        boolean shouldSplitOrEmbed = context.getProperties().keySet().stream()
            .anyMatch(key -> key.startsWith(Options.WRITE_SPLITTER_PREFIX) || key.startsWith(Options.WRITE_EMBEDDER_PREFIX));
        if (!shouldSplitOrEmbed) {
            return null;
        }

        Object factory = newLangchain4jProcessorFactory();
        if (factory == null) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Function<Context, Function<DocumentWriteOperation, Iterator<DocumentWriteOperation>>> processorFactory = (Function<Context, Function<DocumentWriteOperation, Iterator<DocumentWriteOperation>>>) factory;
        return processorFactory.apply(context);
    }

    private static Object newLangchain4jProcessorFactory() {
        try {
            return Class.forName(FACTORY_CLASS_NAME).getDeclaredConstructor().newInstance();
        } catch (UnsupportedClassVersionError e) {
            throw new ConnectorException("Unable to configure support for splitting documents and/or generating embeddings. " +
                "Please ensure you are using Java 17 or higher for these operations.", e);
        }
        // Catch every checked exception from trying to instantiate the class. Any exception from the factory class
        // itself is expected to be a RuntimeException that should bubble up.
        catch (ClassNotFoundException | InstantiationException | IllegalAccessException | NoSuchMethodException |
               InvocationTargetException ex) {
            if (Util.MAIN_LOGGER.isDebugEnabled()) {
                Util.MAIN_LOGGER.debug(
                    "Unable to instantiate factory class {}; this is expected when the marklogic-langchain4j module is not on the classpath. Cause: {}",
                    FACTORY_CLASS_NAME, ex.getMessage()
                );
            }
            return null;
        }
    }

    private DocumentProcessorFactory() {
    }
}
