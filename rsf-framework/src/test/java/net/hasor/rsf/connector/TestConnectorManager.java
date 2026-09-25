/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import net.hasor.cobble.ref.Tuple;
import net.hasor.rsf.RsfContext;

/** Local SPI test support for the connector core; providers use real ServiceLoader discovery. */
public class TestConnectorManager extends ConnectorManager {
    private final Map<String, ConnectorConfig> configs = new HashMap<>();

    @FunctionalInterface
    public interface Creator {
        RsfConnector create(ConnectorConfig connectorConfig, ConnectorManager connectorManager) throws Exception;
    }

    public TestConnectorManager(RsfContext context) {
        super(context);
    }

    public void prepare(ConnectorConfig config, Creator creator) {
        if (this.configs.putIfAbsent(config.name(), config) != null) {
            throw new IllegalArgumentException("Duplicate fixture: " + config.name());
        }
        ((TestLoader) this.context().getClassLoader()).creators.put(Tuple.of(this, config.name()), creator);
    }

    public ConnectorConfig config(String name) {
        ConnectorConfig config = this.configs.get(name);
        if (config == null) {
            throw new IllegalStateException("No test configuration: " + name);
        }
        return config;
    }

    @Override
    public void close() {
        try {
            super.close();
        } finally {
            this.configs.clear();
        }
    }

    public static class TestLoader extends ClassLoader {
        private final Map<Tuple, Creator> creators = Collections.synchronizedMap(new HashMap<>());
        private final String              providers;

        public TestLoader(ClassLoader parent) {
            this(parent, MemoryFactory.class, TcpFactory.class, HttpFactory.class);
        }

        public TestLoader(ClassLoader parent, Class<?>... factories) {
            super(parent);
            StringBuilder providers = new StringBuilder();
            for (Class<?> factory : factories) {
                providers.append(factory.getName()).append('\n');
            }
            this.providers = providers.toString();
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (!name.equals("META-INF/services/" + RsfConnectorFactory.class.getName())) {
                return super.getResources(name);
            }
            URL resource = new URL(null, "test-spi:factories", new URLStreamHandler() {
                protected URLConnection openConnection(URL url) {
                    return new URLConnection(url) {
                        public void connect() {
                        }

                        public InputStream getInputStream() {
                            return new ByteArrayInputStream(TestLoader.this.providers.getBytes(StandardCharsets.UTF_8));
                        }
                    };
                }
            });
            return Collections.enumeration(Collections.singletonList(resource));
        }
    }

    public static class MemoryFactory implements RsfConnectorFactory {
        public String name() {
            return "memory";
        }

        public RsfConnector create(ConnectorConfig connectorConfig, ConnectorManager connectorManager) throws Exception {
            Creator creator = ((TestLoader) connectorManager.context().getClassLoader()).creators.get(Tuple.of(connectorManager, connectorConfig.name()));
            if (creator == null) {
                throw new IllegalArgumentException("No fixture for " + connectorConfig.name());
            }
            return creator.create(connectorConfig, connectorManager);
        }
    }

    public static final class TcpFactory extends MemoryFactory {
        public String name() {
            return "tcp";
        }
    }

    public static final class HttpFactory extends MemoryFactory {
        public String name() {
            return "http";
        }
    }
}
