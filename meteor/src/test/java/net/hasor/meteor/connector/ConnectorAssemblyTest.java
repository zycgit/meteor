/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector;
import java.net.URL;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetException;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorAssemblyTest {
    @Before
    public void reset() {
        CountingFactory.constructed = 0;
        CountingFactory.created = 0;
        CountingFactory.initialized = 0;
        CountingFactory.configuration = null;
        CountingFactory.target = null;
    }

    private ConnectorManager manager(MetConnectorFactory factory) {
        return new ConnectorManager(ConnectorResourcesTest.sharedContext(), factory);
    }

    private ConnectorConfig config(String name) {
        InterAddress localAddress = new InterAddress("memory", "localhost", 1, "default");
        Map<String, String> options = Collections.singletonMap("listenType", "custom");
        ProtocolConfig mount = new ProtocolConfig(name, "memory", "memory", options);
        return new ConnectorConfig(name, localAddress, options, Collections.singletonList(mount), true);
    }

    @Test(timeout = 10000)
    public void operationsRejectUninitializedManagerWithoutWaitingForAssemblyPreparation() throws Exception {
        BlockingFactory.entered = new CountDownLatch(1);
        BlockingFactory.release = new CountDownLatch(1);
        ConnectorConfig config = this.config("outgoing");
        MetContext managerContext = ConnectorResourcesTest.sharedContext(config);
        ConnectorManager manager = new ConnectorManager(managerContext, new BlockingFactory());
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
                InterAddress localAddress = new InterAddress("memory", "localhost", 1, "default");
                String protocolName = options.getOrDefault("protocol", "memory");
                ProtocolConfig mount = new ProtocolConfig("invalid", "memory", protocolName, options);
                new ConnectorConfig("invalid", localAddress, options, Collections.singletonList(mount), true);
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
        InterAddress localAddress = new InterAddress("memory://localhost:1/default");
        String protocolName = options.getOrDefault("protocol", "memory");
        ProtocolConfig mount = new ProtocolConfig("outgoing", "memory", protocolName, options);
        List<ProtocolConfig> configMounts = Collections.singletonList(mount);
        ConnectorConfig config = new ConnectorConfig("outgoing", localAddress, options, configMounts, true);
        MetContext managerContext = ConnectorResourcesTest.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
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
        Map<String, String> secondOptions = Collections.singletonMap("listenType", "custom");
        ProtocolConfig secondProtocol = new ProtocolConfig("second", "MEMORY", "MEMORY", secondOptions);
        InterAddress secondAddress = new InterAddress("MEMORY://localhost:2/default");
        List<ProtocolConfig> secondMounts = Collections.singletonList(secondProtocol);
        ConnectorConfig second = new ConnectorConfig("second", secondAddress, secondOptions, secondMounts, true);
        MetContext managerContext = ConnectorResourcesTest.sharedContext(first, second);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
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
        Map<String, String> configOptions = Collections.singletonMap("listenType", "unknown");
        ProtocolConfig configProtocol = new ProtocolConfig("missing", "memory", "memory", configOptions);
        InterAddress configAddress = config("missing").address();
        List<ProtocolConfig> configMounts = Collections.singletonList(configProtocol);
        ConnectorConfig config = new ConnectorConfig("missing", configAddress, configOptions, configMounts, true);
        MetContext managerContext = ConnectorResourcesTest.sharedContext(config);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
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
    public void preparesSuppliedFactoryAtInitAndLazilyCreatesConfiguredEndpoints() throws Exception {
        Map<String, String> firstOptions = Collections.singletonMap("listenType", "CUSTOM");
        ProtocolConfig firstProtocol = new ProtocolConfig("first", "memory", "memory", firstOptions);
        InterAddress firstAddress = config("first").address();
        List<ProtocolConfig> firstMounts = Collections.singletonList(firstProtocol);
        ConnectorConfig first = new ConnectorConfig("first", firstAddress, firstOptions, firstMounts, true);
        Map<String, String> secondOptions = Collections.singletonMap("listenType", "custom");
        ProtocolConfig secondProtocol = new ProtocolConfig("second", "other", "other", secondOptions);
        InterAddress secondAddress = new InterAddress("other", "localhost", 2, "default");
        List<ProtocolConfig> secondMounts = Collections.singletonList(secondProtocol);
        ConnectorConfig second = new ConnectorConfig("second", secondAddress, secondOptions, secondMounts, true);
        MetContext managerContext = ConnectorResourcesTest.sharedContext(first, second);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            manager.init();
            manager.init();
            assertEquals(1, CountingFactory.constructed);
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
            MetListen listen = manager.bind(first.name()).get(5, TimeUnit.SECONDS);
            assertSame(first, manager.find("first").config());
            assertSame(listen, manager.bind(first.name()).get(5, TimeUnit.SECONDS));
            manager.bind(second.name()).get(5, TimeUnit.SECONDS);
            assertEquals(2, CountingFactory.created);
            assertEquals(2, CountingFactory.initialized);
            manager.close();
            assertFalse(listen.isActive());
            assertTrue(manager.protocols().isEmpty());
            manager.init();
            assertEquals(1, CountingFactory.constructed);
            assertEquals(2, CountingFactory.created);
            manager.bind(first.name()).get(5, TimeUnit.SECONDS);
            assertEquals(3, CountingFactory.created);
        }
    }

    @Test
    public void unknownTypeDoesNotFallBackToAnUnrelatedFactory() {
        try (ConnectorManager manager = manager(new CountingFactory())) {
            manager.init();
            ConnectorConfig config = config("missing");
            Map<String, String> options = Collections.singletonMap("listenType", "unknown");
            ProtocolConfig protocol = new ProtocolConfig(config.name(), "memory", "memory", options);
            InterAddress missingAddress = config.address();
            List<ProtocolConfig> missingMounts = Collections.singletonList(protocol);
            ConnectorConfig missing = new ConnectorConfig(config.name(), missingAddress, options, missingMounts, true);
            Future<MetListen> bound = manager.bind(missing.name());
            assertTrue(bound.getCause() instanceof IllegalArgumentException);
            assertTrue(bound.getCause().getMessage().contains("Unknown endpoint"));
            assertTrue(manager.connect(config.address()).getCause() instanceof MetException);
            assertEquals(0, CountingFactory.created);
            assertTrue(manager.protocols().isEmpty());
        }
    }

    @Test
    public void suppliedAssemblerReceivesContextLoaderWithoutManagerSpiDiscovery() {
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) {
                throw new AssertionError("Manager must not discover an assembler via SPI: " + name);
            }
        };

        CountingFactory factory = new CountingFactory() {
            @Override
            public Collection<String> listenTypes(ClassLoader actual) {
                assertSame(loader, actual);
                return super.listenTypes(actual);
            }
        };

        try (ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(loader), factory)) {
            manager.init();
            assertTrue(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void blankFactoryNameFailsInitialization() {
        try (ConnectorManager manager = manager(new BlankFactory())) {
            try {
                manager.init();
                fail("The assembler must declare valid transport types");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("listenType"));
            }
            assertFalse(manager.isInitialized());
        }
    }

    @Test
    public void duplicateEndpointNamesFailBeforeCreatingAnyConnector() {
        ConnectorConfig first = this.config("duplicate");
        ProtocolConfig mount = new ProtocolConfig("other", "other", Collections.emptyMap());
        InterAddress secondAddress = first.address();
        List<ProtocolConfig> secondMounts = Collections.singletonList(mount);
        Map<String, String> secondOptions = first.options();
        ConnectorConfig second = new ConnectorConfig(first.name(), secondAddress, secondOptions, secondMounts, true);
        MetContext managerContext = ConnectorResourcesTest.sharedContext(first, second);
        try (ConnectorManager manager = new ConnectorManager(managerContext, new CountingFactory())) {
            try {
                manager.init();
                fail("Duplicate endpoint names must be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("Duplicate endpoint"));
            }
            assertFalse(manager.isInitialized());
            assertEquals(0, CountingFactory.created);
        }
    }

    @Test
    public void initializationFailurePreservesCleanupCauseAndAllowsFreshConnector() throws Exception {
        ConnectorConfig config = this.config("retry");
        IllegalStateException initialization = new IllegalStateException("initialization failed");
        IllegalArgumentException cleanup = new IllegalArgumentException("cleanup failed");
        List<String> events = new ArrayList<>();
        CountingFactory factory = new CountingFactory() {
            private int attempts;

            @Override
            public MetConnector create(ConnectorConfig value, ConnectorManager manager) {
                if (this.attempts++ != 0) {
                    return super.create(value, manager);
                }
                return new EndpointFixture.Endpoint(value, manager, events) {
                    @Override
                    protected void initialize() {
                        throw initialization;
                    }

                    @Override
                    protected void doClose() {
                        events.add("cleanup");
                        throw cleanup;
                    }
                };
            }
        };
        try (ConnectorManager manager = new ConnectorManager(ConnectorResourcesTest.sharedContext(config), factory)) {
            manager.init();
            assertSame(initialization, manager.bind(config.name()).getCause());
            assertArrayEquals(new Throwable[] { cleanup }, initialization.getSuppressed());
            assertEquals(Collections.singletonList("cleanup"), events);
            assertNull(manager.find(config.name()));
            assertTrue(manager.bind(config.name()).get(5, TimeUnit.SECONDS).isActive());
            assertEquals(1, CountingFactory.created);
        }
    }

    public static class CountingFactory implements MetConnectorFactory {
        private static int             constructed;
        private static int             created;
        private static int             initialized;
        private static ConnectorConfig configuration;
        private static InterAddress    target;

        public CountingFactory() {
            constructed++;
        }

        public Collection<String> listenTypes(ClassLoader loader) {
            return Collections.singletonList("CuStOm");
        }

        public MetConnector create(ConnectorConfig connectorConfig, ConnectorManager connectorManager) {
            created++;
            configuration = connectorConfig;
            return new AbstractConnector(connectorConfig, connectorManager) {
                protected void initialize() {
                    initialized++;
                }

                protected Future<MetListen> listen(InterAddress address, ReceivedListener listener) {
                    String type = this.config.listenType();
                    return new BasicFuture<>(new AbstractMetListen(type, address, listener) {
                        private boolean active = true;

                        public boolean isActive() {
                            return this.active;
                        }

                        public void close() {
                            this.active = false;
                        }
                    });
                }

                protected Future<MetChannel> openSession(InterAddress target, ReceivedListener listener) {
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

        @Override
        public Collection<String> listenTypes(ClassLoader loader) {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Assembly preparation was not released");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(failure);
            }
            return super.listenTypes(loader);
        }
    }

    public static final class BlankFactory extends CountingFactory {
        public Collection<String> listenTypes(ClassLoader loader) {
            return Collections.singletonList(" ");
        }
    }
}
