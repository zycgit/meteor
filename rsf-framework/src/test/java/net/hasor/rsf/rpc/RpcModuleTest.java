/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.CancelFutureCallback;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.rsf.*;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.provider.AddressProvider;
import net.hasor.rsf.address.provider.InstanceAddressProvider;
import net.hasor.rsf.connector.ConnectorManager;
import net.hasor.rsf.connector.ConnectorSubscriber;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.domain.*;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.domain.payload.ThrowPayload;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs client/server calls with only public host contracts and an in-memory transport. */
public class RpcModuleTest {
    public interface Echo {
        String echo(String text);

        String fail(String text);

        String getTarget();

        String setTarget(String text);

        String toString(String text);
    }

    public static class EchoImpl implements Echo {
        public String echo(String text) {
            return text;
        }

        public String fail(String text) {
            throw new IllegalArgumentException(text);
        }

        public String getTarget() {
            return "business target";
        }

        public String setTarget(String text) {
            return text;
        }

        public String toString(String text) {
            return text;
        }
    }

    public interface EchoView {
        String echo(String text);
    }

    @Test
    public void synchronousClientUsesDefaultTimeoutForNonPositiveServiceTimeout() throws Exception {
        try (Host host = new Host(true)) {
            for (int timeout : new int[] { 0, -1 }) {
                host.service.setClientTimeout(timeout);
                assertEquals("default timeout", host.client.syncInvoke(host.service, "echo", new Class<?>[] { String.class }, new Object[] { "default timeout" }));
            }
        }
    }

    @Test
    public void proxyObjectMethodsStayLocalAndBusinessNamesRemainCallable() throws Exception {
        try (Host host = new Host(true)) {
            Echo first = host.client.getRemote(host.service);
            Echo second = host.client.getRemote(host.service);
            assertEquals(first, first);
            assertNotEquals(first, second);
            assertNotEquals(null, first);
            assertEquals(System.identityHashCode(first), first.hashCode());
            assertTrue(first.toString().contains(host.service.getBindID()));
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
            assertArrayEquals(new Class<?>[] { Echo.class }, first.getClass().getInterfaces());

            assertEquals("business target", first.getTarget());
            assertEquals("new value", first.setTarget("new value"));
            assertEquals("overloaded", first.toString("overloaded"));
            assertEquals(3, host.localFilters.get());
            assertEquals(3, host.remoteFilters.get());
        }
    }

    @Test
    public void sameServiceCanBeWrappedAsDifferentInterfaces() throws Exception {
        try (Host host = new Host(true)) {
            Echo first = host.client.getRemoteByID(host.service.getBindID());
            EchoView second = host.client.wrapperByID(host.service.getBindID(), EchoView.class);
            assertEquals("first", first.echo("first"));
            assertEquals("second", second.echo("second"));
            assertArrayEquals(new Class<?>[] { EchoView.class }, second.getClass().getInterfaces());
            assertEquals("lookup", host.client.<Echo>getRemote("RSF", "Echo", "1.0.0").echo("lookup"));
            assertEquals("type", host.client.wrapper(Echo.class).echo("type"));
            assertEquals("alias", host.client.wrapper("RSF", "Echo", "1.0.0", EchoView.class).echo("alias"));
            try {
                host.client.wrapper(host.service, EchoImpl.class);
                fail("Concrete types cannot be proxied");
            } catch (UnsupportedOperationException expected) {
                assertTrue(expected.getMessage().contains("interface"));
            }
        }
    }

    @Test
    public void missingServiceLookupFailsBeforeCreatingOrSendingRequests() throws Exception {
        try (Host host = new Host(true)) {
            host.serviceAvailable = false;
            Runnable[] lookups = { () -> host.client.getRemoteByID("missing"), () -> host.client.getRemote("RSF", "missing", "1.0.0"), () -> host.client.wrapperByID("missing", Echo.class), () -> host.client.wrapper(Echo.class) };
            for (Runnable lookup : lookups) {
                try {
                    lookup.run();
                    fail("Unregistered services must be rejected");
                } catch (IllegalStateException expected) {
                    assertTrue(expected.getMessage().contains("undefined"));
                }
            }
            assertNull(host.client.wrapper("RSF", "missing", "1.0.0", Echo.class));
            assertTrue(host.sent.isEmpty());
            assertTrue(host.targets.isEmpty());
        }
    }

