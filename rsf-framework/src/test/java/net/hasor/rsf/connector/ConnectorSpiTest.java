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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    @Test(timeout = 10000)
    public void operationsRejectUninitializedManagerWithoutWaitingForSpiDiscovery() throws Exception {
        BlockingFactory.entered = new CountDownLatch(1);
        BlockingFactory.release = new CountDownLatch(1);
        ConnectorConfig config = this.config("outgoing");
        ClassLoader loader = new TestConnectorManager.TestLoader(getClass().getClassLoader(), BlockingFactory.class);
        ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(loader, config));
        ExecutorService workers = Executors.newFixedThreadPool(2);
        BasicFuture<Void> initialized = new BasicFuture<>();
        try {
            workers.execute(() -> {
                try {
                    manager.init();
                    initialized.completed(null);
                } catch (Throwable failure) {
                    initialized.failed(failure);
                }
            });
            assertTrue(BlockingFactory.entered.await(2, TimeUnit.SECONDS));
            BasicFuture<Void> rejected = new BasicFuture<>();
            workers.execute(() -> {
                try {
                    assertFalse(manager.isInitialized());
                    assertTrue(manager.bind(config.name()).getCause() instanceof IllegalStateException);
                    assertTrue(manager.connect(config.address()).getCause() instanceof IllegalStateException);
                    assertTrue(manager.protocols().isEmpty());
                    rejected.completed(null);
                } catch (Throwable failure) {
                    rejected.failed(failure);
                }
            });
            rejected.get(2, TimeUnit.SECONDS);
            assertFalse(initialized.isDone());
            BlockingFactory.release.countDown();
            initialized.get(2, TimeUnit.SECONDS);
            assertTrue(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.connect(config.address()).getCause() instanceof UnsupportedOperationException);
            assertEquals(1, CountingFactory.initialized);
        } finally {
            BlockingFactory.release.countDown();
            workers.shutdown();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
            manager.close();
        }
    }

    @Test
    public void configurationRejectsMissingTransportBeforeConnectorCreation() {
        for (String type : new String[] { null, "", "  " }) {
            Map<String, String> options = new HashMap<>();
            options.put("listenType", type);
            try {
                new ConnectorConfig("invalid", new InterAddress("memory", "localhost", 1, "default"), options);
                fail("A connector configuration must identify its transport");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("listenType"));
                assertEquals(0, CountingFactory.created);
            }
        }
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
                assertTrue(expected.getMessage().contains("multiple endpoints"));
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
            try {
                manager.init();
                fail("Missing configured transport must fail initialization");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("unknown"));
            }
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void discoversFactoriesAtInitAndLazilyCreatesConfiguredEndpoints() throws Exception {
        ConnectorConfig first = new ConnectorConfig("first", config("first").address(), Collections.singletonMap("listenType", "CUSTOM"));
        ConnectorConfig second = new ConnectorConfig("second", new InterAddress("other", "localhost", 2, "default"), Collections.singletonMap("listenType", "custom"));
        ClassLoader loader = new TestConnectorManager.TestLoader(getClass().getClassLoader(), CountingFactory.class);
        try (ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(loader, first, second))) {
            manager.init();
            manager.init();
            assertEquals(1, CountingFactory.constructed);
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
            RsfListen listen = manager.bind(first.name()).get();
            assertSame(first, manager.find("first").config());
            assertSame(listen, manager.bind(first.name()).get());
            manager.bind(second.name()).get();
            assertEquals(2, CountingFactory.created);
            assertEquals(2, CountingFactory.initialized);
            manager.close();
            assertFalse(listen.isActive());
            assertTrue(manager.protocols().isEmpty());
            manager.init();
            assertEquals(2, CountingFactory.constructed);
            assertEquals(2, CountingFactory.created);
            manager.bind(first.name()).get();
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
            Future<RsfListen> bound = manager.bind(new ConnectorConfig(config.name(), config.address(), Collections.singletonMap("listenType", "unknown")).name());
            assertTrue(bound.getCause() instanceof IllegalArgumentException);
            assertTrue(bound.getCause().getMessage().contains("Unknown endpoint"));
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

                protected Future<RsfListen> listen(InterAddress address, ReceivedListener listener) {
                    String type = this.config.listenType();
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

                protected Future<RsfChannel> openSession(InterAddress target, ReceivedListener listener) {
                    CountingFactory.target = target;
                    throw new UnsupportedOperationException();
                }

                protected void doClose() {
                }
            };
        }
    }

    public static final class BlockingFactory extends CountingFactory {
        private static CountDownLatch entered;
        private static CountDownLatch release;

        public BlockingFactory() throws InterruptedException {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("SPI discovery was not released");
            }
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
