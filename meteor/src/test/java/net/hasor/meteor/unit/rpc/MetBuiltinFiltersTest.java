/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.rpc;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.domain.MetFlags;
import net.hasor.meteor.domain.MetRequestObject;
import net.hasor.meteor.domain.MetResponseObject;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.ServiceDomain;
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
        // 提供一个可计数的本地服务工厂。
        AtomicInteger instances = new AtomicInteger();
        MetRequestObject request = request(1, true, true, () -> {
            instances.incrementAndGet();
            return new EchoImpl();
        });
        MetResponseObject response = new MetResponseObject(request);

        // 本地服务完成响应时，不再进入网络派发链。
        new LocalPref().doFilter(request, response, (req, res) -> {
            fail("Local service must short-circuit network dispatch");
        });
        assertEquals("value", response.getData());
        assertEquals(1, instances.get());
    }

    @Test
    public void localPreferenceInvokesTheRegisteredInterfaceOnNonPublicImplementations() throws Throwable {
        // 使用非公开的 lambda 实现，注册方法来自公开业务接口。
        Echo target = value -> value + "-local";
        MetRequestObject request = request(1, true, true, () -> target);
        MetResponseObject response = new MetResponseObject(request);

        // 通过注册接口调用实现并取得本地结果。
        new LocalPref().doFilter(request, response, (req, res) -> {
            fail("Local service expected");
        });
        assertEquals("value-local", response.getData());
    }

    @Test
    public void localPreferencePropagatesTheOriginalBusinessFailure() throws Throwable {
        // 本地实现抛出一个已知的业务异常。
        IllegalStateException cause = new IllegalStateException("business failure");
        MetRequestObject request = request(1, true, true, () -> new EchoImpl() {
            public String echo(String value) {
                throw cause;
            }
        });
        MetResponseObject response = new MetResponseObject(request);

        // 保留原始异常身份，失败后也不能回退到网络调用。
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            new LocalPref().doFilter(request, response, (req, res) -> {
                fail("Must not fall back after failure");
            });
        });
        assertSame(cause, failure);
    }

    @Test
    public void localPreferenceFallsThroughWhenTheProviderIsAbsent() throws Throwable {
        // 本地请求没有注册服务提供者。
        MetRequestObject request = request(1, true, true, null);
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 原请求和响应继续传入后续链，本地过滤器不提交响应。
        new LocalPref().doFilter(request, response, (req, res) -> {
            assertSame(request, req);
            assertSame(response, res);
            calls.incrementAndGet();
        });
        assertFalse(response.isResponse());
        assertEquals(1, calls.get());
    }

    @Test
    public void localPreferenceFallsThroughWhenTheProviderReturnsNull() throws Throwable {
        // 服务提供者存在，但本次没有返回本地实例。
        MetRequestObject request = request(2, true, true, () -> null);
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 空实例继续走后续链，不能提交一个空的本地响应。
        new LocalPref().doFilter(request, response, (req, res) -> {
            assertSame(request, req);
            assertSame(response, res);
            calls.incrementAndGet();
        });
        assertFalse(response.isResponse());
        assertEquals(1, calls.get());
    }

    @Test
    public void localPreferenceBypassesTheProviderForRemoteRequests() throws Throwable {
        // 远程请求即使具有本地提供者，也不能获取其服务实例。
        MetRequestObject request = request(3, false, true, () -> {
            throw new AssertionError("Remote request must bypass local preference");
        });
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 远程请求原样转交后续链，本地过滤器不产生响应。
        new LocalPref().doFilter(request, response, (req, res) -> {
            assertSame(request, req);
            assertSame(response, res);
            calls.incrementAndGet();
        });
        assertFalse(response.isResponse());
        assertEquals(1, calls.get());
    }

    @Test
    public void localPreferenceBypassesTheProviderForPointToPointRequests() throws Throwable {
        // 点对点调用明确指定了远端，不能获取本地服务实例。
        MetRequestObject request = request(4, true, true, () -> {
            throw new AssertionError("P2P request must bypass local preference");
        });
        request.setFlags(MetFlags.P2PFlag.addTag((short) 0));
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 保留请求与响应身份，继续执行点对点派发链。
        new LocalPref().doFilter(request, response, (req, res) -> {
            assertSame(request, req);
            assertSame(response, res);
            calls.incrementAndGet();
        });
        assertFalse(response.isResponse());
        assertEquals(1, calls.get());
    }

    @Test
    public void onlineFilterRejectsRemoteCallsWhileOffline() throws Throwable {
        // 下线状态下接收一个远程请求。
        MetRequestObject request = request(1, false, false, null);
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 直接返回 Forbidden，并停止后续过滤器链。
        new OnlineMetFilter().doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertEquals(0, calls.get());
        assertEquals(ProtocolStatus.Forbidden, response.getStatus());
        assertTrue(response.isResponse());
    }

    @Test
    public void onlineFilterAllowsRemoteCallsWhileOnline() throws Throwable {
        // 上线状态下接收一个远程请求。
        MetRequestObject request = request(1, false, true, null);
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 远程请求继续执行后续链，过滤器本身不提交响应。
        new OnlineMetFilter().doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        assertFalse(response.isResponse());
    }

    @Test
    public void onlineFilterAllowsLocalCallsWhileOffline() throws Throwable {
        // 下线状态下发起一个本地请求。
        MetRequestObject request = request(1, true, false, null);
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 下线限制不阻止本地请求，也不预先提交响应。
        new OnlineMetFilter().doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        assertFalse(response.isResponse());
    }

    @Test
    public void onlineFilterAllowsLocalCallsWhileOnline() throws Throwable {
        // 上线状态下发起一个本地请求。
        MetRequestObject request = request(1, true, true, null);
        MetResponseObject response = new MetResponseObject(request);
        AtomicInteger calls = new AtomicInteger();

        // 本地请求继续执行后续链，过滤器本身不提交响应。
        new OnlineMetFilter().doFilter(request, response, (req, res) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        assertFalse(response.isResponse());
    }

    @Test
    public void threadContextIsClearedAfterSuccess() throws Throwable {
        // 为一次普通过滤器调用准备请求与响应。
        MetRequestObject request = request(1, true, true, null);
        MetResponseObject response = new MetResponseObject(request);

        // 执行期间可见本次上下文，正常返回后清理线程状态。
        new LocalWarpFilter().doFilter(request, response, (req, res) -> {
            assertContext(request, response);
        });
        assertContext(null, null);
    }

    @Test
    public void threadContextIsClearedAfterFailure() throws Throwable {
        // 后续过滤器抛出一个已知异常。
        MetRequestObject request = request(1, true, true, null);
        MetResponseObject response = new MetResponseObject(request);
        IllegalStateException cause = new IllegalStateException("filter failed");

        // 异常原样传播，失败退出也清理线程上下文。
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            new LocalWarpFilter().doFilter(request, response, (req, res) -> {
                assertContext(request, response);
                throw cause;
            });
        });
        assertSame(cause, failure);
        assertContext(null, null);
    }

    @Test
    public void nestedCallsRestoreOuterContextAfterSuccess() throws Throwable {
        // 同一线程上的两次嵌套调用各自具有请求与响应。
        MetRequestObject outer = request(1, true, true, null);
        MetResponseObject outerResponse = new MetResponseObject(outer);
        MetRequestObject inner = request(2, true, true, null);
        MetResponseObject innerResponse = new MetResponseObject(inner);
        LocalWarpFilter filter = new LocalWarpFilter();

        // 内层执行期间切换上下文，返回后恢复尚未结束的外层调用。
        filter.doFilter(outer, outerResponse, (req, res) -> {
            assertContext(outer, outerResponse);
            filter.doFilter(inner, innerResponse, (nestedReq, nestedRes) -> {
                assertContext(inner, innerResponse);
            });
            assertContext(outer, outerResponse);
        });
        assertContext(null, null);
    }

    @Test
    public void nestedCallsRestoreOuterContextAfterFailure() throws Throwable {
        // 内层调用将在外层上下文仍然有效时失败。
        MetRequestObject outer = request(1, true, true, null);
        MetResponseObject outerResponse = new MetResponseObject(outer);
        MetRequestObject inner = request(2, true, true, null);
        MetResponseObject innerResponse = new MetResponseObject(inner);
        LocalWarpFilter filter = new LocalWarpFilter();
        IllegalStateException cause = new IllegalStateException("inner failed");

        // 内层异常原样传播，同时恢复外层请求与响应。
        filter.doFilter(outer, outerResponse, (req, res) -> {
            assertContext(outer, outerResponse);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                filter.doFilter(inner, innerResponse, (nestedReq, nestedRes) -> {
                    assertContext(inner, innerResponse);
                    throw cause;
                });
            });
            assertSame(cause, failure);
            assertContext(outer, outerResponse);
        });
        assertContext(null, null);
    }

    @Test
    public void threadsHaveIndependentContexts() throws Throwable {
        // 主线程与工作线程分别执行自己的请求。
        MetRequestObject outer = request(1, true, true, null);
        MetResponseObject outerResponse = new MetResponseObject(outer);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // 工作线程开始时没有主线程上下文，调用结束后恢复为空。
            new LocalWarpFilter().doFilter(outer, outerResponse, (req, res) -> {
                Future<?> task = worker.submit(() -> {
                    assertContext(null, null);

                    // 工作线程只在自己的过滤器链执行期间具有上下文。
                    try {
                        MetRequestObject inner = request(2, true, true, null);
                        MetResponseObject response = new MetResponseObject(inner);
                        new LocalWarpFilter().doFilter(inner, response, (nestedReq, nestedRes) -> {
                            assertContext(inner, response);
                        });
                    } catch (Throwable failure) {
                        throw new AssertionError(failure);
                    }
                    assertContext(null, null);
                });

                // 有界等待工作线程完成，再验证主线程上下文未被覆盖。
                task.get(2, TimeUnit.SECONDS);
                assertContext(outer, outerResponse);
            });
            assertContext(null, null);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    public void reusedWorkersStartWithoutThePreviousContext() throws Exception {
        // 单线程执行器确保下一任务复用同一工作线程。
        MetRequestObject request = request(1, true, true, null);
        MetResponseObject response = new MetResponseObject(request);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            // 第一个任务在调用期间建立上下文，完成后释放线程状态。
            Future<?> first = worker.submit(() -> {
                assertContext(null, null);
                try {
                    new LocalWarpFilter().doFilter(request, response, (req, res) -> {
                        assertContext(request, response);
                    });
                } catch (Throwable failure) {
                    throw new AssertionError(failure);
                }
                assertContext(null, null);
            });
            first.get(2, TimeUnit.SECONDS);

            // 复用同一工作线程时不能遗留上一次调用的上下文。
            Future<?> reused = worker.submit(() -> assertContext(null, null));
            reused.get(2, TimeUnit.SECONDS);
            assertContext(null, null);
        } finally {
            worker.shutdownNow();
        }
    }

    private static MetRequestObject request( //
            long id, //
            boolean local, //
            boolean online, //
            Supplier<Echo> service) throws Exception {
        // 只提供过滤器会读取的上下文能力，不创建真实 RPC 资源。
        ServiceDomain<Echo> bindInfo = new ServiceDomain<>(Echo.class);
        InvocationHandler handler = (proxy, method, args) -> {
            switch (method.getName()) {
                case "getServiceProvider":
                    return service;
                case "isOnline":
                    return online;
                default:
                    throw new AssertionError("Unexpected context access: " + method);
            }
        };
        ClassLoader loader = Echo.class.getClassLoader();
        MetContext context = (MetContext) Proxy.newProxyInstance(loader, new Class<?>[] { MetContext.class }, handler);

        // 请求携带业务接口上的公开方法，便于验证非公开服务实现。
        Method method = Echo.class.getMethod("echo", String.class);
        return new MetRequestObject(id, local, context, bindInfo, method, new Object[] { "value" });
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
