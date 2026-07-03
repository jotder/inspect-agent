package com.eoiagent.knowledge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Loads the raw text of a {@link DocumentSource} for one corpus {@code sourceType}. The default
 * reads the source's {@code uri()} as a UTF-8 file, falling back to a classpath resource when the
 * file does not exist (packs bundle their corpus inside the jar — e.g. {@code /acme/docs/*.md} in
 * the reference pack, T-352); a {@code classpath:} prefix forces the resource path. Implementations
 * only declare their type (and may later add type-specific parsing).
 */
public interface DocumentLoader {

    /** The corpus type this loader produces (e.g. {@code PRODUCT_DOC}). */
    String sourceType();

    /** Read the source's text; throws (caught by the ingestor as a warning) if the source is unreadable. */
    default String loadText(DocumentSource source) {
        String uri = source.uri();
        String resource;
        if (uri.startsWith("classpath:")) {
            resource = uri.substring("classpath:".length());
        } else {
            try {
                return Files.readString(Path.of(uri));
            } catch (IOException | InvalidPathException e) {
                resource = uri; // not a readable file — try the classpath before giving up
            }
        }
        String path = resource.startsWith("/") ? resource : "/" + resource;
        try (InputStream in = DocumentLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new UncheckedIOException(
                        new IOException("cannot read source (no file, no classpath resource): " + uri));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read source: " + uri, e);
        }
    }
}