    @Test
    public void clientsKeepIndependentProvidersAndDynamicRoutingRunsPerCall() throws Exception {
        try (Host host = new Host(true)) {
            InterAddress first = new InterAddress("rsf://localhost:2181/default");
            InterAddress second = new InterAddress("rsf://localhost:2182/default");
            AtomicReference<InterAddress> selected = new AtomicReference<>(first);
            AtomicInteger selections = new AtomicInteger();
            AddressProvider dynamic = new AddressProvider() {
                public boolean isDistributed() {
                    return true;
                }

                public InterAddress get(String serviceID, String method, Object[] arguments) {
                    assertEquals(host.service.getBindID(), serviceID);
                    assertEquals("echo", method);
                    assertEquals(1, arguments.length);
                    selections.incrementAndGet();
                    return selected.get();
                }
            };
            Echo routed = new RsfClientImpl(host.caller, dynamic).getRemote(host.service);
            Echo fixed = new RsfClientImpl(host.caller, new InstanceAddressProvider(first)).getRemote(host.service);
            assertEquals("one", routed.echo("one"));
            selected.set(second);
            assertEquals("two", routed.echo("two"));
            assertEquals("fixed", fixed.echo("fixed"));
            assertSame(first, host.targets.remove());
            assertSame(second, host.targets.remove());
            assertSame(first, host.targets.remove());
            assertEquals(2, selections.get());
            assertTrue(host.callerManager.isInitialized());
        }
    }

