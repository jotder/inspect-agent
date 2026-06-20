package com.eoiagent.knowledge;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads the raw text of a {@link DocumentSource} for one corpus {@code sourceType}. The default
 * reads the source's {@code uri()} as a UTF-8 file; implementations only declare their type (and may
 * later add type-specific parsing).
 */
public interface DocumentLoader {

    /** The corpus type this loader produces (e.g. {@code PRODUCT_DOC}). */
    String sourceType();

    /** Read the source's text; throws (caught by the ingestor as a warning) if the source is unreadable. */
    default String loadText(DocumentSource source) {
        try {
            return Files.readString(Path.of(source.uri()));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read source: " + source.uri(), e);
        }
    }
}
