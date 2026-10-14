/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import static org.junit.Assert.*;

/** 用户配置经过服务绑定、TCP 请求和等待结果后的超时行为。 */
public class RequestTimeoutTest {
    @Test
    public void configuredRequestTimeoutExpiresAnAsynchronousCall() throws Exception {
        // 请求默认值覆盖服务默认值，两个上下文通过真实 TCP 连接。
        MetConfiguration serverConfig = this.serverConfiguration();
        MetConfiguration clientConfig = this.clientConfiguration();
        clientConfig.setDefaultTimeout(5000).setRequestTimeout(1000);
        BlockingEcho provider = new BlockingEcho();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, provider).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("ready", remote.getRemote(service).echo("ready"));

            // 服务端保持调用未完成，观察请求携带的超时及异步失败原因。
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "block" };
                MetFuture invocation = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
                assertEquals(1000, invocation.getRequest().getTimeout());
                assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
                ThrowingRunnable awaitTimeout = () -> invocation.getData(3, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, awaitTimeout);
                assertTrue(failure.getCause() instanceof MetTimeoutException);
            } finally {
                provider.release.countDown();
            }
        }
    }

    @Test
    public void defaultTimeoutRemainsTheFallbackForExistingConfigurations() throws Exception {
        // 只配置原有 defaultTimeout，未显式设置请求超时。
        MetConfiguration serverConfig = this.serverConfiguration();
        MetConfiguration clientConfig = this.clientConfiguration();
        clientConfig.setDefaultTimeout(1700);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, value -> value).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();

            // 通过公开客户端发起请求，检查继承值及远程结果。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "fallback" };
            MetFuture invocation = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
            assertEquals(1700, invocation.getRequest().getTimeout());
            assertEquals("fallback", invocation.getData(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void serviceTimeoutCanExtendASynchronousCallBeyondTheRequestDefault() throws Exception {
        // 服务显式超时高于客户端请求默认值，服务端的出站默认值更短。
        MetConfiguration serverConfig = this.serverConfiguration();
        serverConfig.setRequestTimeout(100);
        MetConfiguration clientConfig = this.clientConfiguration();
        clientConfig.setRequestTimeout(200);
        BlockingEcho provider = new BlockingEcho();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, provider).timeout(5000).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).timeout(3000).register();
            AtomicInteger requestTimeout = new AtomicInteger();
            client.publisher().bindFilter("observe-timeout", (request, response, chain) -> {
                requestTimeout.set(request.getTimeout());
                chain.doFilter(request, response);
            });
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));

            // 确认请求超过全局默认仍在等待，再释放服务完成同步调用。
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "block" };
                Future<Object> invocation = caller.submit(() -> {
                    return remote.syncInvoke(service, "echo", parameterTypes, arguments);
                });
                assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
                assertEquals(3000, requestTimeout.get());
                assertThrows(TimeoutException.class, () -> invocation.get(500, TimeUnit.MILLISECONDS));
                provider.release.countDown();
                assertEquals("block", invocation.get(3, TimeUnit.SECONDS));
            } finally {
                provider.release.countDown();
            }
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void annotationTimeoutOverridesTheRequestDefault() throws Exception {
        // 接口注解声明调用超时，应用只设置更短的请求默认值。
        MetConfiguration serverConfig = this.serverConfiguration();
        MetConfiguration clientConfig = this.clientConfiguration();
        clientConfig.setRequestTimeout(200);
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(AnnotatedEcho.class, value -> value).register();
            MetBindInfo<AnnotatedEcho> service = client.publisher().rsfService(AnnotatedEcho.class).register();
            server.start();
            client.start();

            // 注解的明确声明跟随远程请求，不被全局默认值覆盖。
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "annotated" };
            MetFuture invocation = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
            assertEquals(3000, invocation.getRequest().getTimeout());
            assertEquals("annotated", invocation.getData(3, TimeUnit.SECONDS));
        }
    }

    @Test
    public void synchronousCallReportsTheConfiguredRequestTimeout() throws Exception {
        // 保留较长服务默认值，只缩短调用端的请求超时。
        MetConfiguration serverConfig = this.serverConfiguration();
        MetConfiguration clientConfig = this.clientConfiguration();
        clientConfig.setDefaultTimeout(5000).setRequestTimeout(1000);
        BlockingEcho provider = new BlockingEcho();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, provider).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("ready", remote.getRemote(service).echo("ready"));

            // 同步调用必须在请求时限内报告超时，而不是继续等待服务默认时限。
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "block" };
                Future<Object> invocation = caller.submit(() -> {
                    return remote.syncInvoke(service, "echo", parameterTypes, arguments);
                });
                assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
                ThrowingRunnable awaitTimeout = () -> invocation.get(3, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, awaitTimeout);
                Throwable cause = failure.getCause();
                if (cause instanceof ExecutionException) {
                    cause = cause.getCause();
                }
                assertTrue(cause instanceof MetTimeoutException || cause instanceof TimeoutException);
            } finally {
                provider.release.countDown();
            }
        } finally {
            caller.shutdownNow();
        }
    }

    @Test
    public void callbackReceivesTheConfiguredRequestTimeout() throws Exception {
        // 回调调用与同步、Future 调用共用请求默认超时。
        MetConfiguration serverConfig = this.serverConfiguration();
        MetConfiguration clientConfig = this.clientConfiguration();
        clientConfig.setDefaultTimeout(5000).setRequestTimeout(1000);
        BlockingEcho provider = new BlockingEcho();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, provider).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("ready", remote.getRemote(service).echo("ready"));

            // 服务保持阻塞，直接检查公共回调报告的异常。
            CompletableFuture<Object> completion = new CompletableFuture<>();
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "block" };
                remote.callbackInvoke(service, "echo", parameterTypes, arguments, new FutureCallback<Object>() {
                    @Override
                    public void completed(Object result) {
                        completion.complete(result);
                    }

                    @Override
                    public void failed(Throwable failure) {
                        completion.completeExceptionally(failure);
                    }
                });
                assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
                ThrowingRunnable awaitTimeout = () -> completion.get(3, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, awaitTimeout);
                assertTrue(failure.getCause() instanceof MetTimeoutException);
            } finally {
                provider.release.countDown();
            }
        }
    }

    @Test
    public void timeoutReleasesTheOutstandingRequestPermit() throws Exception {
        // 客户端最多允许一个未完成请求，服务端保留线程处理后续调用。
        MetConfiguration serverConfig = this.serverConfiguration();
        serverConfig.setQueueMinPoolSize(2);
        MetConfiguration clientConfig = this.clientConfiguration();
        clientConfig.setRequestTimeout(1000).setMaximumRequest(1);
        BlockingEcho provider = new BlockingEcho();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher().rsfService(Echo.class, provider).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            server.start();
            client.start();
            MetClient remote = client.getRsfClient(server.bindAddress("rsf"));
            assertEquals("ready", remote.getRemote(service).echo("ready"));

            // 首次调用超时后仍不释放服务端，让第二次调用证明客户端额度已回收。
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] blockedArguments = { "block" };
                MetFuture expired = remote.asyncInvoke(service, "echo", parameterTypes, blockedArguments);
                assertTrue(provider.entered.await(3, TimeUnit.SECONDS));
                ThrowingRunnable awaitTimeout = () -> expired.getData(3, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, awaitTimeout);
                assertTrue(failure.getCause() instanceof MetTimeoutException);

                Object[] nextArguments = { "after-timeout" };
                MetFuture next = remote.asyncInvoke(service, "echo", parameterTypes, nextArguments);
                assertEquals("after-timeout", next.getData(3, TimeUnit.SECONDS));
            } finally {
                provider.release.countDown();
            }
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        return configuration;
    }

    private MetConfiguration clientConfiguration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        return configuration;
    }

    public interface Echo {
        String echo(String value) throws InterruptedException;
    }

    @MetService(clientTimeout = 3000)
    public interface AnnotatedEcho {
        String echo(String value);
    }

    public static class BlockingEcho implements Echo {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String echo(String value) throws InterruptedException {
            if (StringUtils.equals("block", value)) {
                this.entered.countDown();
                if (!this.release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test did not release the remote call");
                }
            }
            return value;
        }
    }
}