    @Test
    public void callbackDataAndResponsePreserveResultsAndFailures() throws Exception {
        try (Host host = new Host(true)) {
            Callback<Object> data = new Callback<>();
            host.client.callbackInvoke(host.service, "echo", new Class<?>[] { String.class }, new Object[] { "data" }, data);
            assertEquals("data", data.result.get(2, TimeUnit.SECONDS));
            Callback<RsfResponse> response = new Callback<>();
            host.client.callbackRequest(host.service, "echo", new Class<?>[] { String.class }, new Object[] { null }, response);
            assertNull(response.result.get(2, TimeUnit.SECONDS).getData());
            Callback<Object> failed = new Callback<>();
            host.client.callbackInvoke(host.service, "fail", new Class<?>[] { String.class }, new Object[] { "bad" }, failed);
            try {
                failed.result.get(2, TimeUnit.SECONDS);
                fail("Business failure must reach callback");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof RsfException);
            }
            assertEquals(1, data.notifications.get());
            assertEquals(1, response.notifications.get());
            assertEquals(1, failed.notifications.get());
        }
    }

    @Test
    public void callbackCancellationUsesTheSameSemanticsForDataAndResponse() throws Exception {
        try (Host host = new Host(false)) {
            Callback<Object> data = new Callback<>();
            host.client.callbackInvoke(host.service, "echo", new Class<?>[] { String.class }, new Object[] { "data" }, data);
            RequestPayload request = host.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            host.caller.getRequest(request.getRequestID()).cancel();
            assertTrue(data.result.isCancelled());
            Callback<RsfResponse> response = new Callback<>();
            host.client.callbackRequest(host.service, "echo", new Class<?>[] { String.class }, new Object[] { "response" }, response);
            request = host.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            host.caller.getRequest(request.getRequestID()).cancel();
            assertTrue(response.result.isCancelled());
            assertEquals(1, data.notifications.get());
            assertEquals(1, response.notifications.get());
        }
    }

    @Test
    public void nullCallbacksStillSubmitCallsAndClosedCallerRejectsExistingClients() throws Exception {
        try (Host host = new Host(false)) {
            host.client.callbackInvoke(host.service, "echo", new Class<?>[] { String.class }, new Object[] { "data" }, null);
            host.client.callbackRequest(host.service, "echo", new Class<?>[] { String.class }, new Object[] { "response" }, null);
            assertEquals(2, host.sent.size());
            host.caller.close();
            RsfFuture rejected = host.invoke();
            assertTrue(rejected.getCause() instanceof IllegalStateException);
            assertEquals(2, host.sent.size());
        }
    }

    private static class Callback<T> implements CancelFutureCallback<T> {
        private final BasicFuture<T> result        = new BasicFuture<>();
        private final AtomicInteger  notifications = new AtomicInteger();

        public void completed(T value) {
            this.notifications.incrementAndGet();
            this.result.completed(value);
        }

        public void failed(Throwable failure) {
            this.notifications.incrementAndGet();
            this.result.failed(failure);
        }

        public void cancelled() {
            this.notifications.incrementAndGet();
            this.result.cancel();
        }
    }

    @Test
    public void clientAndServerWorkWithoutRuntimeAssemblyAndUseHostFilters() throws Exception {
        try (Host host = new Host(true)) {
            Echo proxy = host.client.getRemote(host.service);
            assertEquals("hello", proxy.echo("hello"));
            assertNull(proxy.echo(null));
            assertEquals(2, host.localFilters.get());
            assertEquals(2, host.remoteFilters.get());
            try {
                host.client.syncInvoke(host.service, "fail", new Class<?>[] { String.class }, new Object[] { "failure" });
                fail("Service failure must reach the client");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof RsfException);
            }
            assertNull(getClass().getClassLoader().getResource("net/hasor/rsf/protocol/hprose/HproseProtocol.class"));
        }
    }

    @Test
    public void acceptKeepsInvocationPendingUntilFinalResponse() throws Exception {
        try (Host host = new Host(false)) {
            RsfFuture future = host.invoke();
            RequestPayload request = host.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(request.getRequestID());
            response.setStatus(ProtocolStatus.Accept);
            host.callerManager.onResponse(host.outgoing, response.getRequestID(), response);
            assertFalse(future.isDone());
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("done");
            host.callerManager.onResponse(host.outgoing, response.getRequestID(), response);
            assertEquals("done", future.get(2, TimeUnit.SECONDS).getData());
            assertNull(host.caller.getRequest(request.getRequestID()));
            response.setReturnData("duplicate");
            host.callerManager.onResponse(host.outgoing, response.getRequestID(), response);
            assertEquals("done", future.getData());
        }
    }

    @Test
    public void connectorTimerAndShutdownCompletePendingCalls() throws Exception {
        try (Host host = new Host(false)) {
            RsfFuture timed = host.invoke();
            Runnable timeout = host.timers.poll(2, TimeUnit.SECONDS);
            assertNotNull(timeout);
            timeout.run();
            try {
                timed.get(2, TimeUnit.SECONDS);
                fail("Expected timeout");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof RsfTimeoutException);
            }
            RsfFuture pending = host.invoke();
            host.caller.close();
            try {
                pending.get(2, TimeUnit.SECONDS);
                fail("Expected shutdown failure");
            } catch (ExecutionException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        }
    }

    @Test
    public void failurePayloadCompletesPendingCallWithOriginalCause() throws Exception {
        try (Host host = new Host(false)) {
            RsfFuture pending = host.invoke();
            RequestPayload request = host.sent.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            IllegalStateException failure = new IllegalStateException("disconnected", new IllegalArgumentException("cause"));
            host.callerManager.onFailure(host.outgoing, request.getRequestID(), new ThrowPayload(failure));
            try {
                pending.get(2, TimeUnit.SECONDS);
                fail("Failure payload must complete the pending call");
            } catch (ExecutionException expected) {
                assertSame(failure, expected.getCause());
            }
            assertNull(host.caller.getRequest(request.getRequestID()));
        }
    }

    @Test
    public void callerRequiresInitializedManager() {
        RsfContext context = proxy(RsfContext.class, (object, method, arguments) -> {
            throw new AssertionError("Uninitialized manager must be rejected before accessing the context");
        });
        try (ConnectorManager manager = new ConnectorManager(context, new RsfCallerTest.MemoryFactory())) {
            try {
                new RsfCaller(manager, id -> null);
                fail("Expected uninitialized manager rejection");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("Initialize"));
            }
        }
    }

    @Test
    public void closingCallerClosesItsManagerAndRejectsInboundCalls() throws Exception {
        try (Host host = new Host(true)) {
            assertEquals("value", host.invoke().getData(2, TimeUnit.SECONDS));
            RequestPayload request = host.sent.remove();
            host.server.close();
            assertFalse(host.serverManager.isInitialized());
            RsfChannel source = proxy(RsfChannel.class, (object, method, arguments) -> {
                throw new AssertionError("Closed RPC must reject the message before inspecting the channel");
            });
            host.serverManager.onRequest(source, request.getRequestID(), request);
            assertEquals(1, host.remoteFilters.get());
            host.serverManager.close();
            assertFalse(host.serverManager.isInitialized());
        }
    }

    @Test
    public void subscriptionFailureDoesNotCloseHostManager() throws Exception {
        try (Host host = new Host(false); ConnectorManager manager = new ConnectorManager(host.caller.getContext(), new RsfCallerTest.MemoryFactory()) {
            @Override
            public void subscribe(ConnectorSubscriber subscriber) {
                throw new IllegalStateException("subscription failed");
            }
        }) {
            manager.init();
            try {
                new RsfCaller(manager, id -> null);
                fail("Expected subscription failure");
            } catch (IllegalStateException expected) {
                assertEquals("subscription failed", expected.getMessage());
            }
            assertTrue(manager.isInitialized());
        }
    }

    private static class Host implements AutoCloseable {
        final ServiceDomain<Echo>           service      = new ServiceDomain<>(Echo.class);
        final BlockingQueue<RequestPayload> sent         = new LinkedBlockingQueue<>();
        final BlockingQueue<Runnable>       timers       = new LinkedBlockingQueue<>();
        final BlockingQueue<InterAddress>   targets      = new LinkedBlockingQueue<>();
        final AtomicInteger                 localFilters = new AtomicInteger(), remoteFilters = new AtomicInteger();
        final ConnectorManager callerManager;
        final ConnectorManager serverManager;
        final RsfCaller        caller;
        final RsfCaller        server;
        final RsfClientImpl    client;
        final RsfChannel       outgoing;
        final RsfChannel       incoming;
        boolean serviceAvailable = true;

        @SuppressWarnings("unchecked")
        Host(boolean deliver) throws Exception {
            this.service.setBindName("Echo");
            this.service.setServiceType(RsfServiceType.Provider);
            this.service.setSerializeType("Java");
            InterAddress peer = new InterAddress("rsf://127.0.0.1:2181/default");
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
                    case "getDefaultTimeout":
                        return 6000;
                    case "getMaximumRequest":
                        return 100;
                    case "getRequestOptions":
                    case "getResponseOptions":
                        return new OptionInfo();
                    default:
                        throw new AssertionError("Unexpected setting: " + m);
                }
            });
            JavaSerializeCoder coder = new JavaSerializeCoder();
            RsfContext context = proxy(RsfContext.class, (p, m, args) -> {
                switch (m.getName()) {
                    case "getSettings":
                        return settings;
                    case "getSerializeCoder":
                        return coder;
                    case "getClassLoader":
                        return getClass().getClassLoader();
                    case "getServiceInfo":
                        return this.serviceAvailable ? this.service : null;
                    case "getServiceProvider":
                        return (Supplier<Echo>) EchoImpl::new;
                    default:
                        throw new AssertionError("Unexpected context access: " + m);
                }
            });
            RsfFilter filter = (request, response, chain) -> {
                (request.isLocal() ? this.localFilters : this.remoteFilters).incrementAndGet();
                chain.doFilter(request, response);
            };
            RsfFilterProvider filters = id -> {
                assertEquals(this.service.getBindID(), id);
                return new Supplier[] { (Supplier<RsfFilter>) () -> filter };
            };
            this.incoming = proxy(RsfChannel.class, (object, method, arguments) -> {
                if ("getRemote".equals(method.getName())) {
                    return peer;
                }
                if ("sendData".equals(method.getName())) {
                    ResponsePayload response = (ResponsePayload) arguments[0];
                    this.deliverResponse(response);
                    return new BasicFuture<>((RsfChannel) object);
                }
                throw new AssertionError("Unexpected incoming channel access: " + method);
            });
            this.outgoing = proxy(RsfChannel.class, (object, method, arguments) -> {
                if ("sendData".equals(method.getName())) {
                    RequestPayload request = (RequestPayload) arguments[0];
                    request.setReceiveTime(System.currentTimeMillis());
                    this.sent.add(request);
                    if (deliver) {
                        this.deliverRequest(request);
                    }
                    return new BasicFuture<>((RsfChannel) object);
                }
                throw new AssertionError("Unexpected outgoing channel access: " + method);
            });
            this.callerManager = new ConnectorManager(context, new RsfCallerTest.MemoryFactory()) {
                @Override
                public BasicFuture<RsfChannel> connect(InterAddress address) {
                    Host.this.targets.add(address);
                    return new BasicFuture<>(Host.this.outgoing);
                }

                @Override
                public Cancellable schedule(Runnable task, long delay) {
                    Host.this.timers.add(task);
                    return () -> true;
                }
            };
            this.serverManager = new ConnectorManager(context, new RsfCallerTest.MemoryFactory());
            this.callerManager.init();
            this.serverManager.init();
            this.server = new RsfCaller(this.serverManager, filters);
            this.caller = new RsfCaller(this.callerManager, filters);
            this.client = new RsfClientImpl(this.caller, new InstanceAddressProvider(peer));
        }

        private void deliverRequest(RequestPayload request) {
            this.serverManager.onRequest(this.incoming, request.getRequestID(), request);
        }

        private void deliverResponse(ResponsePayload response) {
            this.callerManager.onResponse(this.outgoing, response.getRequestID(), response);
        }

        RsfFuture invoke() {
            return this.client.asyncInvoke(this.service, "echo", new Class<?>[] { String.class }, new Object[] { "value" });
        }

        public void close() {
            try {
                this.caller.close();
                this.server.close();
            } finally {
                this.callerManager.close();
                this.serverManager.close();
            }
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
