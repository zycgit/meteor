/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.rpc.filters.local.LocalPref;
import net.hasor.meteor.rpc.filters.online.OnlineMetFilter;
import net.hasor.meteor.rpc.filters.thread.LocalWarpFilter;
import net.hasor.meteor.rpc.filters.thread.MetRequestLocal;
import net.hasor.meteor.rpc.filters.thread.MetResponseLocal;
import org.junit.Test;
import static org.junit.Assert.*;

public class MetBuiltinFiltersTest {
    public interface Echo {
        String echo(String value);
    }

    public static class EchoImpl implements Echo {
        public String echo(String value) {
            return value;
        }
    }

    @Test
    public void localPreferenceUsesOneServiceInstanceAndStopsTheChain() throws Throwable {
        AtomicInteger instances = new AtomicInteger();
        MetRequestObject request = request(1, true, true, () -> {
            instances.incrementAndGet();
            return new EchoImpl();
        });
        MetResponseObject response = new MetResponseObject(request);
        new LocalPref().doFilter(request, response, (req, res) -> fail("Local service must short-circuit network dispatch"));
        assertEquals("value", response.getData());
        assertEquals(1, instances.get());
    }

    @Test
    public void localPreferenceInvokesTheRegisteredInterfaceOnNonPublicImplementations() throws Throwable {
        Echo target = value -> value + "-local";
        MetRequestObject request = request(1, true, true, () -> target);
        MetResponseObject response = new MetResponseObject(request);
        new LocalPref().doFilter(request, response, (req, res) -> fail("Local service expected"));
        assertEquals("value-local", response.getData());
    }

    @Test
    public void localPreferencePropagatesTheOriginalBusinessFailure() throws Throwable {
        IllegalStateException cause = new IllegalStateException("business failure");
        MetRequestObject request = request(1, true, true, () -> new EchoImpl() {
            public String echo(String value) {
                throw cause;
            }
        });
        try {
            new LocalPref().doFilter(request, new MetResponseObject(request), (req, res) -> fail("Must not fall back after failure"));
            fail("Expected business failure");
        } catch (IllegalStateException expected) {
            assertSame(cause, expected);
        }
    }

    @Test
    public void localPreferenceFallsThroughForAbsentServicesAndRemoteOrP2PRequests() throws Throwable {
        MetRequestObject[] requests = { request(1, true, true, null), request(2, true, true, () -> null), request(3, false, true, () -> {
            throw new AssertionError("Remote request must bypass local preference");
        }), request(4, true, true, () -> {
            throw new AssertionError("P2P request must bypass local preference");
        }) };
        requests[3].setFlags(MetFlags.P2PFlag.addTag((short) 0));
        AtomicInteger calls = new AtomicInteger();
        for (MetRequestObject request : requests) {
            MetResponseObject response = new MetResponseObject(request);
            new LocalPref().doFilter(request, response, (req, res) -> {
                assertSame(request, req);
                assertSame(response, res);
                calls.incrementAndGet();
            });
            assertFalse(response.isResponse());
        }
        assertEquals(4, calls.get());
    }

    @Test
    public void onlineFilterRejectsOnlyRemoteCallsWhileOffline() throws Throwable {
        for (boolean local : new boolean[] { false, true }) {
            for (boolean online : new boolean[] { false, true }) {
                MetRequestObject request = request(1, local, online, null);
                MetResponseObject response = new MetResponseObject(request);
                AtomicInteger calls = new AtomicInteger();
                new OnlineMetFilter().doFilter(request, response, (req, res) -> calls.incrementAndGet());
                if (!local && !online) {
                    assertEquals(0, calls.get());
                    assertEquals(ProtocolStatus.Forbidden, response.getStatus());
                    assertTrue(response.isResponse());
                } else {
                    assertEquals(1, calls.get());
                    assertFalse(response.isResponse());
                }
            }
        }
    }

