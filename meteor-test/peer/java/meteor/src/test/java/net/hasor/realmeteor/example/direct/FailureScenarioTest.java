/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.SendLimitPolicy;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用点对点订单契约演示业务失败、超时、取消和实例离线上线。 */
public class FailureScenarioTest {
    @Test
    public void businessFailureIsDifferentFromTimeout() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        ControlledOrderService implementation = new ControlledOrderService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再将故障业务显式发布到 RSF。
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, implementation) //
                    .protocol("rsf").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 同一个业务接口通过查询编号触发可控失败，不改变正常订单示例。
            Class<?>[] failedTypes = { OrderQuery.class };
            Object[] failedArguments = { query(ControlledOrderService.FAILURE_ID) };
            MetFuture failed = remote.asyncInvoke(service, "find", failedTypes, failedArguments);
            Throwable business = failure(failed);
            assertTrue(business.toString(), business instanceof MetException);
            assertFalse(business instanceof MetTimeoutException);
            assertTrue(business.getMessage().contains("inventory unavailable"));
        }
    }

    @Test
    public void offlineClosesExistingConnection() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        ControlledOrderService implementation = new ControlledOrderService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再将故障业务显式发布到 RSF。
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, implementation) //
                    .protocol("rsf").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 首次调用已经建立真实连接，随后离线必须关闭监听和既有连接。
            OrderDemoService proxy = remote.getRemote(service);
            assertEquals("ready", proxy.find(query(42)).getDescription());
            server.offline();
            assertNull(server.getListenAddress("rsf"));
            Class<?>[] closedTypes = { OrderQuery.class };
            Object[] closedArguments = { query(42) };
            MetFuture closed = remote.asyncInvoke(service, "find", closedTypes, closedArguments);
            assertNotNull(failure(closed));
        }
    }

    @Test
    public void onlineAcceptsANewConnectionAfterOffline() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        ControlledOrderService implementation = new ControlledOrderService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端容器，再将故障业务显式发布到 RSF。
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, implementation) //
                    .protocol("rsf").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 旧连接关闭后重新上线，消费端使用当前监听地址恢复调用。
            assertEquals("ready", remote.getRemote(service).find(query(42)).getDescription());
            server.offline();
            server.online();
            MetClient reconnected = client.getRsfClient(server.getListenAddress("rsf"));
            assertEquals("ready", reconnected.getRemote(service).find(query(42)).getDescription());
        }
    }

    @Test
    public void timeoutReleasesTheRequestWhileTheBusinessTaskIsStillRunning() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        ControlledOrderService implementation = new ControlledOrderService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 所有容器启动后发布与订阅，消费者设置本次演示使用的请求超时。
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, implementation) //
                    .protocol("rsf").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").timeout(700).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            assertEquals("ready", remote.getRemote(service).find(query(42)).getDescription());

            // 业务信号固定任务执行阶段，RPC 超时不会终止已经运行的 Java 方法。
            try {
                Class<?>[] waitingTypes = { OrderQuery.class };
                Object[] waitingArguments = { query(ControlledOrderService.BLOCKING_ID) };
                MetFuture waiting = remote.asyncInvoke(service, "find", waitingTypes, waitingArguments);
                assertTrue(implementation.entered.await(3, TimeUnit.SECONDS));
                assertTrue(failure(waiting) instanceof MetTimeoutException);
                assertEquals(1, implementation.release.getCount());
                assertEquals("ready", remote.getRemote(service).find(query(42)).getDescription());
                implementation.release.countDown();
                assertTrue(implementation.finished.await(3, TimeUnit.SECONDS));
            } finally {
                implementation.release.countDown();
            }
        }
    }

    @Test
    public void anInFlightCallConsumesTheOnlyConcurrencyPermit() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        outgoing.setMaximumRequest(1).setSendLimitPolicy(SendLimitPolicy.Reject);
        ControlledOrderService implementation = new ControlledOrderService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 使用一个客户端在途配额，服务端仍允许其他业务线程执行。
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, implementation) //
                    .protocol("rsf").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").timeout(5000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 首个请求占满配额，后续请求必须明确拒绝，不能伪装为超时。
            try {
                Class<?>[] waitingTypes = { OrderQuery.class };
                Object[] waitingArguments = { query(ControlledOrderService.BLOCKING_ID) };
                MetFuture waiting = remote.asyncInvoke(service, "find", waitingTypes, waitingArguments);
                assertTrue(implementation.entered.await(3, TimeUnit.SECONDS));
                Class<?>[] secondTypes = { OrderQuery.class };
                Object[] secondArguments = { query(42) };
                MetFuture second = remote.asyncInvoke(service, "find", secondTypes, secondArguments);
                Throwable rejected = failure(second);
                assertTrue(rejected.toString(), rejected instanceof MetException);
                assertFalse(rejected instanceof MetTimeoutException);
                waiting.cancel();
            } finally {
                implementation.release.countDown();
            }
        }
    }

    @Test
    public void cancellingAnInFlightCallReturnsItsConcurrencyPermit() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        outgoing.setMaximumRequest(1).setSendLimitPolicy(SendLimitPolicy.Reject);
        ControlledOrderService implementation = new ControlledOrderService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 使用订单查询构造一个在途请求，业务阻塞由测试夹具持有。
            server.start();
            client.start();
            server.publisher().rsfService(OrderDemoService.class, implementation) //
                    .protocol("rsf").register();
            MetBindInfo<OrderDemoService> service = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").timeout(5000).register();
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 取消释放客户端配额，同一代理可立即继续完成普通查询。
            try {
                Class<?>[] waitingTypes = { OrderQuery.class };
                Object[] waitingArguments = { query(ControlledOrderService.BLOCKING_ID) };
                MetFuture waiting = remote.asyncInvoke(service, "find", waitingTypes, waitingArguments);
                assertTrue(implementation.entered.await(3, TimeUnit.SECONDS));
                assertTrue(waiting.cancel());
                assertTrue(waiting.isCancelled());
                assertEquals("ready", remote.getRemote(service).find(query(42)).getDescription());
                implementation.release.countDown();
                assertTrue(implementation.finished.await(3, TimeUnit.SECONDS));
            } finally {
                implementation.release.countDown();
            }
        }
    }

    private static OrderQuery query(int id) {
        OrderQuery query = new OrderQuery();
        query.setId(id);
        return query;
    }

    private static Throwable failure(MetFuture future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.getData(5, TimeUnit.SECONDS));
        return failure.getCause();
    }
}
