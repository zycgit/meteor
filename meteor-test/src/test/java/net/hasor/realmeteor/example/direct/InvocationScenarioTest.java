/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.FutureCallback;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.direct.OrderMessageService;
import net.hasor.example.direct.OrderView;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 同一订单接口的客户端调用形式；每个场景直接写出公开 API。 */
public class InvocationScenarioTest {

    @Test(timeout = 30000)
    public void invokeThroughTheTypedProxy() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端，再发布业务实现并订阅接口；消费者没有本地提供者。
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());

            // 订阅时登记静态地址，无参客户端从服务地址本选址。
            MetBindInfo<OrderDemoService> binding = client.publisher().rsfService(OrderDemoService.class) //
                    .name(DirectRpcExample.SERVICE_NAME).protocol("rsf") //
                    .bindAddress(server.getListenAddress("rsf")).register();
            MetClient remote = client.getRsfClient();

            // 类型代理提交普通订单，再从真实网络查询保存的结果。
            OrderDemoService orders = remote.getRemote(binding);
            orders.save(order(201, "typed proxy"));
            assertEquals("typed proxy", orders.find(query(201)).getDescription());
        }
    }

    @Test(timeout = 30000)
    public void invokeByTheServiceIdentity() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端，再发布业务实现并订阅接口；消费者没有本地提供者。
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client, "rsf");
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 服务 ID 与类型代理指向同一份服务声明。
            OrderDemoService orders = remote.getRemoteByID(binding.getBindID());
            orders.save(order(202, "service identity"));
            assertEquals("service identity", orders.find(query(202)).getDescription());
        }
    }

    @Test(timeout = 30000)
    public void queryThroughARestrictedInterfaceView() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端，再发布业务实现并订阅接口；消费者没有本地提供者。
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client, "rsf");
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 兼容视图只暴露查询方法，发布的接口和业务对象保持不变。
            remote.getRemote(binding).save(order(203, "query view"));
            OrderView view = remote.wrapper(binding, OrderView.class);
            assertEquals("query view", view.find(query(203)).getDescription());
        }
    }

    @Test(timeout = 30000)
    public void invokeByMethodNameAndDeclaredParameterTypes() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端，再发布业务实现并订阅接口；消费者没有本地提供者。
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client, "rsf");
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 泛化调用显式提供方法名、参数类型和参数值。
            remote.getRemote(binding).save(order(204, "generic method"));
            Class<?>[] types = { OrderQuery.class };
            Object[] arguments = { query(204) };
            Order result = (Order) remote.syncInvoke(binding, "find", types, arguments);
            assertEquals("generic method", result.getDescription());
        }
    }

    @Test(timeout = 30000)
    public void concurrentFuturesKeepEachOrderResultSeparate() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端，再发布业务实现并订阅接口；消费者没有本地提供者。
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client, "rsf");
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 同一个客户端提交一批订单，每个 Future 只对应自己的结果。
            List<MetFuture> pending = new ArrayList<>();
            Class<?>[] types = { Order.class };
            for (int index = 0; index < 24; index++) {
                Object[] arguments = { order(300 + index, "future-" + index) };
                pending.add(remote.asyncInvoke(binding, "save", types, arguments));
            }
            for (int index = 0; index < pending.size(); index++) {
                Order result = (Order) pending.get(index).getData(5, TimeUnit.SECONDS);
                assertEquals(300 + index, result.getId());
                assertEquals("future-" + index, result.getDescription());
            }
        }
    }

    @Test(timeout = 30000)
    public void observeTheResultThroughACallback() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动两端，再发布业务实现并订阅接口；消费者没有本地提供者。
            server.start();
            client.start();
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client, "rsf");
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));

            // 回调处理结果或异常；当前应用在关闭上下文前有界等待回调完成。
            BasicFuture<Object> completion = new BasicFuture<>();
            FutureCallback<Object> callback = new FutureCallback<>() {
                @Override
                public void completed(Object result) {
                    completion.completed(result);
                }

                @Override
                public void failed(Throwable failure) {
                    completion.failed(failure);
                }
            };
            Class<?>[] types = { Order.class };
            Object[] arguments = { order(205, "callback") };
            remote.callbackInvoke(binding, "save", types, arguments, callback);
            Order result = (Order) completion.get(5, TimeUnit.SECONDS);
            assertEquals("callback", result.getDescription());
        }
    }

    @Test(timeout = 30000)
    public void messageReceiptDoesNotWaitForOrderProcessingToFinish() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        incoming.setQueueMinPoolSize(2);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 消息使用单独的 void 契约，仅发布到支持消息回执的 RSF 协议。
            server.start();
            client.start();
            OrderDemoService orders = new OrderDemoServiceImpl();
            OrderMessageService processing = order -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Order processing was not released");
                    }
                    orders.save(order);
                } finally {
                    finished.countDown();
                }
            };
            server.publisher().rsfService(OrderMessageService.class, processing) //
                    .protocol("rsf").asMessage().register();
            MetBindInfo<OrderMessageService> binding = client.publisher().rsfService(OrderMessageService.class) //
                    .protocol("rsf").asMessage().register();

            // 业务进入处理后，回执先完成；它不承诺业务已经执行成功。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            Class<?>[] types = { Order.class };
            Object[] arguments = { order(206, "message") };
            MetFuture receipt = remote.asyncInvoke(binding, "accept", types, arguments);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertNull(receipt.getData(3, TimeUnit.SECONDS));
            assertEquals(1, finished.getCount());

            // 应用自己的完成信号确认订单最终入库，没有声明独立线程池能力。
            release.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals("message", orders.find(query(206)).getDescription());
        } finally {
            release.countDown();
        }
    }

    private static Order order(int id, String description) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        order.setAttachment(new byte[] { 1, 2, 3 });
        return order;
    }

    private static OrderQuery query(int id) {
        OrderQuery query = new OrderQuery();
        query.setId(id);
        return query;
    }
}
