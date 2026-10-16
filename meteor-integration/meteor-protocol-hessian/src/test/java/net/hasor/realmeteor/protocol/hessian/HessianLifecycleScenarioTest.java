/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianLifecycleScenarioTest {
    @Test(timeout = 15000)
    public void concurrentHttp2CallsKeepTheirOwnResults() throws Exception {
        // HTTP/2 在并发承载多个普通 RPC。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器先启动，再使用公开发布与订阅 API 注册普通 Java 接口。
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .register();
            MetBindInfo<HessianDemoService> service = client.publisher() //
                    .rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .register();

            // 一次提交所有调用，避免同步等待将场景退化为串行。
            MetClient remote = client.getRsfClient(server.getListenAddress("hessian"));
            List<MetFuture> pending = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                Object[] arguments = { "request-" + i };
                pending.add(remote.asyncInvoke(service, "echo", new Class<?>[] { String.class }, arguments));
            }

            // 每条 HTTP/2 stream 对应自己的 Hessian 调用结果。
            for (int i = 0; i < pending.size(); i++) {
                assertEquals("request-" + i, pending.get(i).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 15000)
    public void requestTimeoutCompletesTheCallerFuture() throws Exception {
        // 用闩锁保持一次真实远程调用在途，控制取消或超时的触发时刻。
        MetConfiguration incoming = this.serverConfiguration();
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = this.clientConfiguration(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HessianDemoServiceImpl implementation = new HessianDemoServiceImpl() {
            @Override
            public String echo(String value) {
                if (StringUtils.equals("blocked", value)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timed out");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(HessianDemoService.class, implementation) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .register();
            MetBindInfo<HessianDemoService> service = client.publisher() //
                    .rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .timeout(600) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("hessian"));

            // 只有确认业务已进入后才检查调用方的终止结果。
            try {
                Class<?>[] types = { String.class };
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "blocked" });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(3, TimeUnit.SECONDS);
                });
                assertTrue(failure.getCause() instanceof MetTimeoutException);
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 15000)
    public void cancellingOneHttp2CallDoesNotCancelOtherCalls() throws Exception {
        // 用闩锁保持一次真实远程调用在途，控制取消或超时的触发时刻。
        MetConfiguration incoming = this.serverConfiguration();
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = this.clientConfiguration(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HessianDemoServiceImpl implementation = new HessianDemoServiceImpl() {
            @Override
            public String echo(String value) {
                if (StringUtils.equals("blocked", value)) {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test release timed out");
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                }
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(HessianDemoService.class, implementation) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .register();
            MetBindInfo<HessianDemoService> service = client.publisher() //
                    .rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .timeout(5000) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("hessian"));

            // 只有确认业务已进入后才检查调用方的终止结果。
            try {
                Class<?>[] types = { String.class };
                MetFuture pending = remote.asyncInvoke(service, "echo", types, new Object[] { "blocked" });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(pending.cancel());
                assertTrue(pending.isCancelled());

                // 取消只影响当前 HTTP/2 stream，其它请求仍能执行。
                MetFuture surviving = remote.asyncInvoke(service, "echo", types, new Object[] { "surviving" });
                assertEquals("surviving", surviving.getData(3, TimeUnit.SECONDS));
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 20000)
    public void closingTheServerFailsPendingCallsWithoutWaitingForTimeout() throws Exception {
        // 阻塞一次业务执行，观察连接关闭对在途调用的终止通知。
        MetConfiguration incoming = this.serverConfiguration();
        MetConfiguration outgoing = this.clientConfiguration(2).setRequestTimeout(10000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HessianDemoServiceImpl implementation = new HessianDemoServiceImpl() {
            @Override
            public String echo(String value) {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test release timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher() //
                    .rsfService(HessianDemoService.class, implementation) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .register();
            MetBindInfo<HessianDemoService> service = client.publisher() //
                    .rsfService(HessianDemoService.class) //
                    .aliasName("hessian", "demo").timeout(10000) //
                    .register();
            MetClient remote = client.getRsfClient(server.getListenAddress("hessian"));

            // 在业务期限之前关闭服务端，客户端应直接感知通道终止。
            try {
                Class<?>[] parameterTypes = { String.class };
                MetFuture pending = remote.asyncInvoke(service, "echo", parameterTypes, new Object[] { "blocked" });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                server.close();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.getData(5, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
            } finally {
                release.countDown();
            }
        }
    }

    @Test(timeout = 15000)
    public void publishingAFlowServiceIsRejectedBeforeAnyCall() throws Exception {
        // Hessian 的单次结果不能承载 Flow，错误必须在公开发布入口报告。
        MetConfiguration configuration = this.serverConfiguration();
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 启动容器后发布带持续响应的接口，服务不能进入可调用状态。
            FlowService implementation = () -> subscriber -> {
            };
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                server.publisher().rsfService(FlowService.class, implementation) //
                        .aliasName("hessian", "flow").serialize("Hessian").register();
            });
            assertNotNull(failure.getMessage());
        }
    }

    @Test(timeout = 15000)
    public void publishingAMessageServiceIsRejectedBeforeAnyCall() throws Exception {
        // Hessian 使用请求和响应，不将 @MetMessage 静默当成普通调用。
        MetConfiguration configuration = this.serverConfiguration();
        try (MetContext server = configuration.buildContext()) {
            server.start();

            // 方法级消息标注同样需要在发布时拒绝。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                server.publisher().rsfService(MessageService.class, value -> {
                        }) //
                        .aliasName("hessian", "message").serialize("Hessian").register();
            });
            assertNotNull(failure.getMessage());
        }
    }

    private MetConfiguration serverConfiguration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration clientConfiguration(int version) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").option("httpVersion", version) //
                .protocol("hessian").contextPath("/rpc");
        return configuration;
    }

    public interface FlowService {
        Flow.Publisher<String> values();
    }

    public interface MessageService {
        @MetMessage
        void accept(String value);
    }
}