    @Test
    public void threadContextIsClearedAfterSuccessOrFailure() throws Throwable {
        for (boolean fail : new boolean[] { false, true }) {
            MetRequestObject request = request(1, true, true, null);
            MetResponseObject response = new MetResponseObject(request);
            IllegalStateException cause = new IllegalStateException("filter failed");
            try {
                new LocalWarpFilter().doFilter(request, response, (req, res) -> {
                    assertContext(request, response);
                    if (fail) {
                        throw cause;
                    }
                });
                assertFalse(fail);
            } catch (IllegalStateException expected) {
                assertSame(cause, expected);
            }
            assertContext(null, null);
        }
    }

    @Test
    public void nestedCallsRestoreOuterContextAfterSuccessOrFailure() throws Throwable {
        for (boolean fail : new boolean[] { false, true }) {
            MetRequestObject outer = request(1, true, true, null);
            MetResponseObject outerResponse = new MetResponseObject(outer);
            MetRequestObject inner = request(2, true, true, null);
            MetResponseObject innerResponse = new MetResponseObject(inner);
            LocalWarpFilter filter = new LocalWarpFilter();
            IllegalStateException cause = new IllegalStateException("inner failed");
            filter.doFilter(outer, outerResponse, (req, res) -> {
                assertContext(outer, outerResponse);
                try {
                    filter.doFilter(inner, innerResponse, (nestedReq, nestedRes) -> {
                        assertContext(inner, innerResponse);
                        if (fail) {
                            throw cause;
                        }
                    });
                    assertFalse(fail);
                } catch (IllegalStateException expected) {
                    assertSame(cause, expected);
                }
                assertContext(outer, outerResponse);
            });
            assertContext(null, null);
        }
    }

    @Test
    public void threadsHaveIndependentContextsAndReusedWorkersStayClean() throws Throwable {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            MetRequestObject outer = request(1, true, true, null);
            MetResponseObject outerResponse = new MetResponseObject(outer);
            new LocalWarpFilter().doFilter(outer, outerResponse, (req, res) -> {
                worker.submit(() -> {
                    assertContext(null, null);
                    try {
                        MetRequestObject inner = request(2, true, true, null);
                        MetResponseObject response = new MetResponseObject(inner);
                        new LocalWarpFilter().doFilter(inner, response, (nestedReq, nestedRes) -> assertContext(inner, response));
                    } catch (Throwable failure) {
                        throw new AssertionError(failure);
                    }
                    assertContext(null, null);
                }).get(2, TimeUnit.SECONDS);
                assertContext(outer, outerResponse);
            });
            worker.submit(() -> assertContext(null, null)).get(2, TimeUnit.SECONDS);
            assertContext(null, null);
        } finally {
            worker.shutdownNow();
        }
    }

    private static MetRequestObject request(long id, boolean local, boolean online, Supplier<Echo> service) throws Exception {
        ServiceDomain<Echo> bindInfo = new ServiceDomain<>(Echo.class);
        MetContext context = (MetContext) Proxy.newProxyInstance(Echo.class.getClassLoader(), new Class<?>[] { MetContext.class }, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getServiceProvider":
                    return service;
                case "isOnline":
                    return online;
                default:
                    throw new AssertionError("Unexpected context access: " + method);
            }
        });
        return new MetRequestObject(id, local, context, bindInfo, Echo.class.getMethod("echo", String.class), new Object[] { "value" });
    }

    private static void assertContext(MetRequest request, MetResponse response) {
        assertSame(request, new RequestView().current());
        assertSame(response, new ResponseView().current());
        if (request != null) {
            assertEquals(request.getRequestID(), new MetRequestLocal().getRequestID());
            assertEquals(response.getRequestID(), new MetResponseLocal().getRequestID());
        }
    }

    private static class RequestView extends MetRequestLocal {
        private MetRequest current() {
            return this.getRsfRequest();
        }
    }

    private static class ResponseView extends MetResponseLocal {
        private MetResponse current() {
            return this.getRsfResponse();
        }
    }
}
