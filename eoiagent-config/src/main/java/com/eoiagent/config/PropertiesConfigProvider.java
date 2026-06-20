package com.eoiagent.config;

import com.eoiagent.core.ConfigException;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/**
 * Reads configuration from a {@code .properties} source with dotted keys. The file/resource is read
 * once at construction and snapshotted, so lookups perform no I/O.
 */
public final class PropertiesConfigProvider extends AbstractConfigProvider {

    public PropertiesConfigProvider(Properties properties) {
        super(source(properties));
    }

    /** Loads from a filesystem path at construction. */
    public static PropertiesConfigProvider fromFile(Path path) {
        Objects.requireNonNull(path, "path");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config properties: " + path, e);
        }
        return new PropertiesConfigProvider(props);
    }

    /** Loads from a classpath resource at construction. */
    public static PropertiesConfigProvider fromClasspath(String resource) {
        Objects.requireNonNull(resource, "resource");
        Properties props = new Properties();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl != null ? cl.getResourceAsStream(resource)
                : PropertiesConfigProvider.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new ConfigException("config resource not found on classpath: " + resource);
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read config resource: " + resource, e);
        }
        return new PropertiesConfigProvider(props);
    }

    /** A {@link RawSource} over a defensive copy of the properties. */
    static RawSource source(Properties properties) {
        Objects.requireNonNull(properties, "properties");
        Properties copy = new Properties();
        copy.putAll(properties);
        return copy::getProperty;
    }
}
