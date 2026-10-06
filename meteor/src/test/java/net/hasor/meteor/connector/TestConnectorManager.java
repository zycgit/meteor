/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.MetContext;

/** Local connector implementations supplied explicitly to the manager. */
public class TestConnectorManager extends ConnectorManager {
    private final Map<String, ConnectorConfig> configs  = new HashMap<>();
    private final Map<String, Creator>         creators = new HashMap<>();

    @FunctionalInterface
    public interface Creator {
        MetConnector create(ConnectorConfig config, ConnectorManager manager) throws Exception;
    }

    public TestConnectorManager(MetContext context) {
        super(context, new MemoryFactory());
    }

    public void prepare(ConnectorConfig config, Creator creator) {
        if (this.configs.putIfAbsent(config.name(), config) != null) {
            throw new IllegalArgumentException("Duplicate fixture: " + config.name());
        }
        this.creators.put(config.name(), creator);
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
            this.creators.clear();
        }
    }

    public static class MemoryFactory implements MetConnectorFactory {
        public Collection<String> listenTypes(ClassLoader loader) {
            return List.of("memory", "tcp", "http");
        }

        public MetConnector create(ConnectorConfig config, ConnectorManager manager) throws Exception {
            Creator creator = ((TestConnectorManager) manager).creators.get(config.name());
            if (creator == null) {
                throw new IllegalArgumentException("No fixture for " + config.name());
            }
            return creator.create(config, manager);
        }
    }
}
