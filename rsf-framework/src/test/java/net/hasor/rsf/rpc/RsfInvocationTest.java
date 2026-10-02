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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfEnvironment;
import net.hasor.rsf.RsfFilter;
import net.hasor.rsf.RsfSettings;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.RsfChannel;
import net.hasor.rsf.domain.OptionInfo;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfServiceType;
import net.hasor.rsf.domain.ServiceDomain;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;
import net.hasor.rsf.serialize.SerializeCoder;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercises inbound validation, invocation and scheduling through the real dispatcher/task. */
public class RsfInvocationTest {
    public interface Service {
        String echo(String value);

        int length(String[] values);

        void ping();

        String fail();
    }

    @Test
    public void missingAndNonProviderServicesAreRejectedBeforeInvocation() throws Exception {
        try (Host host = new Host()) {
            host.servicePresent = false;
            host.assertStatus(ProtocolStatus.NotFound, host.request());
            host.servicePresent = true;
            host.service.setServiceType(RsfServiceType.Consumer);
            host.assertStatus(ProtocolStatus.NotFound, host.request());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void expiredRequestsUseDefaultAndServiceTimeoutLimits() throws Exception {
        try (Host host = new Host()) {
            for (int timeout : new int[] { 500, 0, -1, 20000 }) {
                RequestPayload request = host.request();
                request.setClientTimeout(timeout);
                request.setReceiveTime(System.currentTimeMillis() - 20000);
                host.assertStatus(ProtocolStatus.Timeout, request);
            }
            assertEquals(0, host.invocations.get());
            RequestPayload fresh = host.request();
            fresh.setClientTimeout(0);
            assertEquals("value", host.assertStatus(ProtocolStatus.OK, fresh).getReturnData());
        }
    }

    @Test
    public void defaultTimeoutAndServiceLimitAreConsistentForAdmissionAndFilters() throws Exception {
        try (Host host = new Host()) {
            AtomicInteger observed = new AtomicInteger();
            host.filters = new RsfFilter[] { (request, response, chain) -> {
                observed.set(request.getTimeout());
                chain.doFilter(request, response);
            } };
            for (int serviceTimeout : new int[] { 0, -1, 1000 }) {
                host.service.setClientTimeout(serviceTimeout);
                for (int clientTimeout : new int[] { 0, -1, 2000, 20000 }) {
                    RequestPayload request = host.request();
                    request.setClientTimeout(clientTimeout);
                    assertEquals("value", host.assertStatus(ProtocolStatus.OK, request).getReturnData());
                    int expected = Math.min(serviceTimeout > 0 ? serviceTimeout : 5000, clientTimeout > 0 ? clientTimeout : 5000);
                    assertEquals(expected, observed.get());
                }
            }
        }
    }

    @Test
    public void unsupportedAndBrokenSerializerLookupsHaveDistinctStatuses() throws Exception {
        try (Host host = new Host()) {
            host.coders = name -> null;
            host.assertStatus(ProtocolStatus.SerializeForbidden, host.request());
            host.coders = name -> {
                throw new IllegalStateException("coder failure");
            };
            assertTrue(host.assertStatus(ProtocolStatus.SerializeError, host.request()).getOption("message").contains("coder failure"));
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void invalidParameterCountsAndTypesDoNotInvokeService() throws Exception {
        try (Host host = new Host()) {
            RequestPayload mismatch = host.request();
            mismatch.getParameterValues().clear();
            host.assertStatus(ProtocolStatus.InvokeError, mismatch);
            RequestPayload unknownType = host.request();
            unknownType.getParameterTypes().set(0, "missing.request.Type");
            host.assertStatus(ProtocolStatus.SerializeError, unknownType);
            RequestPayload wrongValue = host.request();
            wrongValue.getParameterValues().set(0, 123);
            host.assertStatus(ProtocolStatus.InvokeError, wrongValue);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void missingMethodIsForbidden() throws Exception {
        try (Host host = new Host()) {
            RequestPayload request = host.request();
            request.setTargetMethod("missing");
            host.assertStatus(ProtocolStatus.Forbidden, request);
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void missingProviderAndMissingInstanceAreNotFound() throws Exception {
        try (Host host = new Host()) {
            host.provider = null;
            host.assertStatus(ProtocolStatus.NotFound, host.request());
            host.provider = () -> null;
            host.assertStatus(ProtocolStatus.NotFound, host.request());
        }
    }

    @Test
    public void serviceExceptionIsUnwrappedIntoFailureResponse() throws Exception {
        try (Host host = new Host()) {
            ResponsePayload response = host.assertStatus(ProtocolStatus.InvokeError, host.request("fail"));
            assertTrue(response.getOption("message").contains("business failure"));
            assertFalse(response.getOption("message").contains("InvocationTargetException"));
        }
    }

    @Test
    public void voidArrayPrimitiveAndNullResultsKeepTheirTypes() throws Exception {
        try (Host host = new Host()) {
            ResponsePayload nothing = host.assertStatus(ProtocolStatus.OK, host.request("ping"));
            assertNull(nothing.getReturnData());
            assertEquals("void", nothing.getReturnType());
            RequestPayload array = host.request("length");
            array.addParameter(String[].class.getName(), new String[] { "one", "two" });
            ResponsePayload length = host.assertStatus(ProtocolStatus.OK, array);
            assertEquals(2, length.getReturnData());
            assertEquals("int", length.getReturnType());
            RequestPayload empty = host.request();
            empty.getParameterValues().set(0, null);
            assertNull(host.assertStatus(ProtocolStatus.OK, empty).getReturnData());
        }
    }

    @Test
    public void filtersRunInOrderAndShareAttributesAndResponseOptions() throws Exception {
        try (Host host = new Host()) {
            List<String> order = new ArrayList<>();
            host.filters = new RsfFilter[] { (request, response, chain) -> {
                order.add("first-before");
                request.setAttribute("marker", "value");
                chain.doFilter(request, response);
                order.add("first-after");
                response.addOption("filtered", "yes");
            }, (request, response, chain) -> {
                order.add("second-before");
                assertEquals("value", request.getAttribute("marker"));
                chain.doFilter(request, response);
                order.add("second-after");
            } };
            host.serverOptions.addOption("server", "option");
            ResponsePayload response = host.assertStatus(ProtocolStatus.OK, host.request());
            assertEquals(Arrays.asList("first-before", "second-before", "second-after", "first-after"), order);
            assertEquals("option", response.getOption("server"));
            assertEquals("yes", response.getOption("filtered"));
            assertEquals(1, host.invocations.get());
        }
    }

    @Test
    public void filterMayShortCircuitOrCommitBeforeReachingInvocation() throws Exception {
        try (Host host = new Host()) {
            host.filters = new RsfFilter[] { (request, response, chain) -> response.sendData("cached") };
            assertEquals("cached", host.assertStatus(ProtocolStatus.OK, host.request()).getReturnData());
            host.filters = new RsfFilter[] { (request, response, chain) -> {
                response.sendData("committed");
                chain.doFilter(request, response);
            } };
            assertEquals("committed", host.assertStatus(ProtocolStatus.OK, host.request()).getReturnData());
            assertEquals(0, host.invocations.get());
        }
    }

    @Test
    public void filterAndProviderFailuresBecomeInvocationErrors() throws Exception {
        try (Host host = new Host()) {
            host.filters = new RsfFilter[] { (request, response, chain) -> {
                throw new IllegalStateException("filter failure");
            } };
            assertTrue(host.assertStatus(ProtocolStatus.InvokeError, host.request()).getOption("message").contains("filter failure"));
            host.filters = new RsfFilter[0];
            host.provider = () -> {
                throw new IllegalStateException("provider failure");
            };
            assertTrue(host.assertStatus(ProtocolStatus.InvokeError, host.request()).getOption("message").contains("provider failure"));
        }
    }

    @Test
    public void responseCoderUnavailableOrThrowingProducesSerializationFailure() throws Exception {
        try (Host host = new Host()) {
            AtomicInteger lookups = new AtomicInteger();
            host.coders = name -> lookups.incrementAndGet() == 1 ? new JavaSerializeCoder() : null;
            host.assertStatus(ProtocolStatus.SerializeForbidden, host.request());
            lookups.set(0);
            host.coders = name -> {
                if (lookups.incrementAndGet() == 1) {
                    return new JavaSerializeCoder();
                }
                throw new IllegalStateException("response coder failure");
            };
            host.assertStatus(ProtocolStatus.SerializeError, host.request());
            assertEquals(2, host.invocations.get());
        }
    }

    @Test
    public void messageExecutionNeverSendsTerminalResponseIncludingFailures() {
        try (Host host = new Host()) {
            RequestPayload message = host.request();
            message.setMessage(true);
            host.run(message);
            RequestPayload failure = host.request("fail");
            failure.setMessage(true);
            host.run(failure);
            host.servicePresent = false;
            host.run(message);
            assertTrue(host.responses.isEmpty());
            assertEquals(1, host.invocations.get());
        }
    }

    @Test
    public void boundedQueueRejectsExcessRequestsThenContinuesAfterDrain() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (Host host = new Host()) {
            host.filters = new RsfFilter[] { (request, response, chain) -> {
                if (request.getRequestID() == 1) {
                    entered.countDown();
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                }
                chain.doFilter(request, response);
            } };
            try {
                host.dispatch(1);
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                host.dispatch(2);
                host.dispatch(3);
                assertEquals(ProtocolStatus.Accept, host.take().getStatus());
                assertEquals(ProtocolStatus.Accept, host.take().getStatus());
                ResponsePayload rejected = host.take();
                assertEquals(3, rejected.getRequestID());
                assertEquals(ProtocolStatus.QueueFull, rejected.getStatus());
                release.countDown();
                assertEquals(ProtocolStatus.OK, host.take().getStatus());
                assertEquals(ProtocolStatus.OK, host.take().getStatus());
                assertEquals(2, host.invocations.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    public void dispatcherCloseInterruptsActiveWorkAndDiscardsQueuedWork() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try (Host host = new Host()) {
            host.filters = new RsfFilter[] { (request, response, chain) -> {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            } };
            host.dispatch(1);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            host.dispatch(2);
            host.dispatcher.close();
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(0, host.invocations.get());
            host.dispatch(3);
            // Two ACKs, the interrupted first call's response, and a rejection after shutdown.
            boolean rejected = false;
            for (int i = 0; i < 4; i++) {
                ResponsePayload response = host.take();
                if (response.getRequestID() == 3) {
                    assertEquals(ProtocolStatus.QueueFull, response.getStatus());
                    rejected = true;
                }
                assertFalse(response.getRequestID() == 2 && response.getStatus() != ProtocolStatus.Accept);
            }
            assertTrue(rejected);
        }
    }

    private static final class Host implements AutoCloseable {
        private final ServiceDomain<Service>           service        = new ServiceDomain<>(Service.class);
        private final AtomicInteger                    invocations    = new AtomicInteger();
        private final BlockingQueue<ResponsePayload>   responses      = new LinkedBlockingQueue<>();
        private final OptionInfo                       serverOptions  = new OptionInfo();
        private final RsfDispatcher                    dispatcher;
        private final RsfChannel                       channel;
        private       boolean                          servicePresent = true;
        private       Function<String, SerializeCoder> coders         = name -> new JavaSerializeCoder();
        private       RsfFilter[]                      filters        = new RsfFilter[0];
        private       Supplier<Service>                provider       = () -> new Service() {
            public String echo(String value) {
                Host.this.invocations.incrementAndGet();
                return value;
            }

            public int length(String[] values) {
                Host.this.invocations.incrementAndGet();
                return values.length;
            }

            public void ping() {
                Host.this.invocations.incrementAndGet();
            }

            public String fail() {
                throw new IllegalArgumentException("business failure");
            }
        };

        private Host() {
            this.service.setBindName("Service");
            this.service.setServiceType(RsfServiceType.Provider);
            this.service.setClientTimeout(10000);
            this.service.setSerializeType("Java");
            RsfSettings settings = proxy(RsfSettings.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getQueueMinPoolSize":
                    case "getQueueMaxPoolSize":
                    case "getQueueMaxSize":
                        return 1;
                    case "getQueueKeepAliveTime":
                        return 1000L;
                    case "getDefaultTimeout":
                        return 5000;
                    case "getServerOption":
                        return this.serverOptions;
                    default:
                        throw new AssertionError(method);
                }
            });
            RsfEnvironment environment = proxy(RsfEnvironment.class, (p, method, args) -> {
                if ("getSerializeCoder".equals(method.getName())) {
                    return this.coders.apply((String) args[0]);
                }
                throw new AssertionError(method);
            });
            RsfContext context = proxy(RsfContext.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getSettings":
                        return settings;
                    case "getEnvironment":
                        return environment;
                    case "getClassLoader":
                        return getClass().getClassLoader();
                    case "getServiceInfo":
                        return this.servicePresent ? this.service : null;
                    case "getServiceProvider":
                        return this.provider;
                    default:
                        throw new AssertionError(method);
                }
            });
            this.channel = proxy(RsfChannel.class, (p, method, args) -> {
                switch (method.getName()) {
                    case "getRemote":
                        return new InterAddress("rsf://localhost:2181/default");
                    case "sendData":
                        this.responses.add((ResponsePayload) args[0]);
                        return new BasicFuture<>((RsfChannel) p);
                    default:
                        throw new AssertionError(method);
                }
            });
            this.dispatcher = new RsfDispatcher(context, id -> this.suppliers());
        }

        @SuppressWarnings("unchecked")
        private Supplier<RsfFilter>[] suppliers() {
            return Arrays.stream(this.filters).map(filter -> (Supplier<RsfFilter>) () -> filter).toArray(Supplier[]::new);
        }

        private RequestPayload request() {
            RequestPayload request = this.request("echo");
            request.addParameter(String.class.getName(), "value");
            return request;
        }

        private RequestPayload request(String method) {
            RequestPayload request = new RequestPayload();
            request.setRequestID(1);
            request.setServiceGroup(this.service.getBindGroup());
            request.setServiceName(this.service.getBindName());
            request.setServiceVersion(this.service.getBindVersion());
            request.setSerializeType("Java");
            request.setTargetMethod(method);
            request.setClientTimeout(10000);
            request.setReceiveTime(System.currentTimeMillis());
            return request;
        }

        private void run(RequestPayload request) {
            new RsfInvocationTask(this.channel, this.dispatcher, request).run();
        }

        private ResponsePayload assertStatus(short status, RequestPayload request) throws Exception {
            this.run(request);
            ResponsePayload response = this.take();
            assertEquals(status, response.getStatus());
            assertEquals(request.getRequestID(), response.getRequestID());
            return response;
        }

        private ResponsePayload take() throws Exception {
            ResponsePayload response = this.responses.poll(3, TimeUnit.SECONDS);
            assertNotNull("Expected response", response);
            return response;
        }

        private void dispatch(long id) {
            RequestPayload request = this.request();
            request.setRequestID(id);
            this.dispatcher.onRequest(this.channel, id, request);
        }

        public void close() {
            this.dispatcher.close();
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
