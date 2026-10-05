/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.rsf.*;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ConnectorManager;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.domain.*;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class RsfCallerLifecycleTest {
    public interface Echo {
        String echo(String value);

        RsfResult notify(String value);
    }

    @Test
    public void requestOptionFailureCompletesFutureBeforeFiltersOrSending() throws Exception {
        try (Host host = new Host(1)) {
            IllegalStateException cause = new IllegalStateException("request options unavailable");
            host.requestOptionFailure = cause;
            AtomicInteger filters = new AtomicInteger();
            host.filter = (request, response, chain) -> {
                filters.incrementAndGet();
                chain.doFilter(request, response);
            };
            RsfFuture failed = host.invoke();
            assertSame(cause, assertFailure(failed, IllegalStateException.class));
            assertEquals(0, filters.get());
            assertTrue(host.sent.isEmpty());
            assertTrue(host.timers.isEmpty());
            host.requestOptionFailure = null;
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void messageFailurePreservesStatusAndDescriptionAndReleasesCapacity() throws Exception {
        try (Host host = new Host(1)) {
            host.service.setMessage(true);
            for (short status : new short[] { ProtocolStatus.Forbidden, ProtocolStatus.OK }) {
                RsfFuture call = host.invoke();
                ResponsePayload response = new ResponsePayload();
                response.setRequestID(call.getRequest().getRequestID());
                response.setStatus(status);
                response.addOption("message", "message was not accepted");
                host.manager.onResponse(host.channel, response.getRequestID(), response);
                RsfException failure = (RsfException) assertFailure(call, RsfException.class);
                assertEquals(status, failure.getStatus());
                assertTrue(failure.getMessage().contains("message was not accepted"));
                assertOneSlotAvailable(host);
            }
        }
    }

    @Test
    public void successfulResponsePreservesOptionsAndNullResult() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture call = host.invoke();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.getRequest().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.addOption("trace", "server-trace");
            host.manager.onResponse(host.channel, response.getRequestID(), response);
            RsfResponse result = call.get(2, TimeUnit.SECONDS);
            assertNull(result.getData());
            assertEquals(ProtocolStatus.OK, result.getStatus());
            assertEquals("server-trace", result.getOption("trace"));
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void duplicateSubmissionDoesNotRemoveOriginalPendingRequest() throws Exception {
        try (Host host = new Host(2)) {
            RsfRequestObject request = host.request(host.peer, new Object[] { "value" });
            RsfFuture original = host.send(request);
            RsfFuture duplicate = host.send(request);
            assertTrue(duplicate.getCause() instanceof IllegalStateException);
            assertSame(original, host.caller.getRequest(request.getRequestID()));
            assertEquals(1, host.sent.size());
            RsfFuture other = host.invoke();
            assertFalse(other.isDone());
            assertRejected(host.invoke());
            host.respond(original, ProtocolStatus.OK);
            assertEquals("done", original.getData());
            other.cancel();
        }
    }

    @Test
    public void repeatedCancellationCannotRemoveAnotherFutureUsingSameRequest() throws Exception {
        try (Host host = new Host(1)) {
            RsfRequestObject request = host.request(host.peer, new Object[] { "value" });
            RsfFuture cancelled = host.send(request);
            cancelled.cancel();
            RsfFuture current = host.send(request);
            cancelled.cancel();
            assertSame(current, host.caller.getRequest(request.getRequestID()));
            assertRejected(host.invoke());
            host.respond(current, ProtocolStatus.OK);
            assertEquals("done", current.getData());
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void messageAcceptReturnsReceiptForRsfResultMethod() throws Exception {
        try (Host host = new Host(1)) {
            RsfRequestObject request = new RsfRequestObject(1000000L, true, host.caller.getContext(), host.service, Echo.class.getMethod("notify", String.class), new Object[] { "value" });
            host.configureRequest(request, host.peer);
            request.setMessage(true);
            RsfFuture future = host.send(request);
            host.respond(future, ProtocolStatus.Accept);
            RsfResult result = (RsfResult) future.getData();
            assertTrue(result.isSuccess());
            assertEquals(request.getRequestID(), result.getMessageID());
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void messageDeliveryChecksEnvelopeIdAndSupportsNullFilterLists() throws Exception {
        try (Host host = new Host(1)) {
            host.noFilters = true;
            RsfFuture call = host.invoke();
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.getRequest().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("reply");
            host.manager.onResponse(host.channel, response.getRequestID() + 1, response);
            assertFalse(call.isDone());
            host.manager.onResponse(host.channel, response.getRequestID(), response);
            assertEquals("reply", call.getData());
            RsfFuture failed = host.invoke();
            IllegalStateException cause = new IllegalStateException("transport failed");
            host.manager.onFailure(host.channel, failed.getRequest().getRequestID(), new ThrowPayload(cause));
            assertSame(cause, assertFailure(failed, IllegalStateException.class));
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void closeContinuesFailingRequestsWhenCompletionListenerThrows() throws Exception {
        try (Host host = new Host(2)) {
            RsfFuture first = host.invoke();
            AtomicInteger failures = new AtomicInteger();
            first.onFailed(done -> {
                failures.incrementAndGet();
                throw new IllegalArgumentException("broken callback");
            });
            RsfFuture second = host.invoke();
            host.caller.close();
            assertEquals(1, failures.get());
            assertTrue(first.isDone());
            assertFailure(second, IllegalStateException.class);
            assertNull(host.caller.getRequest(first.getRequest().getRequestID()));
            assertNull(host.caller.getRequest(second.getRequest().getRequestID()));
        }
    }

    @Test
    public void reservesSlotBeforeMappingAndRegistration() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (Host host = new Host(1)) {
            RsfRequestObject request = host.blockedRequest(mapping, resume);
            Future<RsfFuture> first = executor.submit(() -> host.send(request));
            assertTrue(mapping.await(2, TimeUnit.SECONDS));
            assertNull(host.caller.getRequest(request.getRequestID()));
            assertRejected(host.invoke());
            resume.countDown();
            RsfFuture admitted = first.get(2, TimeUnit.SECONDS);
            assertFalse(admitted.isDone());
            assertEquals(1, host.sent.size());
            admitted.cancel();
            assertOneSlotAvailable(host);
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void concurrentAdmissionNeverExceedsMaximum() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(24);
        CountDownLatch start = new CountDownLatch(1);
        try (Host host = new Host(3)) {
            List<Future<RsfFuture>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                calls.add(executor.submit(() -> {
                    start.await();
                    return host.invoke();
                }));
            }
            start.countDown();
            int admitted = 0;
            for (Future<RsfFuture> call : calls) {
                RsfFuture future = call.get(3, TimeUnit.SECONDS);
                if (future.isDone()) {
                    assertRejected(future);
                } else {
                    admitted++;
                }
            }
            assertEquals(3, admitted);
            assertEquals(3, host.sent.size());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void ordinaryAcceptAndSendCompletionKeepSlotUntilFinalResponse() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture future = host.invoke();
            AtomicInteger completions = new AtomicInteger();
            future.onCompleted(done -> completions.incrementAndGet());
            host.callbacks.remove().completed(host.channel);
            host.respond(future, ProtocolStatus.Accept);
            assertFalse(future.isDone());
            assertRejected(host.invoke());
            host.respond(future, ProtocolStatus.OK);
            assertEquals("done", future.getData());
            host.respond(future, ProtocolStatus.OK);
            assertEquals(1, completions.get());
            assertEquals("done", future.getData());
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void messageAcceptAndRemoteErrorReleaseSlots() throws Exception {
        try (Host host = new Host(1)) {
            host.service.setMessage(true);
            RsfFuture message = host.invoke();
            host.respond(message, ProtocolStatus.Accept);
            assertTrue(message.isDone());
            host.service.setMessage(false);
            RsfFuture rejected = host.invoke();
            host.respond(rejected, ProtocolStatus.Forbidden);
            assertFailure(rejected, RsfException.class);
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void timeoutAndLateResponseReleaseExactlyOneSlot() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture future = host.invoke();
            Runnable timer = host.timers.remove();
            timer.run();
            timer.run();
            Throwable timeout = assertFailure(future, RsfTimeoutException.class);
            host.respond(future, ProtocolStatus.OK);
            assertSame(timeout, assertFailure(future, RsfTimeoutException.class));
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void cancellationNotifiesOnceAndReleasesSlot() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture future = host.invoke();
            AtomicInteger cancellations = new AtomicInteger();
            AtomicInteger terminal = new AtomicInteger();
            future.onCancel(done -> cancellations.incrementAndGet());
            future.onFinal(done -> terminal.incrementAndGet());
            assertTrue(future.cancel());
            assertFalse(future.cancel());
            assertEquals(1, cancellations.get());
            assertEquals(1, terminal.get());
            assertNull(host.caller.getRequest(future.getRequest().getRequestID()));
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void throwingCancellationListenerStillReleasesSlot() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture future = host.invoke();
            AtomicBoolean terminal = new AtomicBoolean();
            future.onCancel(done -> {
                throw new IllegalArgumentException("callback");
            });
            future.onFinal(done -> terminal.set(true));
            assertTrue(future.cancel());
            assertTrue(future.isCancelled());
            assertTrue(terminal.get());
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void throwingCompletionListenerDoesNotLeakSlot() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture future = host.invoke();
            AtomicBoolean terminal = new AtomicBoolean();
            future.onCompleted(done -> {
                throw new IllegalArgumentException("callback");
            });
            future.onFinal(done -> terminal.set(true));
            host.respond(future, ProtocolStatus.OK);
            assertTrue(terminal.get());
            assertEquals("done", future.getData());
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void immediateMappingFailureNotifiesLateListenerAndAllowsRetry() throws Exception {
        try (Host host = new Host(1)) {
            BlockingQueue<RsfFuture> retried = new LinkedBlockingQueue<>();
            RsfFuture failed = host.send(host.request(host.peer, new Object[0]));
            assertFailure(failed, ArrayIndexOutOfBoundsException.class);
            failed.onFailed(done -> retried.add(host.invoke()));
            RsfFuture retry = retried.remove();
            assertFalse(retry.isDone());
            assertRejected(host.invoke());
            retry.cancel();
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void cancellingTimeoutDoesNotBlockAnotherInvocation() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch cancelling = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Host host = new Host(1)) {
            host.timerCancellation = () -> {
                cancelling.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return true;
            };
            RsfFuture first = host.invoke();
            host.timerCancellation = () -> true;
            Future<?> responding = executor.submit(() -> host.respond(first, ProtocolStatus.OK));
            RsfFuture next;
            try {
                assertTrue(cancelling.await(2, TimeUnit.SECONDS));
                next = executor.submit(host::invoke).get(2, TimeUnit.SECONDS);
                assertFalse(next.isDone());
                assertSame(next, host.caller.getRequest(next.getRequest().getRequestID()));
                assertRejected(host.invoke());
            } finally {
                release.countDown();
            }
            responding.get(2, TimeUnit.SECONDS);
            assertEquals("done", first.getData());
            next.cancel();
            assertOneSlotAvailable(host);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    public void competingTerminalEventsDoNotInflateCapacity() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try (Host host = new Host(1)) {
            for (int round = 0; round < 20; round++) {
                host.timers.clear();
                RsfFuture future = host.invoke();
                Runnable timer = host.timers.remove();
                CountDownLatch start = new CountDownLatch(1);
                List<Callable<Void>> actions = new ArrayList<>();
                actions.add(() -> {
                    start.await();
                    future.cancel();
                    return null;
                });
                actions.add(() -> {
                    start.await();
                    timer.run();
                    return null;
                });
                actions.add(() -> {
                    start.await();
                    host.respond(future, ProtocolStatus.OK);
                    return null;
                });
                actions.add(() -> {
                    start.await();
                    host.manager.onFailure(host.channel, future.getRequest().getRequestID(), new ThrowPayload(new IllegalStateException("send failed")));
                    return null;
                });
                List<Future<Void>> results = new ArrayList<>();
                for (Callable<Void> action : actions) {
                    results.add(executor.submit(action));
                }
                start.countDown();
                for (Future<Void> result : results) {
                    result.get(2, TimeUnit.SECONDS);
                }
                assertTrue(future.isDone());
                assertOneSlotAvailable(host);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void mappingAndMissingAddressFailuresDoNotLeakSlots() throws Exception {
        try (Host host = new Host(1)) {
            assertFailure(host.send(host.request(null, new Object[] { "value" })), RsfException.class);
            assertFailure(host.send(host.request(host.peer, new Object[0])), ArrayIndexOutOfBoundsException.class);
            assertTrue(host.sent.isEmpty());
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void timerRejectionAndImmediateTimeoutDoNotSendOrLeak() throws Exception {
        try (Host host = new Host(1)) {
            host.rejectTimer = true;
            assertFailure(host.invoke(), RejectedExecutionException.class);
            host.rejectTimer = false;
            host.immediateTimeout = true;
            assertFailure(host.invoke(), RsfTimeoutException.class);
            host.immediateTimeout = false;
            assertTrue(host.sent.isEmpty());
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void synchronousAndAsynchronousSendFailuresReleaseSlots() throws Exception {
        try (Host host = new Host(1)) {
            host.failSend = true;
            assertFailure(host.invoke(), IllegalStateException.class);
            host.failSend = false;
            RsfFuture future = host.invoke();
            BasicFuture<RsfChannel> callback = host.callbacks.remove();
            callback.failed(new IllegalArgumentException("async failure"));
            callback.failed(new IllegalArgumentException("duplicate"));
            assertFailure(future, IllegalArgumentException.class);
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void sendFutureCancellationFailsRpcAndReleasesItsSlot() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture request = host.invoke();
            assertTrue(host.callbacks.remove().cancel());
            assertFailure(request, CancellationException.class);
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void filterFailureAfterSendingReleasesSlotAndLocalResponseNeedsNone() throws Exception {
        try (Host host = new Host(1)) {
            host.filter = (request, response, chain) -> {
                chain.doFilter(request, response);
                throw new IllegalStateException("filter failed after send");
            };
            assertFailure(host.invoke(), IllegalStateException.class);
            host.filter = null;
            RsfFuture pending = host.invoke();
            host.filter = (request, response, chain) -> {
                response.sendData("local");
                chain.doFilter(request, response);
            };
            assertEquals("local", host.invoke().getData());
            host.filter = null;
            assertRejected(host.invoke());
            pending.cancel();
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void waitingSenderContinuesAsSoonAsSlotIsReleased() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Host host = new Host(1)) {
            RsfFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;
            Future<RsfFuture> waiting = executor.submit(host::invoke);
            assertTrue(host.limitReached.await(2, TimeUnit.SECONDS));
            first.cancel();
            RsfFuture admitted = waiting.get(700, TimeUnit.MILLISECONDS);
            assertFalse(admitted.isDone());
            host.policy = SendLimitPolicy.Reject;
            assertRejected(host.invoke());
            admitted.cancel();
            assertOneSlotAvailable(host);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void waitingSenderTimesOutWithoutTakingSlot() throws Exception {
        try (Host host = new Host(1)) {
            RsfFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;
            long start = System.nanoTime();
            assertRejected(host.invoke());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) >= 900);
            assertEquals(1, host.sent.size());
            host.policy = SendLimitPolicy.Reject;
            first.cancel();
            assertOneSlotAvailable(host);
        }
    }

    @Test
    public void interruptedWaitPreservesInterruptAndDoesNotLeak() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicBoolean interrupted = new AtomicBoolean();
        try (Host host = new Host(1)) {
            RsfFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;
            BlockingQueue<Thread> worker = new LinkedBlockingQueue<>();
            Future<RsfFuture> waiting = executor.submit(() -> {
                worker.add(Thread.currentThread());
                RsfFuture future = host.invoke();
                interrupted.set(Thread.currentThread().isInterrupted());
                return future;
            });
            assertTrue(host.limitReached.await(2, TimeUnit.SECONDS));
            worker.remove().interrupt();
            assertFailure(waiting.get(2, TimeUnit.SECONDS), InterruptedException.class);
            assertTrue(interrupted.get());
            assertEquals(1, host.sent.size());
            host.policy = SendLimitPolicy.Reject;
            first.cancel();
            assertOneSlotAvailable(host);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shutdownFailsPendingAndWaitingRequestsWithoutSendingAgain() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Host host = new Host(1)) {
            RsfFuture first = host.invoke();
            host.policy = SendLimitPolicy.WaitSecond;
            Future<RsfFuture> waiting = executor.submit(host::invoke);
            assertTrue(host.limitReached.await(2, TimeUnit.SECONDS));
            host.close();
            host.close();
            assertFailure(first, IllegalStateException.class);
            assertFailure(waiting.get(2, TimeUnit.SECONDS), IllegalStateException.class);
            assertFailure(host.invoke(), IllegalStateException.class);
            assertEquals(1, host.sent.size());
            assertNull(host.caller.getRequest(first.getRequest().getRequestID()));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shutdownDuringMappingPreventsRegistrationAndSend() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        try (Host host = new Host(1)) {
            RsfRequestObject request = host.blockedRequest(mapping, resume);
            Future<RsfFuture> call = executor.submit(() -> host.send(request));
            assertTrue(mapping.await(2, TimeUnit.SECONDS));
            host.close();
            resume.countDown();
            assertFailure(call.get(2, TimeUnit.SECONDS), IllegalStateException.class);
            assertTrue(host.sent.isEmpty());
            assertNull(host.caller.getRequest(request.getRequestID()));
        } finally {
            resume.countDown();
            executor.shutdownNow();
        }
    }

    private static void assertOneSlotAvailable(Host host) throws Exception {
        RsfFuture future = host.invoke();
        assertFalse("Slot must be reusable", future.isDone());
        assertRejected(host.invoke());
        assertTrue(future.cancel());
    }

    private static void assertRejected(RsfFuture future) throws Exception {
        assertEquals(ProtocolStatus.SendLimitPolicy, ((RsfException) assertFailure(future, RsfException.class)).getStatus());
    }

    private static Throwable assertFailure(RsfFuture future, Class<? extends Throwable> type) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
            throw new AssertionError("Expected " + type.getSimpleName());
        } catch (ExecutionException expected) {
            assertTrue("Unexpected failure: " + expected.getCause(), type.isInstance(expected.getCause()));
            return expected.getCause();
        }
    }

    private static class Host implements AutoCloseable {
        private static final AtomicLong                             REQUEST_IDS  = new AtomicLong();
        final                ServiceDomain<Echo>                    service      = new ServiceDomain<>(Echo.class);
        final                InterAddress                           peer         = new InterAddress("rsf://127.0.0.1:2181/default");
        final                Method                                 method       = Echo.class.getMethod("echo", String.class);
        final                BlockingQueue<RequestPayload>          sent         = new LinkedBlockingQueue<>();
        final                BlockingQueue<Runnable>                timers       = new LinkedBlockingQueue<>();
        final                BlockingQueue<BasicFuture<RsfChannel>> callbacks    = new LinkedBlockingQueue<>();
        final                CountDownLatch                         limitReached = new CountDownLatch(1);
        final                ConnectorManager                       manager;
        final                RsfChannel                             channel;
        final                RsfCaller                              caller;
        volatile             SendLimitPolicy                        policy       = SendLimitPolicy.Reject;
        volatile             RsfFilter                              filter;
        boolean rejectTimer, immediateTimeout, failSend, noFilters;
        RuntimeException requestOptionFailure;
        Cancellable      timerCancellation = () -> true;

        @SuppressWarnings("unchecked")
        Host(int maximum) throws Exception {
            this.service.setBindName("Echo");
            this.service.setSerializeType("Java");
            this.service.setClientTimeout(6000);
            RsfSettings settings = proxy(RsfSettings.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "getConnectorConfigs":
                        return Collections.emptySet();
                    case "getQueueMaxSize":
                        return 16;
                    case "getQueueMinPoolSize":
                    case "getQueueMaxPoolSize":
                        return 1;
                    case "getQueueKeepAliveTime":
                        return 1000L;
                    case "getMaximumRequest":
                        return maximum;
                    case "getSendLimitPolicy":
                        this.limitReached.countDown();
                        return this.policy;
                    case "getRequestOptions":
                        if (this.requestOptionFailure != null) {
                            throw this.requestOptionFailure;
                        }
                        return new OptionInfo();
                    default:
                        throw new AssertionError("Unexpected setting: " + m);
                }
            });
            RsfContext context = proxy(RsfContext.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "getSettings":
                        return settings;
                    case "getClassLoader":
                        return getClass().getClassLoader();
                    default:
                        throw new AssertionError("Unexpected context access: " + m);
                }
            });
            this.channel = proxy(RsfChannel.class, (p, m, args) -> {
                if ("sendData".equals(m.getName())) {
                    if (this.failSend) {
                        throw new IllegalStateException("send failed");
                    }
                    this.sent.add((RequestPayload) args[0]);
                    BasicFuture<RsfChannel> writing = new BasicFuture<>();
                    this.callbacks.add(writing);
                    return writing;
                }
                throw new AssertionError("Unexpected channel access: " + m);
            });
            this.manager = new ConnectorManager(context) {
                @Override
                public BasicFuture<RsfChannel> connect(InterAddress address) {
                    return new BasicFuture<>(Host.this.channel);
                }

                @Override
                public Cancellable schedule(Runnable task, long delay) {
                    if (Host.this.rejectTimer) {
                        throw new RejectedExecutionException("timer closed");
                    }
                    Host.this.timers.add(task);
                    if (Host.this.immediateTimeout) {
                        task.run();
                    }
                    return Host.this.timerCancellation;
                }
            };
            this.manager.init();
            this.caller = new RsfCaller(this.manager, id -> this.noFilters ? null : this.filter == null ? new Supplier[0] : new Supplier[] { (Supplier<RsfFilter>) () -> this.filter });
        }

        RsfRequestObject request(InterAddress target, Object[] arguments) {
            RsfRequestObject request = new RsfRequestObject(REQUEST_IDS.incrementAndGet(), true, this.caller.getContext(), this.service, this.method, arguments);
            return this.configureRequest(request, target);
        }

        RsfRequestObject blockedRequest(CountDownLatch mapping, CountDownLatch resume) {
            RsfRequestObject request = new RsfRequestObject(REQUEST_IDS.incrementAndGet(), true, this.caller.getContext(), this.service, this.method, new Object[] { "value" }) {
                @Override
                public Object[] getParameterObject() {
                    mapping.countDown();
                    try {
                        assertTrue(resume.await(3, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    return super.getParameterObject();
                }
            };
            return this.configureRequest(request, this.peer);
        }

        private RsfRequestObject configureRequest(RsfRequestObject request, InterAddress target) {
            request.setPeerAddress(target);
            request.setSerializeType(this.service.getSerializeType());
            request.setMessage(this.service.isMessage());
            request.setTimeout(this.service.getClientTimeout());
            return request;
        }

        RsfFuture invoke() {
            return this.send(this.request(this.peer, new Object[] { "value" }));
        }

        RsfFuture send(RsfRequestObject request) {
            return this.caller.invoke(request);
        }

        void respond(RsfFuture future, short status) {
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(future.getRequest().getRequestID());
            response.setStatus(status);
            response.setReturnData("done");
            this.manager.onResponse(this.channel, response.getRequestID(), response);
        }

        public void close() {
            try {
                this.caller.close();
            } finally {
                this.manager.close();
            }
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
