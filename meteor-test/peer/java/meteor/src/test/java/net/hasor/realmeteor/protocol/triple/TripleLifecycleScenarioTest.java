/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleFlowServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

@NxnRpc(name = "triple")
public class TripleLifecycleScenarioTest {
    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void unaryDeadlineCompletesTheCallerFuture() throws Exception {
        // 两端从 bootstrap 创建，用较短的调用期限验证等待及时结束。
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 400).buildContext()) {
            // 所有容器先启动，再通过正式发布入口绑定业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();

            // 业务延迟返回，调用期限提前结束等待，允许本地或远端先观测到期。
            MetClient caller = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            Object[] arguments = { TripleMessage.of("delayed", 1500) };
            MetFuture pending = caller.asyncInvoke(service, "slow", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(3, TimeUnit.SECONDS);
            });
            Throwable cause = failure.getCause();
            boolean localTimeout = cause instanceof MetTimeoutException;
            GrpcStatusException status = GrpcStatusException.find(cause);
            boolean remoteTimeout = status != null && status.getCode() == 4;
            assertTrue(localTimeout || remoteTimeout, "Expected a deadline failure, got " + cause);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellingTheResponseStreamReleasesItsRemoteSource() throws Exception {
        // 两端从 bootstrap 创建，客户端调用期限独立于服务端业务。
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 所有容器先启动，再通过正式发布入口绑定业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // 等待服务端已经订阅源，再取消当前客户端响应流。
            Probe probe = new Probe();
            remote.never(TripleMessage.of("cancel", 0)).subscribe(probe);
            assertTrue(probe.subscribed.await(2, TimeUnit.SECONDS));
            assertTrue(implementation.getProducing().await(2, TimeUnit.SECONDS));
            probe.subscription.cancel();
            assertTrue(implementation.getCancelled().await(3, TimeUnit.SECONDS));

            // 取消只结束当前调用，其余 HTTP/2 stream 仍可工作。
            TripleMessage result = remote.echo(TripleMessage.of("after cancellation", 4));
            assertEquals("after cancellation", result.getText());
            assertEquals(5, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void streamDeadlineReleasesTheRemoteSourceWithoutDemand() throws Exception {
        // 两端从 bootstrap 创建，客户端调用期限独立于服务端业务。
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 600).buildContext()) {
            // 所有容器先启动，再通过正式发布入口绑定业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // 不向响应流发出需求，截止时间仍应终止调用并取消服务端源。
            Probe probe = new Probe();
            remote.never(TripleMessage.of("deadline", 0)).subscribe(probe);
            assertTrue(implementation.getProducing().await(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> probe.finished.get(3, TimeUnit.SECONDS));
            assertTrue(implementation.getCancelled().await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void closingTheClientFailsItsPendingResponseStream() throws Exception {
        // 两端从 bootstrap 创建，客户端调用期限独立于服务端业务。
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 所有容器先启动，再通过正式发布入口绑定业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // 在源未结束时关闭客户端，流必须收到终态而不等待请求超时。
            Probe probe = new Probe();
            remote.never(TripleMessage.of("closing", 0)).subscribe(probe);
            assertTrue(implementation.getProducing().await(2, TimeUnit.SECONDS));
            client.close();
            assertThrows(ExecutionException.class, () -> probe.finished.get(3, TimeUnit.SECONDS));
            assertTrue(implementation.getCancelled().await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void sourceFailureIsDeliveredAsStreamFailure() throws Exception {
        // 两端从 bootstrap 创建，客户端调用期限独立于服务端业务。
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 所有容器先启动，再通过正式发布入口绑定业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // 业务源在第一次拉取时失败，错误必须经流终态交付。
            Probe probe = new Probe();
            remote.broken(TripleMessage.of("failure", 0)).subscribe(probe);
            probe.subscription.request(1);
            assertThrows(ExecutionException.class, () -> probe.finished.get(3, TimeUnit.SECONDS));
            GrpcStatusException status = GrpcStatusException.find(probe.finished.getCause());
            assertNotNull(status);
            assertTrue(status.getDescription().contains("triple source failure"));
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void responseDemandControlsDeliveryAcrossHttp2Windows() throws Exception {
        // 两端从 bootstrap 创建，客户端调用期限独立于服务端业务。
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 10000).buildContext()) {
            // 所有容器先启动，再通过正式发布入口绑定业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // 订阅跨窗口的大消息；没有需求时不能向用户交付消息。
            Probe probe = new Probe();
            String text = "x".repeat(200000);
            remote.count(TripleMessage.of(text, 12)).subscribe(probe);
            assertTrue(probe.subscribed.await(2, TimeUnit.SECONDS));
            assertNull(probe.values.poll(100, TimeUnit.MILLISECONDS));

            // 单次只请求一条，消费后继续等待下一次明确需求。
            probe.subscription.request(1);
            TripleMessage first = probe.values.poll(3, TimeUnit.SECONDS);
            assertNotNull(first);
            assertEquals(200000, first.getText().length());
            assertEquals(0, first.getNumber());
            assertNull(probe.values.poll(100, TimeUnit.MILLISECONDS));

            // 追加额度后按顺序消费剩余消息，最后收到正常完成信号。
            probe.subscription.request(11);
            for (int index = 1; index < 12; index++) {
                TripleMessage message = probe.values.poll(3, TimeUnit.SECONDS);
                assertNotNull(message);
                assertEquals(index, message.getNumber());
            }
            probe.finished.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void invalidDemandDoesNotPoisonFollowingCalls() throws Exception {
        // 两端从 bootstrap 创建，客户端调用期限独立于服务端业务。
        TripleFlowServiceImpl implementation = new TripleFlowServiceImpl();
        try (MetContext server = this.configuration(true, 5000).buildContext(); //
             MetContext client = this.configuration(false, 5000).buildContext()) {
            // 所有容器先启动，再通过正式发布入口绑定业务接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);

            // 非正数需求只结束当前订阅，不能破坏底层连接的其他调用。
            Probe probe = new Probe();
            remote.count(TripleMessage.of("invalid demand", 2)).subscribe(probe);
            probe.subscription.request(0);
            assertThrows(ExecutionException.class, () -> probe.finished.get(3, TimeUnit.SECONDS));
            assertTrue(probe.finished.getCause() instanceof IllegalArgumentException);

            // 后续普通调用仍通过同一上下文正常完成。
            TripleMessage following = remote.echo(TripleMessage.of("following", 7));
            assertEquals("following", following.getText());
            assertEquals(8, following.getNumber());
        }
    }

    private MetConfiguration configuration(boolean listener, int timeout) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(timeout);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc").option("streamBody", true);
        return configuration;
    }

    private static final class Probe implements Flow.Subscriber<TripleMessage> {
        private final LinkedBlockingQueue<TripleMessage> values     = new LinkedBlockingQueue<>();
        private final CountDownLatch                     subscribed = new CountDownLatch(1);
        private final BasicFuture<Void>                  finished   = new BasicFuture<>();
        private       Flow.Subscription                  subscription;

        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            this.subscribed.countDown();
        }

        public void onNext(TripleMessage message) {
            this.values.add(message);
        }

        public void onError(Throwable failure) {
            this.finished.failed(failure);
        }

        public void onComplete() {
            this.finished.completed(null);
        }
    }
}
