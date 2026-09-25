/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.concurrent.future.FutureListener;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class RsfListenTest {
    private static final ReceivedListener RECEIVER = new ReceivedListener() {
        public void onRequest(RsfChannel channel, long id, RequestPayload request) {
        }

        public void onResponse(RsfChannel channel, long id, ResponsePayload response) {
        }

        public void onFailure(RsfChannel channel, long id, ThrowPayload failure) {
        }
    };

    @Test
    public void completesOnlyAfterListenerIsUsableAndRegistered() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> future = endpoint.bind(address(2001));
            AtomicInteger completed = new AtomicInteger();
            future.onCompleted(done -> {
                assertFalse(Thread.holdsLock(endpoint));
                assertSame(done.getResult(), endpoint.getListenList().get(0));
                assertEquals(address(2001), endpoint.getBindAddress());
                completed.incrementAndGet();
            });
            assertFalse(future.isDone());
            assertTrue(endpoint.getListenList().isEmpty());
            assertNull(endpoint.getBindAddress());
            RsfListen listen = endpoint.succeed(0, "tcp");
            assertSame(listen, future.get(2, TimeUnit.SECONDS));
            assertEquals(1, completed.get());
            future.onCompleted(done -> completed.incrementAndGet());
            assertEquals(2, completed.get());
        }
    }

    @Test
    public void bindsSuppliedAddressesIndependentlyAndSharesDuplicateOperation() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> first = endpoint.bind(address(2001));
            assertSame(first, endpoint.bind(address(2001)));
            Future<RsfListen> second = endpoint.bind(address(2002));
            assertEquals(Arrays.asList(address(2001), address(2002)), endpoint.requested);
            RsfListen secondListen = endpoint.succeed(1, "tcp");
            assertFalse(first.isDone());
            assertSame(secondListen, second.get());
            assertEquals(address(2002), endpoint.getBindAddress());
            RsfListen firstListen = endpoint.succeed(0, "tcp");
            assertSame(firstListen, first.get());
            assertSame(first, endpoint.bind(address(2001)));
            assertEquals(2, endpoint.operations.size());
            assertEquals(Arrays.asList(secondListen, firstListen), endpoint.getListenList());
            assertEquals(address(2002), endpoint.getBindAddress());
            assertNotEquals(endpoint.config().address(), endpoint.getBindAddress());
            secondListen.close();
            assertEquals(address(2001), endpoint.getBindAddress());
            firstListen.close();
            assertNull(endpoint.getBindAddress());
        }
    }

    @Test
    public void cancellationIsIsolatedBetweenAddresses() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> first = endpoint.bind(address(2001));
            assertSame(first, endpoint.bind(address(2001)));
            Future<RsfListen> second = endpoint.bind(address(2002));
            assertNotSame(first, second);
            assertEquals(Arrays.asList("tcp", "tcp"), endpoint.requestedTypes);
            first.cancel();
            assertFalse(second.isDone());
            assertFalse(endpoint.succeed(0, "tcp").isActive());
            RsfListen bound = endpoint.succeed(1, "tcp");
            assertSame(bound, second.get());
            assertSame(second, endpoint.bind(address(2002)));
            assertEquals(Collections.singletonList(bound), endpoint.getListenList());
            assertEquals(address(2002), endpoint.getBindAddress());
        }
    }

    @Test
    public void usesConfiguredTypeWithoutInferringItFromAddress() throws Exception {
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER); Endpoint endpoint = new Endpoint(manager)) {
            manager.init();
            Future<RsfListen> binding = manager.bind(endpoint.config().withAddress(address(2001)));
            assertEquals(Collections.singletonList("tcp"), endpoint.requestedTypes);
            assertSame(endpoint.succeed(0, "tcp"), binding.get());
            assertEquals("rsf", endpoint.getBindAddress().getSchema());
        }
    }

    @Test
    public void providerCannotSilentlyReturnAnotherListenerType() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> binding = endpoint.bind(address(2001));
            TestListen wrong = new TestListen(endpoint, "http", address(2001));
            endpoint.operations.get(0).completed(wrong);
            assertTrue(binding.isDone());
            assertTrue(binding.getCause() instanceof IllegalStateException);
            assertFalse(wrong.isActive());
            assertTrue(endpoint.getListenList().isEmpty());
            Future<RsfListen> retry = endpoint.bind(address(2001));
            assertSame(endpoint.succeed(1, "tcp"), retry.get());
        }
    }

    @Test
    public void snapshotIsImmutableAndClosedListenerCanBeRebound() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            endpoint.bind(address(2001));
            RsfListen first = endpoint.succeed(0, "tcp");
            List<RsfListen> snapshot = endpoint.getListenList();
            try {
                snapshot.clear();
                fail("Snapshot must be immutable");
            } catch (UnsupportedOperationException expected) {
                assertEquals(1, endpoint.getListenList().size());
            }
            first.close();
            assertNull(endpoint.getBindAddress());
            Future<RsfListen> rebound = endpoint.bind(address(2001));
            assertFalse(rebound.isDone());
            RsfListen replacement = endpoint.succeed(1, "tcp");
            assertSame(replacement, rebound.get());
            assertEquals(Collections.singletonList(replacement), endpoint.getListenList());
            endpoint.close();
            endpoint.close();
            assertTrue(endpoint.getListenList().isEmpty());
            assertFalse(first.isActive());
            assertFalse(replacement.isActive());
            assertEquals(1, snapshot.size());
            assertEquals(1, endpoint.destroyed);
        }
    }

    @Test
    public void providerCancellationCancelsBindAndAllowsRetry() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> binding = endpoint.bind(address(2001));
            endpoint.operations.get(0).cancel();
            assertTrue(binding.isCancelled());
            assertTrue(endpoint.getListenList().isEmpty());
            Future<RsfListen> retry = endpoint.bind(address(2001));
            assertSame(endpoint.succeed(1, "tcp"), retry.get());
        }
    }

    @Test
    public void failedBindNotifiesFailureAndDoesNotDestroyExistingListeners() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            endpoint.bind(address(2001));
            RsfListen existing = endpoint.succeed(0, "tcp");
            Future<RsfListen> failed = endpoint.bind(address(2002));
            IOException cause = new IOException("address occupied");
            AtomicInteger callbacks = new AtomicInteger();
            failed.onFailed(done -> {
                assertFalse(Thread.holdsLock(endpoint));
                assertSame(cause, done.getCause());
                callbacks.incrementAndGet();
            });
            endpoint.operations.get(1).failed(cause);
            assertFailure(failed, cause);
            assertEquals(1, callbacks.get());
            assertTrue(existing.isActive());
            assertEquals(0, endpoint.destroyed);
            Future<RsfListen> retry = endpoint.bind(address(2002));
            assertSame(endpoint.succeed(2, "tcp"), retry.get());
        }
    }

    @Test
    public void failureAndCancellationCallbacksCanImmediatelyRetryBinding() throws Exception {
        for (boolean cancel : new boolean[] { false, true }) {
            try (Endpoint endpoint = new Endpoint()) {
                endpoint.initializeThroughManager();
                Future<RsfListen> binding = endpoint.bind(address(2001));
                BasicFuture<Future<RsfListen>> retried = new BasicFuture<>();
                FutureListener<Future<RsfListen>> retry = done -> {
                    assertFalse(Thread.holdsLock(endpoint));
                    retried.completed(endpoint.bind(address(2001)));
                };
                binding.onFailed(retry).onCancel(retry);
                if (cancel) {
                    endpoint.operations.get(0).cancel();
                } else {
                    endpoint.operations.get(0).failed(new IOException("address occupied"));
                }
                Future<RsfListen> replacement = retried.get(2, TimeUnit.SECONDS);
                assertNotSame(binding, replacement);
                assertSame(replacement, endpoint.bind(address(2001)));
                assertSame(endpoint.succeed(1, "tcp"), replacement.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void nullProviderResultFailsBindingAndAllowsRetry() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> binding = endpoint.bind(address(2001));
            endpoint.operations.get(0).completed(null);
            assertTrue(binding.isDone());
            assertTrue(binding.getCause() instanceof IllegalStateException);
            assertTrue(endpoint.getListenList().isEmpty());
            Future<RsfListen> retry = endpoint.bind(address(2001));
            assertNotSame(binding, retry);
            assertSame(endpoint.succeed(1, "tcp"), retry.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void synchronousProviderFailureAlsoCompletesFutureAndAllowsRetry() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            IOException cause = new IOException("setup failed");
            endpoint.setupFailure = cause;
            Future<RsfListen> failed = endpoint.bind(address(2001));
            assertFailure(failed, cause);
            endpoint.setupFailure = null;
            Future<RsfListen> retry = endpoint.bind(address(2001));
            assertSame(endpoint.succeed(0, "tcp"), retry.get());
        }
    }

    @Test
    public void closeStopsListenersAndWritesAndReleasesTheEngineOnce() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            endpoint.bind(address(2001));
            TestListen listening = endpoint.succeed(0, "tcp");
            Future<RsfListen> pending = endpoint.bind(address(2002));
            endpoint.close();
            assertFalse(listening.isActive());
            assertEquals(1, listening.closes);
            assertTrue(pending.getCause() instanceof IllegalStateException);
            assertTrue(endpoint.getListenList().isEmpty());
            assertEquals(1, endpoint.destroyed);
            assertNotNull(endpoint.connect(address(2002)).getCause());
            try {
                endpoint.bind(address(2003));
                fail("Close must reject new listeners");
            } catch (IllegalStateException expected) {
                assertEquals(2, endpoint.operations.size());
            }
            endpoint.close();
            assertEquals(1, endpoint.destroyed);
            assertEquals(1, listening.closes);
        }
    }

    @Test
    public void closeFailsEveryPendingBindOutsideConnectorLock() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> first = endpoint.bind(address(2001));
            Future<RsfListen> second = endpoint.bind(address(2002));
            AtomicInteger notified = new AtomicInteger();
            first.onFailed(done -> {
                assertFalse(Thread.holdsLock(endpoint));
                notified.incrementAndGet();
                throw new AssertionError("Callback failure must not stop cleanup");
            });
            second.onFailed(done -> {
                assertFalse(Thread.holdsLock(endpoint));
                notified.incrementAndGet();
            });
            endpoint.close();
            assertTrue(first.getCause() instanceof IllegalStateException);
            assertTrue(second.getCause() instanceof IllegalStateException);
            assertEquals(2, notified.get());
            assertEquals(1, endpoint.destroyed);
            assertFalse(endpoint.succeed(0, "tcp").isActive());
            assertFalse(endpoint.succeed(1, "tcp").isActive());
            assertTrue(endpoint.getListenList().isEmpty());
        }
    }

    @Test
    public void closeReclaimsLateBindingsAndInitializationCannotReopenIt() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> pending = endpoint.bind(address(2001));
            endpoint.close();
            TestListen late = endpoint.succeed(0, "tcp");
            assertFalse(late.isActive());
            assertEquals(1, late.closes);
            assertTrue(pending.getCause() instanceof IllegalStateException);
            try {
                endpoint.init();
                fail("Initialization must not reopen a closed connector");
            } catch (IllegalStateException expected) {
                assertEquals(1, endpoint.destroyed);
            }
        }
    }

    @Test
    public void managerCloseReclaimsListenersCompletedAfterInternalShutdownPhases() throws Exception {
        try (TestConnectorManager manager = subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER); Endpoint endpoint = new Endpoint(manager)) {
            Future<RsfListen> pending = manager.bind(endpoint.config().withAddress(address(2001)));
            manager.close();
            assertTrue(pending.getCause() instanceof IllegalStateException);
            TestListen late = endpoint.succeed(0, "tcp");
            assertFalse(late.isActive());
            assertEquals(1, late.closes);
            assertEquals(1, endpoint.destroyed);
            assertTrue(endpoint.getListenList().isEmpty());
            manager.close();
            assertEquals(1, endpoint.destroyed);
        }
    }

    @Test
    public void closeFailsPendingBindAndReclaimsLateSuccessfulListener() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.initializeThroughManager();
        Future<RsfListen> pending = endpoint.bind(address(2001));
        endpoint.close();
        assertTrue(pending.isDone());
        assertTrue(pending.getCause() instanceof IllegalStateException);
        RsfListen late = endpoint.succeed(0, "tcp");
        assertFalse(late.isActive());
        assertTrue(endpoint.getListenList().isEmpty());
        assertNull(endpoint.getBindAddress());
        try {
            endpoint.bind(address(2002));
            fail("Closed connector cannot bind");
        } catch (IllegalStateException expected) {
            assertEquals(1, endpoint.operations.size());
        }
    }

    @Test
    public void cancellationReclaimsLateListenerWithoutDiscardingNewAttempt() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> cancelled = endpoint.bind(address(2001));
            assertTrue(cancelled.cancel());
            Future<RsfListen> retry = endpoint.bind(address(2001));
            RsfListen late = endpoint.succeed(0, "tcp");
            assertFalse(late.isActive());
            assertSame(retry, endpoint.bind(address(2001)));
            RsfListen replacement = endpoint.succeed(1, "tcp");
            assertSame(replacement, retry.get());
            assertTrue(replacement.isActive());
        }
    }

    @Test
    public void cancellationOrCloseAfterRegistrationReclaimsListenerWithoutRemovingRetry() throws Exception {
        for (boolean closeConnector : new boolean[] { false, true }) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try (Endpoint endpoint = new Endpoint()) {
                endpoint.initializeThroughManager();
                Future<RsfListen> binding = endpoint.bind(address(2001));
                BasicFuture<RsfListen> provider = endpoint.operations.get(0);
                TestListen bound = new TestListen(endpoint, "tcp", address(2001));
                BasicFuture<Void> finished = new BasicFuture<>();
                Future<RsfListen> retry = null;
                // Hold the future monitor so registration can finish, but successful notification must wait.
                synchronized (binding) {
                    worker.execute(() -> {
                        try {
                            provider.completed(bound);
                            finished.completed(null);
                        } catch (Throwable failure) {
                            finished.failed(failure);
                        }
                    });
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (endpoint.getListenList().isEmpty() && System.nanoTime() < deadline) {
                        Thread.sleep(1);
                    }
                    assertEquals(Collections.singletonList(bound), endpoint.getListenList());
                    assertFalse(binding.isDone());
                    if (closeConnector) {
                        endpoint.close();
                    } else {
                        assertTrue(binding.cancel());
                        retry = endpoint.bind(address(2001));
                    }
                }
                finished.get(2, TimeUnit.SECONDS);
                assertFalse(bound.isActive());
                assertTrue(endpoint.getListenList().isEmpty());
                if (closeConnector) {
                    assertTrue(binding.getCause() instanceof IllegalStateException);
                } else {
                    assertTrue(binding.isCancelled());
                    assertSame(retry, endpoint.bind(address(2001)));
                    assertSame(endpoint.succeed(1, "tcp"), retry.get());
                }
            } finally {
                worker.shutdownNow();
            }
        }
    }

    @Test
    public void invalidResultCleanupFailureStillNotifiesAndAllowsRetry() throws Exception {
        try (Endpoint endpoint = new Endpoint()) {
            endpoint.initializeThroughManager();
            Future<RsfListen> binding = endpoint.bind(address(2001));
            TestListen wrong = new TestListen(endpoint, "http", address(2001));
            IllegalStateException cleanup = new IllegalStateException("cleanup failed");
            wrong.closeFailure = cleanup;
            AtomicInteger callbacks = new AtomicInteger();
            binding.onFailed(done -> {
                assertFalse(Thread.holdsLock(endpoint));
                assertFalse(wrong.isActive());
                callbacks.incrementAndGet();
            });
            endpoint.operations.get(0).completed(wrong);
            assertTrue(binding.getCause() instanceof IllegalStateException);
            assertArrayEquals(new Throwable[] { cleanup }, binding.getCause().getSuppressed());
            assertEquals(1, callbacks.get());
            assertTrue(endpoint.getListenList().isEmpty());
            Future<RsfListen> retry = endpoint.bind(address(2001));
            assertSame(endpoint.succeed(1, "tcp"), retry.get());
        }
    }

    @Test
    public void completionCallbackCanCloseConnectorWithoutDeadlockOrResourceLeak() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.initializeThroughManager();
        Future<RsfListen> future = endpoint.bind(address(2001));
        future.onCompleted(done -> endpoint.close());
        RsfListen bound = endpoint.succeed(0, "tcp");
        assertSame(bound, future.get());
        assertFalse(bound.isActive());
        assertTrue(endpoint.getListenList().isEmpty());
        assertEquals(1, endpoint.destroyed);
    }

    @Test
    public void closeFailureStillClosesOtherListenersAndFailsPendingOperations() throws Exception {
        Endpoint endpoint = new Endpoint();
        endpoint.initializeThroughManager();
        endpoint.bind(address(2001));
        endpoint.succeed(0, "tcp");
        endpoint.bind(address(2002));
        TestListen broken = endpoint.succeed(1, "tcp");
        Future<RsfListen> pending = endpoint.bind(address(2003));
        IllegalStateException failure = new IllegalStateException("cleanup failed");
        broken.closeFailure = failure;
        endpoint.close();
        assertEquals(1, endpoint.destroyed);
        assertFalse(endpoint.acceptsWrites());
        assertNotNull(endpoint.connect(address(2004)).getCause());
        endpoint.close();
        endpoint.close();
        assertEquals(Arrays.asList("tcp", "tcp", "engine"), endpoint.cleanup);
        assertTrue(pending.isDone());
        assertTrue(pending.getCause() instanceof IllegalStateException);
        assertTrue(endpoint.getListenList().isEmpty());
        assertEquals(1, endpoint.destroyed);
    }

    private static void assertFailure(Future<RsfListen> result, Throwable cause) throws Exception {
        try {
            result.get(2, TimeUnit.SECONDS);
            fail("Expected bind failure");
        } catch (ExecutionException expected) {
            assertSame(cause, expected.getCause());
        }
    }

    private static InterAddress address(int port) {
        return new InterAddress("rsf", "127.0.0.1", port, "default");
    }

    private static class Endpoint extends AbstractConnector {
        final List<String>                 cleanup        = new ArrayList<>();
        final List<InterAddress>           requested      = new ArrayList<>();
        final List<String>                 requestedTypes = new ArrayList<>();
        final List<BasicFuture<RsfListen>> operations     = new ArrayList<>();
        IOException setupFailure;
        int         destroyed;

        private final TestConnectorManager runtime;

        Endpoint() {
            this(subscribedManager(ConnectorResourcesTest.sharedContext(), RECEIVER));
        }

        private Endpoint(TestConnectorManager runtime) {
            super(new ConnectorConfig("test", address(1000), Collections.singletonMap("listenType", "tcp")), runtime);
            this.runtime = runtime;
            runtime.prepare(this.config(), (connectorConfig, connectorManager) -> this);
        }

        void initializeThroughManager() {
            // The fake provider never opens an outgoing socket; connect only exercises SPI initialization.
            assertTrue(this.runtime.connect(this.config().withAddress(address(1000))).getCause() instanceof UnsupportedOperationException);
        }

        protected void initialize() {
        }

        protected Future<RsfListen> listen(String listenType, InterAddress address, ReceivedListener listener) throws IOException {
            assertFalse(Thread.holdsLock(this));
            if (this.setupFailure != null) {
                throw this.setupFailure;
            }
            this.requested.add(address);
            this.requestedTypes.add(listenType);
            BasicFuture<RsfListen> operation = new BasicFuture<>();
            this.operations.add(operation);
            return operation;
        }

        TestListen succeed(int operation, String type) {
            assertEquals(this.requestedTypes.get(operation), type);
            TestListen listen = new TestListen(this, type, this.requested.get(operation));
            this.operations.get(operation).completed(listen);
            return listen;
        }

        protected Future<RsfChannel> openSession(String listenType, InterAddress target, ReceivedListener listener) {
            BasicFuture<RsfChannel> result = new BasicFuture<>();
            result.failed(new UnsupportedOperationException());
            return result;
        }

        protected void doClose() {
            this.destroyed++;
            this.cleanup.add("engine");
            if (this.runtime.isInitialized()) {
                this.runtime.close();
            }
        }
    }

    private static class TestListen implements RsfListen {
        final Endpoint     owner;
        final String       type;
        final InterAddress address;
        int              closes;
        RuntimeException closeFailure;

        TestListen(Endpoint owner, String type, InterAddress address) {
            this.owner = owner;
            this.type = type;
            this.address = address;
        }

        public String getType() {
            return this.type;
        }

        public InterAddress getBindAddress() {
            return this.address;
        }

        public boolean isActive() {
            return this.closes == 0;
        }

        public void close() {
            assertFalse(Thread.holdsLock(this.owner));
            if (this.closes == 0) {
                this.closes++;
                this.owner.cleanup.add(this.type);
                if (this.closeFailure != null) {
                    throw this.closeFailure;
                }
            }
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
