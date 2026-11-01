/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.discovery;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.discovery.RegistryDiscoveryExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 无注册存储时，通过目标实例的管理 RPC 查询和调整运行期治理。 */
@NxnRpc(name = "rsf", version = "1")
public class RegistryDirectAdminScenarioTest {
    @TempDir
    public Path data;

    @Test
    public void updateTargetOrderMetadataWithoutRegistryStorage() throws Exception {
        // 离线扩展只安装目标管理能力，不连接注册中心存储。
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false);
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            MetBindInfo<OrderDemoService> published = DirectRpcExample.publish(provider, new OrderDemoServiceImpl());

            // 显式目标地址只用于这个无存储管理场景，不冒充注册发现。
            assertNull(consumer.getExtension(RegistryAdmin.class));
            TargetAdmin targets = consumer.getExtension(TargetAdmin.class);
            InterAddress address = provider.getListenAddress("rsf");
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(address);
            MetServiceAdmin service = targets.getServiceAdminByAddress(address, published.getBindID());
            assertEquals(provider.getInstanceID(), instance.instance().getInstanceId());

            // 管理 RPC 直接更新目标实例中已发布的同一订单绑定。
            service.putMeta("color", "blue");
            assertEquals("blue", published.getMetaData("color"));
            assertEquals("blue", service.fetchMeta().get("color"));
        }
    }

    @Test
    public void unblockTargetInstanceRestoresNetworkOrderCalls() throws Exception {
        // 提供者和消费者都安装离线扩展，管理通道与业务通道共用 RSF 监听。
        RegistryConfiguration registry = new RegistryConfiguration().setOnline(false);
        MetConfiguration incoming = RegistryDiscoveryExample.provider(registry, "127.0.0.1", 0, 0);
        incoming.setDataHome(Files.createDirectory(this.data.resolve("provider")));
        MetConfiguration outgoing = RegistryDiscoveryExample.consumer(registry, "rsf");
        outgoing.setDataHome(Files.createDirectory(this.data.resolve("consumer")));
        try (MetContext provider = incoming.buildContext(); //
             MetContext consumer = outgoing.buildContext()) {
            provider.start();
            consumer.start();
            DirectRpcExample.publish(provider, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> subscribed = DirectRpcExample.subscribe(consumer, "rsf");
            InterAddress address = provider.getListenAddress("rsf");
            OrderDemoService orders = consumer.getRsfClient(address).getRemote(subscribed);
            Order order = new Order();
            order.setId(1001);
            order.setDescription("before-block");
            assertEquals("before-block", orders.save(order).getDescription());

            // block 阻断网络业务调用，管理 RPC 仍能读取状态并解除阻断。
            TargetAdmin targets = consumer.getExtension(TargetAdmin.class);
            MetInstanceAdmin instance = targets.getInstanceAdminByAddress(address);
            instance.block();
            assertTrue(instance.isBlock());
            assertThrows(Exception.class, () -> orders.save(order));
            instance.unblock();
            assertFalse(instance.isBlock());

            // 同一代理恢复业务调用，应用未关闭或重新上线。
            order.setDescription("after-unblock");
            assertEquals("after-unblock", orders.save(order).getDescription());
            assertTrue(provider.isOnline());
        }
    }
}
