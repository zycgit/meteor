/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetIgnore;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** 发布身份、实现来源、本地优先和远端方法准入都复用订单契约。 */
public class PublicationScenarioTest {
    @Test(timeout = 30000)
    public void thriftUsesTheSharedOrderBindingAndTcpListener() throws Exception {
        // 提供者挂载全部协议，消费者只配置 Thrift；双方启动后直接发布和订阅普通接口。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("thrift");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            MetBindInfo<OrderDemoService> published = DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(client, "thrift");

            // 同一个绑定也携带 gRPC 资源，Thrift 使用接口注解编解码，不需要桥接对象或生成 DTO。
            var address = server.getListenAddress("thrift");
            OrderDemoService remote = client.getRsfClient(address).getRemote(subscribed);
            Order expected = order(504, "Thrift 注解订单");
            remote.save(expected);
            Order actual = remote.find(query(expected.getId()));
            assertEquals(expected.getId(), actual.getId());
            assertEquals(expected.getDescription(), actual.getDescription());
            assertArrayEquals(expected.getAttachment(), actual.getAttachment());
            assertEquals(published.getBindID(), subscribed.getBindID());
            assertEquals(server.getListenAddress("rsf").getPort(), address.getPort());
            assertEquals(server.getListenAddress("dubbo").getPort(), address.getPort());
        }
    }

    @Test(timeout = 30000)
    public void queryAServiceByIdentityAliasAndMetadata() throws Exception {
        MetConfiguration configuration = DirectRpcExample.client("rsf");
        try (MetContext context = configuration.buildContext()) {
            // 服务身份与协议别名分别设置；元信息供应用和治理规则查询。
            context.start();
            MetBindInfo<OrderDemoService> binding = context.publisher() //
                    .rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").group("demo").name("orders").version("1") //
                    .aliasName("demo", "order-query").register();
            binding.setMetaData("owner", "examples");

            // 查询返回声明视图，以服务身份和元信息确认是同一服务，不依赖包装对象引用。
            assertEquals("demo", binding.getBindGroup());
            assertEquals("orders", binding.getBindName());
            assertEquals("1", binding.getBindVersion());
            MetBindInfo<OrderDemoService> byIdentity = context.getServiceInfo("demo", "orders", "1");
            MetBindInfo<OrderDemoService> byAlias = context.getServiceInfo("demo", "order-query");
            assertEquals(binding.getBindID(), byIdentity.getBindID());
            assertEquals(binding.getBindID(), byAlias.getBindID());
            assertEquals(OrderDemoService.class, byIdentity.getBindType());
            assertEquals("examples", byIdentity.getMetaData("owner"));
            assertEquals("examples", byAlias.getMetaData("owner"));
            assertEquals("examples", context.getServiceInfo(binding.getBindID()).getMetaData("owner"));
        }
    }

    @Test(timeout = 30000)
    public void declareTheServiceIdentityWithAnInterfaceAnnotation() throws Exception {
        MetConfiguration configuration = DirectRpcExample.client("rsf");
        try (MetContext context = configuration.buildContext()) {
            // 声明接口上的注解用于提供者和消费者，注册时不重复写同一组身份参数。
            context.start();
            MetBindInfo<AnnotatedOrders> binding = context.publisher().rsfService(AnnotatedOrders.class) //
                    .protocol("rsf").register();

            // 该示例仅声明消费者，不创建监听或本地业务实现。
            assertEquals("demo", binding.getBindGroup());
            assertEquals("annotated-orders", binding.getBindName());
            assertEquals("3", binding.getBindVersion());
            MetBindInfo<AnnotatedOrders> declared = context.getServiceInfo("demo", "annotated-orders", "3");
            assertEquals(binding.getBindID(), declared.getBindID());
            assertEquals(AnnotatedOrders.class, declared.getBindType());
            assertNull(context.getListenAddress("rsf"));
        }
    }

    @Test(timeout = 30000)
    public void versionsKeepClassAndSupplierProvidersSeparate() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 专题服务只选 RSF，不为同名多版本创建相同的 HTTP 别名路径。
            server.publisher().rsfService(OrderDemoService.class).to(OrderDemoServiceImpl.class) //
                    .protocol("rsf").name("versioned-orders").version("1").register();
            OrderDemoService supplied = new OrderDemoServiceImpl();
            supplied.save(order(501, "supplier initial state"));
            AtomicInteger supplierCalls = new AtomicInteger();
            server.publisher().rsfService(OrderDemoService.class).toProvider(() -> {
                        supplierCalls.incrementAndGet();
                        return supplied;
                    }) //
                    .protocol("rsf").name("versioned-orders").version("2").register();
            MetBindInfo<OrderDemoService> first = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").name("versioned-orders").version("1").register();
            MetBindInfo<OrderDemoService> second = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").name("versioned-orders").version("2").register();

            // 类注册不承诺跨调用持有同一实例；保存返回值验证它确实处理了当前请求。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            OrderDemoService one = remote.getRemote(first);
            OrderDemoService two = remote.getRemote(second);
            assertEquals("version one", one.save(order(501, "version one")).getDescription());
            assertNotEquals(first.getBindID(), second.getBindID());
            assertEquals("1", first.getBindVersion());
            assertEquals("2", second.getBindVersion());

            // 应用自行持有 Supplier 返回的状态对象，第一个版本的保存不会改动它。
            assertEquals("supplier initial state", two.find(query(501)).getDescription());
            assertEquals("version two", two.save(order(501, "version two")).getDescription());
            assertEquals("version two", two.find(query(501)).getDescription());
            assertEquals("version two", supplied.find(query(501)).getDescription());
            assertTrue(supplierCalls.get() > 0);
        }
    }

    @Test(timeout = 30000)
    public void localProviderTakesPrecedenceEvenForAFixedRemoteAddress() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 两端注册相同服务身份，用不同订单内容标识实际执行的实现。
            OrderDemoService remoteOrders = new OrderDemoServiceImpl();
            remoteOrders.save(order(502, "remote provider"));
            server.publisher().rsfService(OrderDemoService.class, remoteOrders) //
                    .protocol("rsf").name("preferred-orders").register();
            OrderDemoService localOrders = new OrderDemoServiceImpl();
            localOrders.save(order(502, "local provider"));
            MetBindInfo<OrderDemoService> binding = client.publisher() //
                    .rsfService(OrderDemoService.class, localOrders) //
                    .protocol("rsf").name("preferred-orders").register();

            // 默认选址和固定地址都先执行本地提供者。
            OrderDemoService selected = client.getRsfClient().getRemote(binding);
            assertEquals("local provider", selected.find(query(502)).getDescription());
            OrderDemoService fixed = client.getRsfClient(server.getListenAddress("rsf")).getRemote(binding);
            assertEquals("local provider", fixed.find(query(502)).getDescription());
        }
    }

    @Test(timeout = 30000)
    public void ignoredQueryRemainsUnavailableToARemoteCaller() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 受限契约只在这个场景使用，不向通用的订单接口增加管理方法。
            OrderDemoService orders = new OrderDemoServiceImpl();
            RestrictedOrders restricted = new RestrictedOrders() {
                @Override
                public Order save(Order order) {
                    return orders.save(order);
                }

                @Override
                public Order find(OrderQuery query) {
                    return orders.find(query);
                }
            };
            server.publisher().rsfService(RestrictedOrders.class, restricted) //
                    .protocol("rsf").name("restricted-orders").register();
            MetBindInfo<OrderDemoService> binding = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").name("restricted-orders").register();

            // 客户端没有忽略注解，真正由远端契约拒绝 find；之后正常保存仍可用。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            var failure = remote.asyncInvoke(binding, "find", new Class<?>[] { OrderQuery.class }, //
                    new Object[] { query(503) });
            var denied = assertThrows(ExecutionException.class, () -> failure.getData(5, TimeUnit.SECONDS));
            assertEquals(ProtocolStatus.Forbidden, ((MetException) denied.getCause()).getStatus());
            Order saved = remote.getRemote(binding).save(order(503, "allowed save"));
            assertEquals("allowed save", saved.getDescription());
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

    @MetService(group = "demo", name = "annotated-orders", version = "3")
    public interface AnnotatedOrders extends OrderDemoService {
    }

    public interface RestrictedOrders extends OrderDemoService {
        @Override
        @MetIgnore
        Order find(OrderQuery query);
    }
}
