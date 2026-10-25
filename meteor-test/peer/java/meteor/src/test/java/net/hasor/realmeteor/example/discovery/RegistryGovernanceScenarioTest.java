/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.discovery;
import net.hasor.cobble.ObjectUtils;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;
import net.hasor.meteor.registry.admin.MetInstanceInfo;
import net.hasor.meteor.registry.admin.MetPage;
import net.hasor.meteor.registry.admin.MetRuleType;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.MetServiceInfo;
import net.hasor.meteor.registry.memory.MemoryRegistryConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 注册目录与运行期治理复用订单发布入口，修改均通过管理 API 下发。 */
public class RegistryGovernanceScenarioTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    @Test
    public void directoryDescribesOneOrderServiceAcrossAllProtocolMounts() throws Exception {
        // 共享内存注册库，独立缓存目录避免读取其他示例的地址。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<OrderDemoService> published = DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            provider.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .name("hidden-order").protocol("rsf").asShadow().register();
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);

            // 一个服务记录展示完整发布端点，订阅侧只声明实际选用的业务协议。
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            MetServiceAdmin orders = admin.getServiceAdmin(published.getBindID());
            MetDirectoryQuery query = new MetDirectoryQuery();
            RegistryScenarioUtils.await("Order consumer registration", () -> {
                return orders.consumers(null, query).getItems().size() == 1;
            });
            assertEquals(1, orders.providers(null, query).getItems().size());
            int protocolCount = orders.providers(null, query).getItems().get(0).getEndpoints().length;
            assertEquals(DirectRpcExample.PROTOCOLS.size(), protocolCount);
            int consumerEndpoints = orders.consumers(null, query).getItems().get(0).getEndpoints().length;
            assertEquals(1, consumerEndpoints);

            // 目录保留应用身份；隐藏服务不作为业务登记，标准 gRPC 服务按自己的名称区分。
            MetClusterAdmin cluster = admin.getClusterAdmin();
            MetPage<MetServiceInfo> services = cluster.services(DirectRpcExample.SERVICE_NAME, query);
            assertEquals(1, services.getItems().size());
            assertEquals(published.getBindID(), services.getItems().get(0).getServiceId());
            assertNull(services.getNextCursor());
            MetPage<MetInstanceInfo> instances = cluster.instances("order-provider", query);
            assertEquals(1, instances.getItems().size());
            assertTrue(instances.getItems().get(0).isOnline());
            assertNull(consumer.getListenAddress("rsf"));
        }
    }

    @Test
    public void applyGlobalDefaultsAndServiceMetadataBySubscription() throws Exception {
        // 周期核对为 60 秒，本例观察管理变更触发的自动订阅。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<OrderDemoService> published = DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);

            // 集群默认值与服务覆盖分别保存，由应用自动合并生效。
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            admin.getClusterAdmin().putMeta("region", "east");
            admin.getServiceAdmin(subscribed.getBindID()).putMeta("owner", "orders-team");
            RegistryScenarioUtils.await("Provider metadata", () -> {
                return ObjectUtils.equals("orders-team", published.getMetaData("owner"));
            });
            RegistryScenarioUtils.await("Consumer metadata", () -> {
                boolean ownerApplied = ObjectUtils.equals("orders-team", subscribed.getMetaData("owner"));
                boolean regionApplied = ObjectUtils.equals("east", subscribed.getMetaData("region"));
                return ownerApplied && regionApplied;
            });
            assertEquals("east", published.getMetaData("region"));
        }
    }

    @Test
    public void applyServiceRouteBySubscription() throws Exception {
        // 普通订单接口既是发布契约，也是消费者的远程代理契约。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);

            // 下发 XML 路由后观察公开治理结果，不主动 pull。
            String id = subscribed.getBindID();
            String route = "<route><return value='addresses'/></route>";
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            admin.getServiceAdmin(id).updateRule(MetRuleType.SERVICE_ROUTE, route);
            RegistryScenarioUtils.await("Order route", () -> {
                return ObjectUtils.equals(route, consumer.getUpdater().serviceRoute(id));
            });

            // 路由应用后从公开客户端发起真实调用。
            Order order = new Order();
            order.setId(1001);
            order.setDescription("governed-order");
            OrderDemoService orders = consumer.getRsfClient().getRemote(subscribed);
            assertEquals("governed-order", orders.save(order).getDescription());
        }
    }

    @Test
    public void reenableServiceRestoresItsDiscoveredEndpoint() throws Exception {
        // 管理服务启停只影响此订单服务，不停止应用或重建消费者。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);

            // 禁用撤回地址，重新启用后由订阅恢复地址。
            String id = subscribed.getBindID();
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = admin.getServiceAdmin(id);
            service.disable();
            RegistryScenarioUtils.await("Disabled order addresses", () -> {
                return consumer.getUpdater().queryAvailableAddresses(id).isEmpty();
            });
            assertFalse(service.isEnable());
            service.enable();
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);
            assertTrue(service.isEnable());

            // 同一远程契约恢复正常调用，服务端应用一直在线。
            Order order = new Order();
            order.setId(1001);
            order.setDescription("reenabled-order");
            OrderDemoService orders = consumer.getRsfClient().getRemote(subscribed);
            assertEquals("reenabled-order", orders.save(order).getDescription());
            assertTrue(provider.isOnline());
        }
    }

    @Test
    public void providerCloseWithdrawsAddressesAndKeepsInstanceHistory() throws Exception {
        // 持久实例档案与在线提供者关系分别查询。
        MemoryRegistryConfiguration registry = registry();
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(this.data.newFolder("provider").toPath());
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(this.data.newFolder("consumer").toPath());
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);

            // 提供者退出后，消费者地址和在线成员都自动撤回。
            String id = subscribed.getBindID();
            String instanceId = provider.getInstanceID();
            RegistryAdmin admin = consumer.getExtension(RegistryAdmin.class);
            MetServiceAdmin service = admin.getServiceAdmin(id);
            provider.close();
            RegistryScenarioUtils.await("Withdrawn provider addresses", () -> {
                return consumer.getUpdater().queryAvailableAddresses(id).isEmpty();
            });
            MetDirectoryQuery query = new MetDirectoryQuery();
            assertEquals(0, service.providers(null, query).getItems().size());
            assertEquals(id, service.info().getServiceId());

            // 服务定义及实例历史保留，实例标记为离线。
            MetPage<MetInstanceInfo> instances = admin.getClusterAdmin().instances("order-provider", query);
            assertEquals(1, instances.getItems().size());
            MetInstanceInfo archived = instances.getItems().get(0);
            assertEquals(instanceId, archived.getInstanceId());
            assertFalse(archived.isOnline());
        }
    }

    private static MemoryRegistryConfiguration registry() {
        MemoryRegistryConfiguration registry = new MemoryRegistryConfiguration();
        registry.setManagementToken("scenario-admin");
        registry.setRefreshMillis(60000);
        return registry;
    }
}
