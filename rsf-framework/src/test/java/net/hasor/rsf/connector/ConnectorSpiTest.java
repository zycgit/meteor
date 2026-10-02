/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorSpiTest {
    @Before
    public void reset() {
        CountingFactory.constructed = 0;
        CountingFactory.created = 0;
        CountingFactory.initialized = 0;
        CountingFactory.configuration = null;
        CountingFactory.target = null;
    }

    private RsfContext context(Class<?>... providers) {
        ClassLoader loader = new TestConnectorManager.TestLoader(getClass().getClassLoader(), providers);
        return ConnectorResourcesTest.sharedContext(loader);
    }

    private ConnectorManager manager(Class<?>... providers) {
        return new ConnectorManager(context(providers));
    }

    private ConnectorConfig config(String name) {
        return new ConnectorConfig(name, new InterAddress("memory", "localhost", 1, "default"), Collections.singletonMap("listenType", "custom"));
    }

    @Test
    public void addressOnlyConnectSelectsSettingsAndPreservesLocalConfiguration() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "CUSTOM");
        options.put("connectTimeout", "1234");
        options.put("workerThread", "3");
        options.put("tls.enabled", "true");
        ConnectorConfig config = new ConnectorConfig("outgoing", new InterAddress("memory://localhost:1/default"), options);
        ClassLoader loader = new TestConnectorManager.TestLoader(getClass().getClassLoader(), CountingFactory.class);
        try (ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(loader, config))) {
            manager.init();
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
            InterAddress target = new InterAddress("MEMORY://localhost:2/default");
            assertTrue(manager.connect(target).getCause() instanceof UnsupportedOperationException);
            assertSame(target, CountingFactory.target);
            ConnectorConfig selected = CountingFactory.configuration;
            assertEquals("outgoing", selected.name());
            assertEquals(config.address(), selected.address());
            assertEquals(1234, selected.connectTimeout());
            assertEquals(3, selected.integer("workerThread", 1));
            assertEquals("true", selected.option("tls.enabled", null));
            assertTrue(manager.find("outgoing").getListenList().isEmpty());
            InterAddress another = new InterAddress("memory://localhost:3/default");
            assertTrue(manager.connect(another).getCause() instanceof UnsupportedOperationException);
            assertSame(another, CountingFactory.target);
            assertSame(selected, CountingFactory.configuration);
            assertEquals(1, CountingFactory.created);
            assertEquals(1, CountingFactory.initialized);
            try {
                manager.configurations().clear();
                fail("Configuration collection must be read-only");
            } catch (UnsupportedOperationException expected) {
                assertEquals(1, manager.configurations().size());
            }
        }
    }

    @Test
    public void duplicateSchemesFailInitWithoutCreatingConnectors() throws Exception {
        ConnectorConfig first = config("first");
        ConnectorConfig second = new ConnectorConfig("second", new InterAddress("MEMORY://localhost:2/default"), Collections.singletonMap("listenType", "custom"));
        ClassLoader loader = new TestConnectorManager.TestLoader(getClass().getClassLoader(), CountingFactory.class);
        try (ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(loader, first, second))) {
            try {
                manager.init();
                fail("Duplicate schemes must not silently select a connector");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("Duplicate"));
            }
            assertFalse(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void unknownSchemeAndMissingFactoryFailWithoutOpeningConnections() throws Exception {
        ConnectorConfig config = new ConnectorConfig("missing", config("missing").address(), Collections.singletonMap("listenType", "unknown"));
        ClassLoader loader = new TestConnectorManager.TestLoader(getClass().getClassLoader(), CountingFactory.class);
        try (ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(loader, config))) {
            manager.init();
            Throwable unknownScheme = manager.connect(new InterAddress("other://localhost:2/default")).getCause();
            assertTrue(unknownScheme instanceof RsfException);
            assertEquals(ProtocolStatus.ProtocolUndefined, ((RsfException) unknownScheme).getStatus());
            Throwable missingFactory = manager.connect(config.address()).getCause();
            assertTrue(missingFactory instanceof IllegalArgumentException);
            assertTrue(missingFactory.getMessage().contains("unknown"));
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void discoversFactoriesAtInitAndLazilyCreatesConfiguredEndpoints() throws Exception {
        try (ConnectorManager manager = manager(CountingFactory.class)) {
            ConnectorConfig first = new ConnectorConfig("first", config("first").address(), Collections.singletonMap("listenType", "CUSTOM"));
            manager.init();
            manager.init();
            assertEquals(1, CountingFactory.constructed);
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
            RsfListen listen = manager.bind(first).get();
            assertSame(first, manager.find("first").config());
            assertSame(listen, manager.bind(first).get());
            ConnectorConfig second = config("second");
            manager.bind(second).get();
            assertEquals(2, CountingFactory.created);
            assertEquals(2, CountingFactory.initialized);
            manager.close();
            assertFalse(listen.isActive());
            assertTrue(manager.protocols().isEmpty());
            manager.init();
            assertEquals(2, CountingFactory.constructed);
            assertEquals(2, CountingFactory.created);
            manager.bind(first).get();
            assertEquals(3, CountingFactory.created);
        }
    }

    @Test
    public void duplicateTypesFailInitializationBeforeConnectorCreation() {
        try (ConnectorManager manager = manager(CountingFactory.class, DuplicateFactory.class)) {
            try {
                manager.init();
                fail("Duplicate listenTypes must not depend on discovery order");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("custom"));
                assertTrue(expected.getMessage().contains(CountingFactory.class.getName()));
                assertTrue(expected.getMessage().contains(DuplicateFactory.class.getName()));
            }
            assertFalse(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void unknownTypeDoesNotFallBackToAnUnrelatedFactory() {
        try (ConnectorManager manager = manager(CountingFactory.class)) {
            manager.init();
            ConnectorConfig config = config("missing");
            Future<RsfListen> bound = manager.bind(new ConnectorConfig(config.name(), config.address(), Collections.singletonMap("listenType", "unknown")));
            assertTrue(bound.getCause() instanceof IllegalArgumentException);
            assertTrue(bound.getCause().getMessage().contains("unknown"));
            assertTrue(manager.connect(config.address()).getCause() instanceof RsfException);
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
        }
    }

    @Test
    public void blankFactoryNameFailsInitialization() {
        try (ConnectorManager manager = manager(BlankFactory.class)) {
            try {
                manager.init();
                fail("An SPI provider must declare its listenType");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("listenType"));
            }
            assertFalse(manager.isInitialized());
        }
    }

    public static class CountingFactory implements RsfConnectorFactory {
        private static int             constructed;
        private static int             created;
        private static int             initialized;
        private static ConnectorConfig configuration;
        private static InterAddress    target;

        public CountingFactory() {
            constructed++;
        }

        public String name() {
            return "CuStOm";
        }

        public RsfConnector create(ConnectorConfig connectorConfig, ConnectorManager connectorManager) {
            created++;
            configuration = connectorConfig;
            return new AbstractConnector(connectorConfig, connectorManager) {
                protected void initialize() {
                    initialized++;
                }

                protected Future<RsfListen> listen(String type, InterAddress address, ReceivedListener listener) {
                    return new BasicFuture<>(new AbstractRsfListen(type, address, listener) {
                        private boolean active = true;

                        public boolean isActive() {
                            return this.active;
                        }

                        public void close() {
                            this.active = false;
                        }
                    });
                }

                protected Future<RsfChannel> openSession(String type, InterAddress target, ReceivedListener listener) {
                    CountingFactory.target = target;
                    throw new UnsupportedOperationException();
                }

                protected void doClose() {
                }
            };
        }
    }

    public static final class DuplicateFactory extends CountingFactory {
        public String name() {
            return "CUSTOM";
        }
    }

    public static final class BlankFactory extends CountingFactory {
        public String name() {
            return " ";
        }
    }
}
