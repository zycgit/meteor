/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.discovery.jdbc;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.realmeteor.environment.discovery.RegistryDiscoveryEnvironment;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 应用退出后重新接入同一个 JDBC 注册库，持久治理仍在，运行期成员已撤回。 */
public class JdbcRegistryScenarioTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    @Test(timeout = 30000)
    public void discoversTheAnnotatedThriftOrderService() throws Exception {
        // 示例提供者发布全部协议，消费者只选择 Thrift；端点地址从 JDBC 注册库自动发现。
        var home = this.data.newFolder("thrift-registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open("jdbc", home)) {
            var registry = environment.configuration();
            MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
            incoming.setDataHome(this.data.newFolder("thrift-provider").toPath());
            MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "thrift");
            outgoing.setDataHome(this.data.newFolder("thrift-consumer").toPath());
            try (MetContext provider = incoming.buildContext(); //
                 MetContext consumer = outgoing.buildContext()) {
                provider.start();
                consumer.start();
                OrderDemoService implementation = new OrderDemoServiceImpl();
                MetBindInfo<OrderDemoService> published = DirectRpcExample.publish(provider, implementation);
                MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "thrift");
                RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);

                // 代理直接传递普通订单与查询对象，调用时不指定远端 RPC 地址。
                OrderDemoService orders = consumer.getRsfClient().getRemote(subscribed);
                Order expected = new Order();
                expected.setId(1002);
                expected.setDescription("JDBC 发现 Thrift 注解服务");
                orders.save(expected);
                OrderQuery query = new OrderQuery();
                query.setId(expected.getId());
                Order actual = orders.find(query);
                assertEquals(expected.getId(), actual.getId());
                assertEquals(expected.getDescription(), actual.getDescription());
                assertEquals(published.getBindID(), subscribed.getBindID());
            }
        }
    }

    @Test
    public void keepsGovernanceAfterRegisteredApplicationsExit() throws Exception {
        // 独立数据库容器跨越应用关闭和重新接入阶段，所有会话都通过 TCP 访问。
        var home = this.data.newFolder("persistent-registry").toPath();
        try (RegistryDiscoveryEnvironment environment = RegistryDiscoveryEnvironment.open("jdbc", home)) {
            var registry = environment.configuration();
            registry.setManagementToken("order-admin");
            registry.setRefreshMillis(60000);
            MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
            incoming.setDataHome(this.data.newFolder("provider").toPath());
            MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
            outgoing.setDataHome(this.data.newFolder("consumer").toPath());
            String serviceId;
            try (MetContext provider = incoming.buildContext(); //
                 MetContext consumer = outgoing.buildContext()) {
                // 容器全部启动后订阅并发布订单；地址来自自动订阅，不指定远端 RPC 地址。
                provider.start();
                consumer.start();
                MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
                DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
                RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);
                serviceId = subscribed.getBindID();
                Order order = new Order();
                order.setId(1001);
                order.setDescription("jdbc-discovered");
                Order saved = consumer.getRsfClient().getRemote(subscribed).save(order);
                assertEquals(order.getDescription(), saved.getDescription());

                // 治理写入注册库后，关闭两个业务应用，撤回它们各自的运行期成员。
                RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
                admin.getServiceAdmin(serviceId).putMeta("owner", "orders");
            }

            // 新应用只接入管理入口，观察同一个服务保留的治理数据及已清空的成员目录。
            MetConfiguration management = RegistryDiscoveryExample.consumer(registry, "rsf");
            management.setDataHome(this.data.newFolder("management").toPath());
            try (MetContext context = management.buildContext()) {
                context.start();
                RegistryAdmin admin = context.getExtension(RegistryAdmin.class);
                MetServiceAdmin service = admin.getServiceAdmin(serviceId);
                MetDirectoryQuery query = new MetDirectoryQuery();
                assertEquals("orders", service.fetchMeta().get("owner"));
                assertTrue(service.providers(null, query).getItems().isEmpty());
                assertTrue(service.consumers(null, query).getItems().isEmpty());
            }
        }
    }
}
