/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.ref.Tuple;
import net.hasor.cobble.setting.BasicSettings;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfEnvironment;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** A complete in-memory provider: no network engine, framework, or RPC implementation. */
public class ConnectorResourcesTest {
    private static final ReceivedListener RECEIVER = new ReceivedListener() {
        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
        }
    };

    @Test
    public void connectorRequiresExplicitInitializationBeforeBindOrConnect() throws Exception {
        try (TestConnectorManager resourceManager = initializedManager(); MemoryEndpoint endpoint = new MemoryEndpoint(config("endpoint", 1), resourceManager, new MemoryProvider())) {
            try {
                endpoint.bind(endpoint.config().address()).get();
                fail();
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("init()"));
            }
            assertTrue(ConnectorConnectionsTest.failure(endpoint.connect(endpoint.config().address())) instanceof IllegalStateException);
            assertEquals(0, endpoint.initialized);
            assertEquals(0, endpoint.bound);
            endpoint.init();
            endpoint.init();
            assertEquals(1, endpoint.initialized);
            assertEquals(0, endpoint.bound);
            resourceManager.prepare(endpoint.config(), (connectorConfig, connectorManager) -> endpoint);
            resourceManager.bind(endpoint.config()).get();
            endpoint.bind(endpoint.config().address()).get();
            endpoint.init();
            assertEquals(1, endpoint.initialized);
            assertEquals(1, endpoint.bound);
        }
    }

    @Test
    public void managerInitializesOnlyUsedEndpointsOnce() throws Exception {
        try (TestConnectorManager manager = new TestConnectorManager(sharedContext())) {
            MemoryProvider provider = new MemoryProvider();
            manager.prepare(config("first", 1), provider);
            manager.prepare(config("second", 2), provider);
            assertTrue(ConnectorConnectionsTest.failure(manager.bind(manager.config("first"))) instanceof IllegalStateException);
            assertTrue(ConnectorConnectionsTest.failure(manager.connect(config("peer", 3).address())) instanceof IllegalStateException);
            manager.init();
            manager.init();
            assertNull(manager.find("first"));
            assertNull(manager.find("second"));
            manager.bind(manager.config("first")).get();
            MemoryEndpoint first = (MemoryEndpoint) manager.find("first");
            assertEquals(1, first.initialized);
            assertNull(manager.find("second"));
            manager.bind(manager.config("first")).get();
            manager.bind(manager.config("second")).get();
            MemoryEndpoint second = (MemoryEndpoint) manager.find("second");
            assertEquals(1, first.initialized);
            assertEquals(1, second.initialized);
            assertEquals(1, first.bound);
            assertEquals(1, second.bound);
        }
    }

    @Test
    public void operationAddressChangesWithoutReplacingConnectorConfiguration() throws Exception {
        Map<String, String> options = new HashMap<>();
        options.put("listenType", "memory");
        options.put("connectTimeout", "1234");
        options.put("workerThread", "3");
        ConnectorConfig client = new ConnectorConfig("client", config("local", 9).address(), options);
        try (TestConnectorManager manager = initializedManager(client)) {
            MemoryProvider provider = new MemoryProvider();
            ConnectorConfig server = config("server", 1);
            manager.prepare(server, provider);
            RsfListen first = manager.bind(server).get();
            InterAddress secondAddress = config("second", 2).address();
            RsfListen second = manager.bind(server.withAddress(secondAddress)).get();
            assertSame(server, manager.find("server").config());

            manager.prepare(client, provider);
            MemorySession firstSession = (MemorySession) manager.connect(first.getBindAddress()).get();
            RsfConnector initialized = manager.find("client");
            ConnectorConfig next = client.withAddress(second.getBindAddress());
            assertEquals(client.name(), next.name());
            assertEquals(client.listenType(), next.listenType());
            assertEquals(1234, next.connectTimeout());
            assertEquals(3, next.integer("workerThread", 1));
            MemorySession secondSession = (MemorySession) manager.connect(next.address()).get();
            assertSame(first, firstSession.server);
            assertSame(second, secondSession.server);
            assertSame(initialized, manager.find("client"));
            assertEquals(client.name(), initialized.config().name());
            assertEquals(1234, initialized.config().connectTimeout());
            assertEquals(3, initialized.config().integer("workerThread", 1));
            assertEquals(client.address(), initialized.config().address());
        }
    }

    @Test
    public void connectionsUseTheirConfiguredTypeWithoutTargetBasedReuse() throws Exception {
        try (TestConnectorManager manager = subscribedManager(sharedContext(new ConnectorConfig("tcp-client", new InterAddress("tcp://localhost:2/default"), Collections.singletonMap("listenType", "TCP")), new ConnectorConfig("http-client", new InterAddress("http://localhost:2/default"), Collections.singletonMap("listenType", "HTTP"))), RECEIVER)) {
            MemoryProvider provider = new MemoryProvider();
            for (String type : Arrays.asList("tcp", "http")) {
                InterAddress target = new InterAddress(type + "://localhost:1/default");
                ConnectorConfig serverConfig = new ConnectorConfig(type + "-server", target, Collections.singletonMap("listenType", type));
                ConnectorConfig clientConfig = new ConnectorConfig(type + "-client", new InterAddress(type + "://localhost:2/default"), Collections.singletonMap("listenType", type.toUpperCase(Locale.ROOT)));
                registerEndpoint(manager, serverConfig, provider);
                RsfConnector client = registerEndpoint(manager, clientConfig, provider);
                RsfListen listen = manager.bind(serverConfig).get();
                MemorySession session = (MemorySession) manager.connect(target).get();
                assertEquals(type, listen.getType());
                assertSame(listen, session.server);
                assertNotSame(session, manager.connect(target).get());
                assertNotSame(session, client.connect(target).get());
                assertTrue(client.getListenList().isEmpty());
            }
        }
    }

    @Test
    public void managerAndDirectConnectBothCreateIndependentConnections() throws Exception {
        try (TestConnectorManager manager = subscribedManager(sharedContext(config("first", 2)), RECEIVER)) {
            MemoryProvider provider = new MemoryProvider();
            RsfConnector server = registerEndpoint(manager, config("server", 1), provider);
            RsfConnector first = registerEndpoint(manager, config("first", 2), provider);
            RsfConnector second = registerEndpoint(manager, config("second", 3), provider);
            manager.init();
            manager.bind(manager.config("server")).get();
            InterAddress target = server.getBindAddress();
            RsfChannel managedFirst = manager.connect(target).get();
            RsfChannel managedSecond = manager.connect(target).get();
            assertNotSame(managedFirst, managedSecond);
            assertNotSame(managedFirst, manager.connect(new InterAddress(target.toString())).get());
            assertNotSame(managedSecond, manager.connect(target).get());
            RsfChannel directFirst = first.connect(target).get();
            RsfChannel directSecond = first.connect(target).get();
            try {
                assertNotSame(directFirst, directSecond);
                assertNotSame(managedFirst, directFirst);
                assertTrue(directFirst.isActive());
                assertTrue(directSecond.isActive());
            } finally {
                directFirst.close().get();
                directSecond.close().get();
            }
            assertTrue(managedFirst.isActive());
            assertTrue(managedSecond.isActive());
            manager.close();
            assertFalse(managedFirst.isActive());
            assertFalse(managedSecond.isActive());
        }
    }

    @Test
    public void lazyInitializationFailureOnlyClosesFailedEndpoint() throws Exception {
        try (TestConnectorManager manager = subscribedManager(sharedContext(), RECEIVER)) {
            MemoryProvider provider = new MemoryProvider();
            MemoryEndpoint first = registerEndpoint(manager, config("first", 1), provider);
            IOException cause = new IOException("init failed");
            MemoryEndpoint broken = new MemoryEndpoint(config("broken", 2), manager, provider) {
                protected void initialize() throws IOException {
                    super.initialize();
                    throw cause;
                }
            };
            manager.prepare(broken.config(), (connectorConfig, connectorManager) -> broken);
            MemoryEndpoint last = registerEndpoint(manager, config("last", 3), provider);
            manager.bind(manager.config("first")).get();
            assertSame(cause, ConnectorConnectionsTest.failure(manager.bind(manager.config("broken"))));
            assertEquals(1, first.initialized);
            assertEquals(1, broken.initialized);
            assertEquals(0, last.initialized);
            assertEquals(0, first.destroyed);
            assertEquals(1, broken.destroyed);
            assertEquals(0, last.destroyed);
            assertTrue(first.getListenList().get(0).isActive());
            manager.bind(manager.config("last")).get();
            assertEquals(1, last.initialized);
        }
    }

    @Test
    public void standaloneManagerBindsCreatesAndClosesSessions() throws Exception {
        MemoryProvider provider = new MemoryProvider();
        BasicFuture<ResponsePayload> received = new BasicFuture<>();
        ReceivedListener listener = new Listener() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                ResponsePayload response = new ResponsePayload();
                response.setRequestID(request.getRequestID());
                response.setStatus(ProtocolStatus.OK);
                response.setReturnData("memory reply");
                channel.sendData(response);
            }

            public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
                received.completed(response);
            }
        };
        RsfChannel session;
        MemoryEndpoint client, server;
        try (TestConnectorManager manager = subscribedManager(sharedContext(config("client", 2)), listener)) {

            server = registerEndpoint(manager, config("server", 1), provider);
            client = registerEndpoint(manager, config("client", 2), provider);
            manager.init();
            manager.bind(manager.config("server")).get();
            assertEquals(0, client.initialized);
            session = manager.connect(server.getBindAddress()).get(2, TimeUnit.SECONDS);
            assertEquals(1, client.initialized);
            assertEquals(0, client.bound);
            assertEquals(1, server.bound);
            assertNotSame(session, manager.connect(server.getBindAddress()).get());
            RequestPayload request = new RequestPayload();
            request.setRequestID(1);
            session.sendData(request);
            ResponsePayload response = received.get(2, TimeUnit.SECONDS);
            assertEquals(request.getRequestID(), response.getRequestID());
            assertEquals("memory reply", response.getReturnData());
            assertEquals(0, ((MemorySession) session).pending.size());
            // Outbound initialization may later be upgraded to listening exactly once.
            manager.bind(manager.config("client")).get();
            manager.bind(manager.config("client")).get();
            client.init();
            assertEquals(1, client.initialized);
            assertEquals(1, client.bound);
            assertSame(client, manager.find("client"));
            assertSame(server, manager.find("server"));
        }
        assertFalse(session.isActive());
        assertTrue(provider.listeners.isEmpty());
        assertEquals(1, client.destroyed);
        assertEquals(1, server.destroyed);
    }

    @Test
    public void closingConnectorInvalidatesAcceptedChannels() throws Exception {
        BasicFuture<RsfChannel> received = new BasicFuture<>();
        ReceivedListener listener = new Listener() {
            public void onRequest(RsfChannel channel, long id, RequestPayload request) {
                received.completed(channel);
            }
        };
        try (TestConnectorManager manager = subscribedManager(sharedContext(config("client", 2)), listener)) {
            MemoryProvider provider = new MemoryProvider();
            RsfConnector server = registerEndpoint(manager, config("server", 1), provider);
            registerEndpoint(manager, config("client", 2), provider);
            manager.init();
            RsfListen listening = manager.bind(manager.config("server")).get();
            MemorySession session = (MemorySession) manager.connect(server.getBindAddress()).get();
            RequestPayload request = new RequestPayload();
            request.setRequestID(1);
            session.sendData(request);
            RsfChannel channel = received.get(2, TimeUnit.SECONDS);
            assertTrue(channel.isActive());
            server.close();
            assertFalse(listening.isActive());
            assertTrue(server.getListenList().isEmpty());
            assertNull(manager.find("server"));
            assertNotNull(manager.find("client"));
            assertFalse(channel.isActive());
            assertEquals(0, session.pending.size());
            assertFalse(session.isActive());
        }
    }

    @Test
    public void closingCallbackRunsOnceBeforeResourceRelease() throws Exception {
        List<String> events = new ArrayList<>();
        try (TestConnectorManager manager = initializedManager()) {
            MemoryEndpoint endpoint = new MemoryEndpoint(config("endpoint", 1), manager, new MemoryProvider()) {
                protected void doClose() {
                    events.add("resources");
                    super.doClose();
                }
            };
            endpoint.onClosing(closing -> {
                assertSame(endpoint, closing);
                assertTrue(closing.connect(closing.config().address()).getCause() instanceof IllegalStateException);
                events.add("closing");
                closing.close();
            });
            endpoint.init();
            endpoint.close();
            endpoint.close();
            assertEquals(Arrays.asList("closing", "resources"), events);
            assertEquals(1, endpoint.destroyed);
        }
    }

    @Test
    public void initializationFailureRollsBackResourcesAndKeepsOriginalCause() throws Exception {
        MemoryProvider provider = new MemoryProvider();
        IOException cause = new IOException("initialize failed");
        AtomicInteger notifications = new AtomicInteger();
        try (TestConnectorManager resourceManager = initializedManager()) {
            MemoryEndpoint endpoint = new MemoryEndpoint(config("broken", 1), resourceManager, provider) {
                protected void initialize() throws IOException {
                    super.initialize();
                    throw cause;
                }

                protected void doClose() {
                    assertEquals(1, notifications.get());
                    super.doClose();
                    throw new IllegalStateException("cleanup failed");
                }
            };
            endpoint.onClosing(closing -> {
                assertSame(endpoint, closing);
                notifications.incrementAndGet();
            });
            try {
                endpoint.init();
                fail();
            } catch (IOException expected) {
                assertSame(cause, expected);
                assertEquals("cleanup failed", expected.getSuppressed()[0].getMessage());
            }
            endpoint.close();
            assertEquals(1, notifications.get());
            assertEquals(1, endpoint.destroyed);
            assertTrue(ConnectorConnectionsTest.failure(endpoint.connect(config("peer", 2).address())) instanceof IllegalStateException);
            try {
                endpoint.bind(endpoint.config().address()).get();
                fail();
            } catch (IllegalStateException expected) {
            }
        }
    }

    @Test
    public void suppliedConfigurationsCreateResourcesOnlyOnFirstUse() throws Exception {
        AtomicInteger created = new AtomicInteger();
        MemoryProvider provider = new MemoryProvider();
        TestConnectorManager.Creator factory = (connectorConfig, connectorManager) -> {
            created.incrementAndGet();
            return provider.create(connectorConfig, connectorManager);
        };
        try (TestConnectorManager manager = subscribedManager(sharedContext(), RECEIVER)) {
            manager.prepare(config("first", 1), factory);
            assertEquals(0, created.get());
            assertTrue(ConnectorConnectionsTest.failure(manager.connect(new InterAddress("missing://localhost:3/default"))) instanceof RsfException);
            manager.bind(manager.config("first")).get();
            manager.prepare(config("late", 2), factory);
            assertEquals(1, created.get());
            manager.bind(manager.config("late")).get();
            assertEquals(2, created.get());
        }
    }

    @Test
    public void managerOwnsTimersAndCancellation() throws Exception {
        TestConnectorManager closed;
        try (TestConnectorManager first = initializedManager(); TestConnectorManager second = initializedManager()) {
            closed = first;
            assertTrue(first.context().getEnvironment().getSerializeCoder("Java") instanceof JavaSerializeCoder);
            assertTrue(first.context().getServiceIDs().isEmpty());
            AtomicInteger fired = new AtomicInteger();
            Cancellable timer = first.schedule(fired::incrementAndGet, 10000);
            assertTrue(timer.cancel());
            assertFalse(timer.cancel());
            BasicFuture<ClassLoader> scheduled = new BasicFuture<>();
            first.schedule(() -> scheduled.completed(Thread.currentThread().getContextClassLoader()), 0);
            assertSame(first.context().getClassLoader(), scheduled.get(2, TimeUnit.SECONDS));
            assertEquals(0, fired.get());
        }
        try {
            closed.schedule(() -> {
            }, 0);
            fail();
        } catch (RejectedExecutionException expected) {
        }
        closed.close();
    }

    @Test
    public void managerClosesConnectorsBeforeStoppingItsTimer() throws Exception {
        verifyManagerCleanup(false, false);
    }

    @Test
    public void failedConnectorInitializationCanBeFollowedByManagerCleanup() throws Exception {
        verifyManagerCleanup(true, false);
    }

    @Test
    public void connectorCloseFailureDoesNotLeakTheManagerTimer() throws Exception {
        verifyManagerCleanup(false, true);
    }

    private void verifyManagerCleanup(boolean failInit, boolean failClose) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        IOException initFailure = new IOException("initialize");
        IllegalStateException closeFailure = new IllegalStateException("close");
        RsfContext shared = sharedContext();
        try (TestConnectorManager manager = new TestConnectorManager(shared)) {
            assertSame(shared, manager.context());
            MemoryEndpoint endpoint = new MemoryEndpoint(config("endpoint", 1), manager, new MemoryProvider()) {
                protected void initialize() throws IOException {
                    super.initialize();
                    this.manager.schedule(() -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException expected) {
                            Thread.currentThread().interrupt();
                        } finally {
                            events.add("timer");
                            stopped.countDown();
                        }
                    }, 0);
                    try {
                        assertTrue(started.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                    if (failInit) {
                        throw initFailure;
                    }
                }

                protected void doClose() {
                    super.doClose();
                    events.add("connector");
                    if (failClose) {
                        throw closeFailure;
                    }
                }
            };
            manager.prepare(endpoint.config(), (connectorConfig, connectorManager) -> endpoint);
            try {
                endpoint.manager.schedule(() -> {
                }, 0);
                fail("Scheduling must require manager initialization");
            } catch (RejectedExecutionException expected) {
            }
            try {
                manager.init();
                manager.bind(manager.config("endpoint")).get();
                assertFalse(failInit);
                manager.close();
            } catch (ExecutionException failure) {
                assertSame(initFailure, failure.getCause());
                manager.close();
            }
            assertTrue(stopped.await(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("connector", "timer"), events);
            try {
                endpoint.manager.schedule(() -> {
                }, 0);
                fail("Scheduling after manager closure must fail");
            } catch (RejectedExecutionException expected) {
            }
        }
    }

    @Test
    public void connectorsKeepConfigurationSeparateWhileSharingManager() throws Exception {
        try (TestConnectorManager manager = subscribedManager(sharedContext(), RECEIVER)) {
            ConnectorConfig firstConfig = config("first", 1);
            ConnectorConfig secondConfig = config("second", 2);
            MemoryProvider provider = new MemoryProvider();
            MemoryEndpoint first = registerEndpoint(manager, firstConfig, provider);
            MemoryEndpoint second = registerEndpoint(manager, secondConfig, provider);
            assertSame(firstConfig, first.config());
            assertSame(secondConfig, second.config());
            assertSame(manager, first.manager());
            assertSame(manager, second.manager());
            assertSame(manager.context(), first.manager().context());
            assertSame(first.manager().context(), second.manager().context());
            first.close();
        }
    }

    @Test
    public void managerRoutesMessagesFromAllItsConnectorsWithoutCrossingIntoAnotherManager() throws Exception {
        RecordingListener inbound = new RecordingListener();
        RecordingListener outbound = new RecordingListener();
        MemoryProvider provider = new MemoryProvider();
        RsfContext shared = sharedContext();
        try (TestConnectorManager servers = subscribedManager(shared, inbound); TestConnectorManager clients = subscribedManager(sharedContext(config("client", 3)), outbound)) {

            RsfConnector first = registerEndpoint(servers, config("first", 1), provider);
            RsfConnector second = registerEndpoint(servers, config("second", 2), provider);
            MemoryEndpoint client = registerEndpoint(clients, config("client", 3), provider);
            servers.init();
            clients.init();
            servers.bind(servers.config("first")).get();
            servers.bind(servers.config("second")).get();
            assertSame(clients, client.manager);
            int id = 0;
            for (RsfConnector server : Arrays.asList(first, second)) {
                MemorySession channel = (MemorySession) clients.connect(server.getBindAddress()).get();
                assertSame(clients, channel.listener());
                assertSame(servers, channel.server.listener());
                RequestPayload request = new RequestPayload();
                request.setRequestID(++id);
                channel.sendData(request).get();
                assertSame(request, inbound.requests.get(id - 1));
                RsfChannel source = inbound.channels.get(id - 1);
                ResponsePayload response = new ResponsePayload();
                response.setRequestID(id);
                response.setStatus(ProtocolStatus.OK);
                source.sendData(response).get();
                assertSame(response, outbound.responses.get(id - 1));
                IOException failure = new IOException("channel failure " + id);
                channel.listener().onFailure(channel, id, new ThrowPayload(failure));
                assertEquals(Long.valueOf(id), outbound.failedIds.get(id - 1));
                assertSame(failure, outbound.failures.get(id - 1));
            }
            assertEquals(2, inbound.requests.size());
            assertEquals(2, outbound.responses.size());
            assertEquals(2, outbound.failures.size());
            assertTrue(inbound.responses.isEmpty());
            assertTrue(inbound.failures.isEmpty());
            assertTrue(outbound.requests.isEmpty());
        }
    }

    private static final class RecordingListener extends Listener {
        private final List<RequestPayload>  requests  = new ArrayList<>();
        private final List<RsfChannel>      channels  = new ArrayList<>();
        private final List<ResponsePayload> responses = new ArrayList<>();
        private final List<Long>            failedIds = new ArrayList<>();
        private final List<Throwable>       failures  = new ArrayList<>();

        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
            this.requests.add(request);
            this.channels.add(channel);
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
            this.responses.add(response);
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
            this.failedIds.add(id);
            this.failures.add(failure.getThrowable());
        }
    }

    private MemoryEndpoint registerEndpoint(TestConnectorManager manager, ConnectorConfig config, TestConnectorManager.Creator factory) throws Exception {
        MemoryEndpoint endpoint = (MemoryEndpoint) factory.create(config, manager);
        manager.prepare(config, (connectorConfig, connectorManager) -> endpoint);
        return endpoint;
    }

    private TestConnectorManager initializedManager(ConnectorConfig... configs) throws Exception {
        TestConnectorManager manager = subscribedManager(sharedContext(configs), RECEIVER);
        manager.init();
        return manager;
    }

    static RsfContext sharedContext(ConnectorConfig... configs) {
        return sharedContext(new TestConnectorManager.TestLoader(ConnectorResourcesTest.class.getClassLoader()), configs);
    }

    static RsfContext sharedContext(ClassLoader loader, ConnectorConfig... configs) {
        BasicSettings values = new BasicSettings();
        Map<String, ConnectorConfig> configured = new LinkedHashMap<>();
        for (ConnectorConfig config : configs) {
            configured.put(config.name(), config);
            for (String key : new String[] { "listenType", "workerThread", "listenThread", "protocolFactory", "maxFrameSize", "maxPendingRequests", "handshakeTimeout", "contextPath", "tls.enabled" }) {
                String value = config.option(key, null);
                if (value != null) {
                    values.setSetting("connectors." + config.name() + "." + key, value);
                }
            }
        }

        JavaSerializeCoder coder = new JavaSerializeCoder();
        RsfSettings settings = (RsfSettings) Proxy.newProxyInstance(loader, new Class<?>[] { RsfSettings.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getProtocos":
                    return configured.keySet();
                case "getProtocolConfigKey":
                    return "connectors." + args[0];
                case "getBindAddressSet":
                    return configured.get(args[0]).address();
                case "getConnectTimeout":
                    return configs.length == 0 ? 3000 : configs[0].connectTimeout();
                case "getNodeArray":
                    return values.getNodeArray((String) args[0]);
                case "getString":
                    return values.getString((String) args[0]);
            }
            if ("getDefaultTimeout".equals(method.getName())) {
                return 3000;
            }
            throw new AssertionError("Unexpected setting: " + method);
        });
        RsfEnvironment environment = (RsfEnvironment) Proxy.newProxyInstance(loader, new Class<?>[] { RsfEnvironment.class }, (proxy, method, args) -> {
            if ("getSerializeCoder".equals(method.getName())) {
                return "Java".equals(args[0]) ? coder : null;
            }
            throw new AssertionError("Unexpected environment access: " + method);
        });
        return (RsfContext) Proxy.newProxyInstance(loader, new Class<?>[] { RsfContext.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getClassLoader":
                    return loader;
                case "getSettings":
                    return settings;
                case "getEnvironment":
                    return environment;
                case "getServiceIDs":
                    return Collections.emptyList();
                case "getServiceInfo":
                    return null;
                default:
                    throw new AssertionError("Unexpected context access: " + method);
            }
        });
    }

    private ConnectorConfig config(String name, int port) throws Exception {
        return new ConnectorConfig(name, new InterAddress("memory://localhost:" + port + "/default"), Collections.singletonMap("listenType", "memory"));
    }

    private static class Listener implements ReceivedListener {
        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
            throw new AssertionError(failure.getThrowable());
        }
    }

    private static final class MemoryProvider implements TestConnectorManager.Creator {
        final Map<Tuple, MemoryListen> listeners = new HashMap<>();

        public RsfConnector create(ConnectorConfig connectorConfig, ConnectorManager connectorManager) {
            return new MemoryEndpoint(connectorConfig, connectorManager, this);
        }
    }

    private static class MemoryEndpoint extends AbstractConnector {
        final MemoryProvider provider;
        int initialized, bound, destroyed;

        MemoryEndpoint(ConnectorConfig config, ConnectorManager manager, MemoryProvider provider) {
            super(config, manager);
            this.provider = provider;
        }

        protected void initialize() throws IOException {
            this.initialized++;
        }

        protected Future<RsfListen> listen(String listenType, InterAddress address, ReceivedListener listener) {
            MemoryListen listening = new MemoryListen(this, listenType, address, listener);
            this.provider.listeners.put(Tuple.of(listenType, address.toString()), listening);
            this.bound++;
            return new BasicFuture<>(listening);
        }

        protected Future<RsfChannel> openSession(String listenType, InterAddress target, ReceivedListener listener) {
            MemoryListen server = this.provider.listeners.get(Tuple.of(listenType, target.toString()));
            if (server == null) {
                BasicFuture<RsfChannel> result = new BasicFuture<>();
                result.failed(new IOException("No listener"));
                return result;
            }
            MemorySession session = new MemorySession(this.manager.nextConnectionId(), server.owner.manager.nextConnectionId(), this, server, listener);
            this.fireChannelConnected(session);
            server.owner.fireChannelConnected(session.accepted);
            return new BasicFuture<>(session);
        }

        protected void doClose() {
            this.destroyed++;
        }
    }

    private static final class MemoryListen extends AbstractRsfListen {
        final MemoryEndpoint owner;

        MemoryListen(MemoryEndpoint owner, String type, InterAddress address, ReceivedListener listener) {
            super(type, address, listener);
            this.owner = owner;
        }

        public boolean isActive() {
            return this.owner.provider.listeners.get(Tuple.of(this.getType(), this.getBindAddress().toString())) == this;
        }

        public void close() {
            this.owner.provider.listeners.remove(Tuple.of(this.getType(), this.getBindAddress().toString()), this);
        }
    }

    private static final class MemorySession extends AbstractRsfChannel {
        final MemoryEndpoint            client;
        final MemoryListen              server;
        final Map<Long, RequestPayload> pending = new ConcurrentHashMap<>();
        final AbstractRsfChannel        accepted;
        boolean active = true;

        MemorySession(long id, long acceptedId, MemoryEndpoint client, MemoryListen server, ReceivedListener listener) {
            super(client, id, listener);
            this.client = client;
            this.server = server;
            this.accepted = new AbstractRsfChannel(server.owner, acceptedId, server.listener()) {
                public InterAddress getRemote() {
                    return MemorySession.this.client.config().address();
                }

                public InterAddress getLocal() {
                    return MemorySession.this.server.getBindAddress();
                }

                public boolean isActive() {
                    return MemorySession.this.active;
                }

                public BasicFuture<RsfChannel> sendData(Payload payload) {
                    ResponsePayload response = (ResponsePayload) payload;
                    RequestPayload request = MemorySession.this.pending.get(response.getRequestID());
                    BasicFuture<RsfChannel> result = new BasicFuture<>();
                    if (!isActive() || request == null) {
                        result.failed(new IllegalStateException("No pending request"));
                        return result;
                    }
                    if (request.isMessage() || response.getStatus() != ProtocolStatus.Accept) {
                        MemorySession.this.pending.remove(response.getRequestID());
                    }
                    MemorySession.this.listener().onResponse(MemorySession.this, response.getRequestID(), response);
                    result.completed(this);
                    return result;
                }

                public BasicFuture<RsfChannel> close() {
                    MemorySession.this.close();
                    return new BasicFuture<>(this);
                }

                public BasicFuture<RsfChannel> drainAndClose() {
                    return this.close();
                }
            };
        }

        public InterAddress getRemote() {
            return this.server.getBindAddress();
        }

        public InterAddress getLocal() {
            return this.client.config().address();
        }

        public boolean isActive() {
            return this.active;
        }

        public BasicFuture<RsfChannel> sendData(Payload payload) {
            RequestPayload request = (RequestPayload) payload;
            BasicFuture<RsfChannel> sent = new BasicFuture<>();
            if (!this.active) {
                sent.failed(new IllegalStateException("Closed session"));
                return sent;
            }
            this.pending.put(request.getRequestID(), request);
            sent.completed(this);
            this.server.listener().onRequest(this.accepted, request.getRequestID(), request);
            return sent;
        }

        public BasicFuture<RsfChannel> drainAndClose() {
            return this.close();
        }

        public BasicFuture<RsfChannel> close() {
            this.active = false;
            this.pending.clear();
            this.client.fireChannelClosed(this);
            this.server.owner.fireChannelClosed(this.accepted);
            return new BasicFuture<>(this);
        }
    }

    private static TestConnectorManager subscribedManager(RsfContext context, ReceivedListener receiver) {
        TestConnectorManager manager = new TestConnectorManager(context);
        manager.init();
        manager.subscribe((channel, id, payload) -> {
            switch (payload.getType()) {
                case REQUEST:
                    receiver.onRequest(channel, id, (RequestPayload) payload);
                    break;
                case RESPONSE:
                    receiver.onResponse(channel, id, (ResponsePayload) payload);
                    break;
                case THROW:
                    receiver.onFailure(channel, id, (ThrowPayload) payload);
                    break;
            }
        });
        return manager;
    }
}
