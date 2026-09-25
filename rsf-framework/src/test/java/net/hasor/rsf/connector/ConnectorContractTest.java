/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.payload.Payload;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectorContractTest {
    private static final ReceivedListener RECEIVER = new ReceivedListener() {
        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
            throw new AssertionError("Unexpected request");
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
            throw new AssertionError("Unexpected response");
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
            throw new AssertionError(failure.getThrowable());
        }
    };

    private InterAddress peer() throws Exception {
        return new InterAddress("rsf://127.0.0.1:2181/default");
    }

    @Test
    public void bindingFailureLeavesOtherEndpointsUsableUntilExplicitClose() throws Exception {
        List<String> events = new ArrayList<>();
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            RsfConnector first = endpoint(manager, "first", events, false);
            RsfConnector second = endpoint(manager, "second", events, true);
            manager.prepare(first.config(), (connectorConfig, connectorManager) -> first);
            manager.prepare(second.config(), (connectorConfig, connectorManager) -> second);
            manager.init();
            RsfListen listening = manager.bind(manager.config("first")).get();
            assertEquals("bind failed", manager.bind(manager.config("second")).getCause().getMessage());
            assertTrue(listening.isActive());
            assertSame(first, manager.find("first"));
            assertEquals(Arrays.asList("bind:first", "bind:second"), events);
            manager.close();
            assertEquals(Arrays.asList("bind:first", "bind:second", "closeBind:first", "close:first", "close:second"), events);
            assertTrue(manager.protocols().isEmpty());
        }
        assertEquals(5, events.size());
    }

    @Test
    public void closePhasesAreNotPublicConnectorOperations() throws Exception {
        for (Class<?> contract : Arrays.asList(RsfConnector.class, AbstractConnector.class)) {
            for (String name : Arrays.asList("disableBind", "closeBind", "disableWrite", "prepareClose")) {
                try {
                    contract.getMethod(name);
                    fail("Shutdown phases must not be public: " + name);
                } catch (NoSuchMethodException expected) {
                    // Users close the manager, connector or individual listener instead.
                }
            }
        }
    }

    @Test
    public void listenerPhaseFailureStillRunsEveryRemainingPhase() throws Exception {
        List<String> events = new ArrayList<>();
        IllegalStateException failure = new IllegalStateException("listener cleanup failed");
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            RsfConnector first = endpoint(manager, "first", events, false);
            RsfConnector second = endpoint(manager, "second", events, false, failure);
            manager.prepare(first.config(), (connectorConfig, connectorManager) -> first);
            manager.prepare(second.config(), (connectorConfig, connectorManager) -> second);
            manager.bind(manager.config("first")).get();
            manager.bind(manager.config("second")).get();
            manager.close();
            assertClosePhases(events.subList(2, events.size()));
            assertTrue(manager.protocols().isEmpty());
        }
        assertEquals(6, events.size());
    }

    @Test
    public void providerWithoutCloseCallbacksUsesItsListenerHandles() throws Exception {
        List<String> events = new ArrayList<>();
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            RsfConnector connector = endpoint(manager, "plain", events, false);
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> connector);
            RsfListen listen = manager.bind(manager.config("plain")).get();
            assertSame(connector, manager.find("plain"));
            manager.close();
            assertFalse(listen.isActive());
            assertEquals(Arrays.asList("bind:plain", "closeBind:plain", "close:plain"), events);
        }
    }

    @Test(timeout = 10000)
    public void allListenersCloseBeforeDrainAndResourcesWaitForEveryChannel() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<BasicFuture<RsfChannel>> pending = new ArrayList<>();
        List<RsfChannel> channels = new ArrayList<>();
        CountDownLatch draining = new CountDownLatch(2);
        ExecutorService lifecycle = Executors.newSingleThreadExecutor();
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            try {
                for (String name : Arrays.asList("first", "second")) {
                    PlainConnector connector = endpoint(manager, name, events, false);
                    manager.prepare(connector.config(), (connectorConfig, connectorManager) -> connector);
                    manager.bind(manager.config(name)).get();
                    BasicFuture<RsfChannel> closed = new BasicFuture<>();
                    RsfChannel channel = new AbstractRsfChannel(connector, manager.nextConnectionId(), RECEIVER) {
                        public InterAddress getLocal() {
                            return connector.config().address();
                        }

                        public InterAddress getRemote() {
                            return connector.config().address();
                        }

                        public boolean isActive() {
                            return !closed.isDone();
                        }

                        public BasicFuture<RsfChannel> sendData(Payload payload) {
                            throw new UnsupportedOperationException();
                        }

                        public BasicFuture<RsfChannel> close() {
                            throw new AssertionError("Must drain submitted writes");
                        }

                        public BasicFuture<RsfChannel> drainAndClose() {
                            assertTrue(events.containsAll(Arrays.asList("closeBind:first", "closeBind:second")));
                            draining.countDown();
                            return closed;
                        }
                    };
                    channels.add(channel);
                    pending.add(closed);
                    connector.accept(channel);
                }
                Future<?> closing = lifecycle.submit(manager::close);
                assertTrue(draining.await(2, TimeUnit.SECONDS));
                assertFalse(closing.isDone());
                assertFalse(events.contains("close:first"));
                assertFalse(events.contains("close:second"));
                for (int i = 0; i < pending.size(); i++) {
                    pending.get(i).completed(channels.get(i));
                }
                closing.get(2, TimeUnit.SECONDS);
                assertTrue(manager.getConnections().isEmpty());
                assertClosePhases(events.subList(2, events.size()));
            } finally {
                for (int i = 0; i < pending.size(); i++) {
                    pending.get(i).completed(channels.get(i));
                }
                lifecycle.shutdown();
                assertTrue(lifecycle.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 10000)
    public void unfinishedPhysicalListenerClosesBeforeChannelsDrain() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        BasicFuture<RsfListen> binding = new BasicFuture<>();
        BasicFuture<RsfChannel> drained = new BasicFuture<>();
        CountDownLatch draining = new CountDownLatch(1);
        ExecutorService lifecycle = Executors.newSingleThreadExecutor();
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            ConnectorConfig config = new ConnectorConfig("pending", peer(), Collections.singletonMap("listenType", "memory"));
            RsfListen socket = new RsfListen() {
                private volatile boolean closed;

                public String getType() {
                    return "memory";
                }

                public InterAddress getBindAddress() {
                    return null;
                }

                public boolean isActive() {
                    return false;
                }

                public void close() {
                    if (!this.closed) {
                        this.closed = true;
                        events.add("listen");
                        binding.failed(new IllegalStateException("Listener closed before binding completed"));
                    }
                }
            };
            PlainConnector connector = new PlainConnector() {
                private Consumer<RsfConnector> closingListener;

                public void onClosing(Consumer<RsfConnector> listener) {
                    this.closingListener = listener;
                }

                public void onChannelClosed(Consumer<RsfChannel> listener) {
                    // Exercise the manager's fallback cleanup after draining.
                }

                public ConnectorConfig config() {
                    return config;
                }

                public ConnectorManager manager() {
                    return manager;
                }

                public void init() {
                }

                public List<RsfListen> getListenList() {
                    return Collections.emptyList();
                }

                public InterAddress getBindAddress() {
                    return null;
                }

                public BasicFuture<RsfListen> bind(InterAddress address) {
                    if (!manager.onListen(this, socket)) {
                        socket.close();
                    }
                    return binding;
                }

                public BasicFuture<RsfChannel> connect(InterAddress address) {
                    throw new UnsupportedOperationException();
                }

                public void close() {
                    if (this.closingListener != null) {
                        Consumer<RsfConnector> listener = this.closingListener;
                        this.closingListener = null;
                        listener.accept(this);
                    }
                    events.add("engine");
                }
            };
            manager.prepare(config, (connectorConfig, connectorManager) -> connector);
            assertSame(binding, manager.bind(config));
            RsfChannel channel = new AbstractRsfChannel(connector, manager.nextConnectionId(), RECEIVER) {
                public InterAddress getLocal() {
                    return config.address();
                }

                public InterAddress getRemote() {
                    return config.address();
                }

                public boolean isActive() {
                    return !drained.isDone();
                }

                public BasicFuture<RsfChannel> sendData(Payload payload) {
                    throw new UnsupportedOperationException();
                }

                public BasicFuture<RsfChannel> close() {
                    throw new AssertionError("Must drain submitted writes");
                }

                public BasicFuture<RsfChannel> drainAndClose() {
                    assertEquals(Collections.singletonList("listen"), events);
                    assertTrue(binding.getCause() instanceof IllegalStateException);
                    draining.countDown();
                    return drained;
                }
            };
            connector.accept(channel);
            try {
                Future<?> closing = lifecycle.submit(manager::close);
                assertTrue(draining.await(2, TimeUnit.SECONDS));
                assertFalse(closing.isDone());
                assertFalse(events.contains("engine"));
                drained.completed(channel);
                closing.get(2, TimeUnit.SECONDS);
                assertEquals(Arrays.asList("listen", "engine"), events);
                assertTrue(manager.getListenList(connector).isEmpty());
                assertFalse(manager.onListen(connector, socket));
            } finally {
                drained.completed(channel);
                lifecycle.shutdown();
                assertTrue(lifecycle.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static void assertClosePhases(List<String> events) {
        assertEquals(4, events.size());
        int firstResourceClose = Math.min(events.indexOf("close:first"), events.indexOf("close:second"));
        for (String name : Arrays.asList("first", "second")) {
            int closeListener = events.indexOf("closeBind:" + name);
            assertTrue(closeListener >= 0);
            assertTrue(closeListener < firstResourceClose);
        }
    }

    private PlainConnector endpoint(ConnectorManager manager, String name, List<String> events, boolean fail) throws Exception {
        return endpoint(manager, name, events, fail, null);
    }

    private PlainConnector endpoint(ConnectorManager manager, String name, List<String> events, boolean fail, RuntimeException closeFailure) throws Exception {
        ConnectorConfig config = new ConnectorConfig(name, peer(), Collections.singletonMap("listenType", "memory"));
        return new PlainConnector() {
            private Consumer<RsfConnector> closingListener;

            public void onClosing(Consumer<RsfConnector> listener) {
                this.closingListener = listener;
            }

            public void onChannelClosed(Consumer<RsfChannel> listener) {
                // This listener-only fixture does not create channels.
            }

            public ConnectorManager manager() {
                return manager;
            }

            private final List<RsfListen> listens = new ArrayList<>();

            public List<RsfListen> getListenList() {
                return new ArrayList<>(this.listens);
            }

            public InterAddress getBindAddress() {
                return null;
            }

            public ConnectorConfig config() {
                return config;
            }

            public void init() {
            }

            public BasicFuture<RsfListen> bind(InterAddress address) {
                BasicFuture<RsfListen> result = new BasicFuture<>();
                events.add("bind:" + name);
                if (fail) {
                    result.failed(new IllegalStateException("bind failed"));
                } else {
                    RsfListen listen = new RsfListen() {
                        private boolean active = true;

                        public String getType() {
                            return config.listenType();
                        }

                        public InterAddress getBindAddress() {
                            return address;
                        }

                        public boolean isActive() {
                            return this.active;
                        }

                        public void close() {
                            if (this.active) {
                                this.active = false;
                                events.add("closeBind:" + name);
                                if (closeFailure != null) {
                                    throw closeFailure;
                                }
                            }
                        }
                    };
                    if (manager.onListen(this, listen)) {
                        this.listens.add(listen);
                        result.completed(listen);
                    } else {
                        listen.close();
                        result.failed(new IllegalStateException("Manager is closed"));
                    }
                }
                return result;
            }

            public BasicFuture<RsfChannel> connect(InterAddress address) {
                throw new UnsupportedOperationException();
            }

            public void close() {
                if (this.closingListener != null) {
                    Consumer<RsfConnector> listener = this.closingListener;
                    this.closingListener = null;
                    listener.accept(this);
                }
                events.add("close:" + name);
            }
        };
    }

    /** Simulates incoming channels without depending on AbstractConnector. */
    private abstract static class PlainConnector implements RsfConnector {
        private Consumer<RsfChannel> channelConnectedListener;

        public final void onChannelConnected(Consumer<RsfChannel> listener) {
            this.channelConnectedListener = listener;
        }

        public final void accept(RsfChannel channel) {
            this.channelConnectedListener.accept(channel);
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
