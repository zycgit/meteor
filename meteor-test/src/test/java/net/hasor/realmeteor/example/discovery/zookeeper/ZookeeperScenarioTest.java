/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.discovery.zookeeper;
import java.util.concurrent.TimeUnit;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.order.Order;
import net.hasor.example.order.OrderDemoService;
import net.hasor.example.order.OrderDemoServiceImpl;
import net.hasor.example.order.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.zookeeper.ZookeeperRegistryConfiguration;
import net.hasor.realmeteor.environment.storage.ZookeeperTestServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 一个应用下线再上线，ZooKeeper 自动订阅撤回并恢复其订单服务地址。 */
public class ZookeeperScenarioTest {
    @Rule
    public final TemporaryFolder data = new TemporaryFolder();

    @Test
    public void restoresDiscoveredProviderAfterOfflineAndOnline() throws Exception {
        // 外部 ZooKeeper 在测试中独占；应用只接收明确的注册地址和独立缓存目录。
        try (ZookeeperTestServer storage = new ZookeeperTestServer()) {
            ZookeeperRegistryConfiguration registry = new ZookeeperRegistryConfiguration();
            registry.setAddress(storage.getConnectString());
            registry.setRefreshMillis(60000);
            MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
            incoming.setDataHome(this.data.newFolder("provider").toPath());
            MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
            outgoing.setDataHome(this.data.newFolder("consumer").toPath());

            try (MetContext provider = incoming.buildContext(); //
                 MetContext consumer = outgoing.buildContext()) {
                // 所有容器启动后发布和订阅，通过自动发现的地址执行真实订单 RPC。
                provider.start();
                consumer.start();
                MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
                DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
                RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);
                String serviceId = subscribed.getBindID();
                OrderDemoService orders = consumer.getRsfClient().getRemote(subscribed);
                Order order = new Order();
                order.setId(1001);
                order.setDescription("zookeeper-discovered");
                orders.save(order);

                // 下线保持本地服务与订单数据，自动存储通知应先撤回消费者的可用地址。
                provider.offline();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!consumer.getUpdater().queryAvailableAddresses(serviceId).isEmpty()) {
                    if (System.nanoTime() >= deadline) {
                        break;
                    }
                    TimeUnit.MILLISECONDS.sleep(20);
                }
                assertTrue("Offline provider is still discoverable", //
                        consumer.getUpdater().queryAvailableAddresses(serviceId).isEmpty());

                // 重新上线后等待同一个订阅恢复，再远程读取下线前保存的订单。
                provider.online();
                RegistryDiscoveryExample.awaitDiscovery(consumer, subscribed);
                OrderQuery query = new OrderQuery();
                query.setId(order.getId());
                Order found = orders.find(query);
                assertEquals(order.getDescription(), found.getDescription());
            }
        }
    }
}
