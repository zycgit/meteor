/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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

public class ConnectorManagerTest {
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

    @Test
    public void initializationOnlyPreparesManagerAndOperationsRequireIt() throws Exception {
        BlockingConnector connector = new BlockingConnector(false);
        AtomicInteger created = new AtomicInteger();
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> {
                created.incrementAndGet();
                return connector.attach(connectorManager);
            });
            expectIllegalState(() -> manager.subscribe((channel, id, payload) -> {
            }));
            expectFailedOperation(manager.bind(connector.config()));
            expectFailedOperation(manager.connect(connector.config()));
            manager.init();
            manager.init();
            assertEquals(0, created.get());
            assertEquals(0, connector.initializations.get());
            assertNull(manager.find("endpoint"));
            manager.subscribe((channel, id, payload) -> {
            });
            assertSame(connector.binding, manager.bind(connector.config()));
            assertEquals(1, created.get());
            assertEquals(1, connector.initializations.get());
            manager.bind(connector.config());
            assertEquals(1, created.get());
            manager.close();
            expectIllegalState(() -> manager.subscribe(null));
            expectFailedOperation(manager.bind(connector.config()));
            expectFailedOperation(manager.connect(connector.config()));
            assertEquals(1, connector.closes.get());
        }
    }

    @Test
    public void firstConnectInitializesWithoutOpeningAnyListener() throws Exception {
        BlockingConnector connector = new BlockingConnector(false);
        AtomicInteger created = new AtomicInteger();
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> {
                created.incrementAndGet();
                return connector.attach(connectorManager);
            });
            assertSame(connector.channel, manager.connect(manager.config("endpoint").withAddress(connector.config().address())).get());
            assertEquals(1, created.get());
            assertEquals(1, connector.initializations.get());
            assertEquals(0, connector.bindings.get());
            manager.bind(connector.config());
            assertEquals(1, created.get());
            assertEquals(1, connector.initializations.get());
        }
    }

    @Test
    public void unusedFactoriesAllocateNoConnectorsEvenOnClose() throws Exception {
        try (TestConnectorManager manager = new TestConnectorManager(ConnectorResourcesTest.sharedContext())) {
            BlockingConnector connector = new BlockingConnector(false);
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> {
                throw new AssertionError("Unused factory invoked");
            });
            manager.init();
            manager.close();
        }
    }

    @Test
    public void closeFailsPendingBinding() throws Exception {
        BlockingConnector connector = new BlockingConnector(false);
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> connector.attach(connectorManager));
            assertSame(connector.binding, manager.bind(connector.config()));
            manager.close();
            expectFailedOperation(connector.binding);
            assertEquals(1, connector.closes.get());
        }
    }

    @Test
    public void factoryCanAccessManagerFromAnotherThreadAndFailureCanBeRetried() throws Exception {
        BlockingConnector connector = new BlockingConnector(false);
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService worker = Executors.newFixedThreadPool(2);
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            IllegalStateException failure = new IllegalStateException("factory failed");
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> {
                assertFalse(Thread.holdsLock(manager));
                assertSame(manager, connectorManager);
                assertTrue(worker.submit(() -> manager.protocols().isEmpty()).get(2, TimeUnit.SECONDS));
                if (attempts.incrementAndGet() == 1) {
                    throw failure;
                }
                return connector.attach(connectorManager);
            });
            assertSame(failure, ConnectorConnectionsTest.failure(manager.bind(connector.config())));
            assertNull(manager.find("endpoint"));
            assertNull(manager.forSchema("memory"));
            assertSame(connector.binding, manager.bind(connector.config()));
            assertSame(connector, manager.find("endpoint"));
            assertSame(connector, manager.forSchema("MEMORY"));
            assertEquals(2, attempts.get());
        } finally {
            worker.shutdownNow();
        }
        assertEquals(1, connector.closes.get());
    }

    @Test
    public void concurrentFirstBindAndConnectShareCreationAndWaitForInitialization() throws Exception {
        BlockingConnector connector = new BlockingConnector(true);
        AtomicInteger created = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> {
                created.incrementAndGet();
                return connector.attach(connectorManager);
            });
            Future<?> first = workers.submit(() -> manager.bind(connector.config()));
            assertTrue(connector.entered.await(2, TimeUnit.SECONDS));
            Future<?> second = workers.submit(() -> manager.connect(manager.config("endpoint").withAddress(connector.config().address())));
            assertNull(manager.find("endpoint"));
            assertNull(manager.forSchema("memory"));
            assertEquals(0, connector.bindings.get());
            connector.release.countDown();
            assertSame(connector.binding, first.get(2, TimeUnit.SECONDS));
            assertSame(connector.channel, ((Future<?>) second.get(2, TimeUnit.SECONDS)).get(2, TimeUnit.SECONDS));
            assertSame(connector, manager.find("endpoint"));
            assertSame(connector, manager.forSchema("MEMORY"));
            assertEquals(1, created.get());
            assertEquals(1, connector.initializations.get());
        } finally {
            connector.release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void readyConnectorDoesNotWaitForAnotherConnectorInitialization() throws Exception {
        BlockingConnector ready = new BlockingConnector("ready", false);
        BlockingConnector starting = new BlockingConnector("starting", true);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            manager.prepare(ready.config(), (connectorConfig, connectorManager) -> ready.attach(connectorManager));
            manager.prepare(starting.config(), (connectorConfig, connectorManager) -> starting.attach(connectorManager));
            assertSame(ready.channel, manager.connect(manager.config("ready").withAddress(ready.config().address())).get());

            Future<?> opening = workers.submit(() -> manager.bind(starting.config()));
            try {
                assertTrue(starting.entered.await(2, TimeUnit.SECONDS));
                Future<?> connecting = workers.submit(() -> manager.connect(manager.config("ready").withAddress(ready.config().address())).get());
                assertSame(ready.channel, connecting.get(2, TimeUnit.SECONDS));
                Future<?> binding = workers.submit(() -> manager.bind(ready.config()));
                assertSame(ready.binding, binding.get(2, TimeUnit.SECONDS));
                assertFalse(opening.isDone());
                assertEquals(1, ready.initializations.get());
            } finally {
                starting.release.countDown();
            }
            assertSame(starting.binding, opening.get(2, TimeUnit.SECONDS));
        } finally {
            starting.release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    public void closeWaitsForInitializationAndReclaimsConnectorBeforeRestart() throws Exception {
        BlockingConnector connector = new BlockingConnector(true);
        ExecutorService worker = Executors.newFixedThreadPool(2);
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            manager.prepare(connector.config(), (connectorConfig, connectorManager) -> connector.attach(connectorManager));
            Future<?> opening = worker.submit(() -> manager.bind(connector.config()));
            assertTrue(connector.entered.await(2, TimeUnit.SECONDS));
            Future<?> closing = worker.submit(manager::close);
            try {
                closing.get(100, TimeUnit.MILLISECONDS);
                fail("close must wait for connector initialization");
            } catch (TimeoutException expected) {
                // The manager timer must remain alive until provider disposal finishes.
            }
            assertTrue(manager.isInitialized());
            connector.release.countDown();
            closing.get(2, TimeUnit.SECONDS);
            assertNull(manager.find("endpoint"));
            assertNull(manager.forSchema("memory"));
            assertTrue(manager.protocols().isEmpty());
            BlockingConnector replacement = new BlockingConnector(false);
            manager.prepare(replacement.config(), (connectorConfig, connectorManager) -> replacement.attach(connectorManager));
            manager.init();
            assertSame(replacement.binding, manager.bind(replacement.config()));
            connector.release.countDown();
            Object binding = opening.get(2, TimeUnit.SECONDS);
            expectFailedOperation((Future<?>) binding);
            // The bind may start before admission stops, or fail when shutdown wins.
            assertTrue(connector.bindings.get() <= 1);
            assertEquals(1, connector.closes.get());
            assertSame(replacement, manager.find("endpoint"));
            assertEquals(0, replacement.closes.get());
        } finally {
            connector.release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    public void managerCanStartAgainWithNewConnectorsAfterClose() throws Exception {
        BlockingConnector first = new BlockingConnector(false);
        BlockingConnector second = new BlockingConnector(false);
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER)) {
            manager.prepare(first.config(), (connectorConfig, connectorManager) -> first.attach(connectorManager));
            manager.bind(first.config());
            manager.close();
            manager.close();
            assertEquals(1, first.closes.get());
            manager.prepare(second.config(), (connectorConfig, connectorManager) -> second.attach(connectorManager));
            expectFailedOperation(manager.bind(second.config()));
            manager.init();
            assertEquals(0, second.initializations.get());
            assertSame(second.binding, manager.bind(second.config()));
            BasicFuture<Boolean> scheduled = new BasicFuture<>();
            manager.schedule(() -> scheduled.completed(true), 0);
            assertTrue(scheduled.get(2, TimeUnit.SECONDS));
            manager.close();
            assertEquals(1, second.closes.get());
        }
    }

    private void expectIllegalState(CheckedAction action) throws Exception {
        try {
            action.run();
            fail("Expected an invalid lifecycle operation to fail");
        } catch (IllegalStateException expected) {
        }
    }

    private void expectFailedOperation(Future<?> operation) throws Exception {
        try {
            operation.get(2, TimeUnit.SECONDS);
            fail("An operation interrupted by close must not succeed");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private static final class BlockingConnector implements RsfConnector {
        private ConnectorManager       manager;
        private Consumer<RsfConnector> closingListener;
        private Consumer<RsfChannel>   channelConnectedListener;
        private Consumer<RsfChannel>   channelClosedListener;

        public void onClosing(Consumer<RsfConnector> listener) {
            this.closingListener = listener;
        }

        public void onChannelClosed(Consumer<RsfChannel> listener) {
            this.channelClosedListener = listener;
        }

        public void onChannelConnected(Consumer<RsfChannel> listener) {
            this.channelConnectedListener = listener;
        }

        public ConnectorManager manager() {
            return this.manager;
        }

        public List<RsfListen> getListenList() {
            return Collections.emptyList();
        }

        public InterAddress getBindAddress() {
            return null;
        }

        private final ConnectorConfig        config;
        private final boolean                blockInitialization;
        private final CountDownLatch         entered         = new CountDownLatch(1);
        private final CountDownLatch         release         = new CountDownLatch(1);
        private final AtomicInteger          initializations = new AtomicInteger();
        private final AtomicInteger          bindings        = new AtomicInteger();
        private final AtomicInteger          closes          = new AtomicInteger();
        private final BasicFuture<RsfListen> binding         = new BasicFuture<>();
        private final RsfChannel             channel         = new RsfChannel() {
            public RsfConnector getConnector() {
                return BlockingConnector.this;
            }

            public long getChannelId() {
                return 1;
            }

            public InterAddress getLocal() {
                return BlockingConnector.this.config.address();
            }

            public InterAddress getRemote() {
                return BlockingConnector.this.config.address();
            }

            public boolean isActive() {
                return true;
            }

            public BasicFuture<RsfChannel> sendData(Payload payload) {
                return new BasicFuture<>(this);
            }

            public BasicFuture<RsfChannel> close() {
                if (BlockingConnector.this.channelClosedListener != null) {
                    BlockingConnector.this.channelClosedListener.accept(this);
                }
                return new BasicFuture<>(this);
            }

            public BasicFuture<RsfChannel> drainAndClose() {
                return this.close();
            }
        };

        BlockingConnector(boolean blockInitialization) throws Exception {
            this("endpoint", blockInitialization);
        }

        BlockingConnector(String name, boolean blockInitialization) throws Exception {
            this.config = new ConnectorConfig(name, new InterAddress("memory://localhost:1/default"), Collections.singletonMap("listenType", "memory"));
            this.blockInitialization = blockInitialization;
        }

        public ConnectorConfig config() {
            return this.config;
        }

        public void init() throws Exception {
            this.initializations.incrementAndGet();
            if (this.blockInitialization) {
                pause();
            }
        }

        public BasicFuture<RsfListen> bind(InterAddress address) {
            if (!this.manager.isInitialized() || this.binding.isDone()) {
                this.binding.failed(new IllegalStateException("RsfConnector is closed"));
                return this.binding;
            }
            this.bindings.incrementAndGet();
            return this.binding;
        }

        private void pause() throws Exception {
            this.entered.countDown();
            assertTrue(this.release.await(5, TimeUnit.SECONDS));
        }

        public BasicFuture<RsfChannel> connect(InterAddress target) {
            assertEquals(1, this.initializations.get());
            this.channelConnectedListener.accept(this.channel);
            return new BasicFuture<>(this.channel);
        }

        private RsfConnector attach(ConnectorManager manager) {
            this.manager = manager;
            return this;
        }

        public void close() {
            if (this.closingListener != null) {
                Consumer<RsfConnector> listener = this.closingListener;
                this.closingListener = null;
                listener.accept(this);
            }
            this.closes.incrementAndGet();
            this.binding.failed(new IllegalStateException("closed"));
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
