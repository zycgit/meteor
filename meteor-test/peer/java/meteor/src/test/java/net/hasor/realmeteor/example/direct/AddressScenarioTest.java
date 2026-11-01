/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.hasor.meteor.address.InterAddress;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 在点对点订单示例上维护地址与单元、限速规则。 */
@NxnRpc(name = "rsf", version = "1")
public class AddressScenarioTest {
    private static final String UNIT_RULE = """
            <controlSet>
                <flowControl type="unit" enable="true">
                    <threshold>0.5</threshold>
                    <exclusions></exclusions>
                </flowControl>
            </controlSet>
            """;

    @Test
    public void preferTheProviderInTheConsumersUnit() throws Exception {
        // 两个真实提供者保存同一编号的订单，内容用于辨认实际选址。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("east");
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("west");
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setUnitName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 使用公开地址本 API 为同一个订阅维护两个提供者。
            InterAddress eastAddress = east.getListenAddress("rsf");
            InterAddress westAddress = west.getListenAddress("rsf");
            String serviceId = service.getBindID();
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(serviceId, List.of(eastAddress, westAddress));
            assertTrue(updater.updateFlowControl(serviceId, UNIT_RULE));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 同单元规则只保留 east 提供者，RPC 从该实例读取订单。
            assertEquals(List.of(eastAddress), updater.queryLocalUnitAddresses(serviceId));
            assertEquals("east", remote.find(query()).getDescription());
        }
    }

    @Test
    public void fallBackWhenTheLocalUnitProviderIsRemoved() throws Exception {
        // 两个真实提供者保存同一编号的订单，内容用于辨认实际选址。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("east");
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("west");
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setUnitName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 使用公开地址本 API 为同一个订阅维护两个提供者。
            InterAddress eastAddress = east.getListenAddress("rsf");
            InterAddress westAddress = west.getListenAddress("rsf");
            String serviceId = service.getBindID();
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(serviceId, List.of(eastAddress, westAddress));
            assertTrue(updater.updateFlowControl(serviceId, UNIT_RULE));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 撤去同单元地址后，同一个代理自动转向其他单元。
            updater.removeAddress(serviceId, eastAddress);
            assertEquals("west", remote.find(query()).getDescription());
        }
    }

    @Test
    public void restoringAnAddressRestoresLocalUnitPriority() throws Exception {
        // 两个真实提供者保存同一编号的订单，内容用于辨认实际选址。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("east");
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("west");
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setUnitName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 使用公开地址本 API 为同一个订阅维护两个提供者。
            InterAddress eastAddress = east.getListenAddress("rsf");
            InterAddress westAddress = west.getListenAddress("rsf");
            String serviceId = service.getBindID();
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(serviceId, List.of(eastAddress, westAddress));
            assertTrue(updater.updateFlowControl(serviceId, UNIT_RULE));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 模拟本单元提供者退出，然后重新加入地址本。
            updater.removeAddress(serviceId, eastAddress);
            assertEquals("west", remote.find(query()).getDescription());
            updater.appendAddress(serviceId, List.of(eastAddress));
            assertEquals("east", remote.find(query()).getDescription());
        }
    }

    @Test
    public void refreshAddressesReplacesThePreviousProviderList() throws Exception {
        // 两个真实提供者保存同一编号的订单，内容用于辨认实际选址。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("east");
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0).setUnitName("west");
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setUnitName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 使用公开地址本 API 为同一个订阅维护两个提供者。
            InterAddress eastAddress = east.getListenAddress("rsf");
            InterAddress westAddress = west.getListenAddress("rsf");
            String serviceId = service.getBindID();
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(serviceId, List.of(eastAddress, westAddress));
            assertTrue(updater.updateFlowControl(serviceId, UNIT_RULE));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 清除单元规则并整体刷新地址，原有 east 地址不应保留。
            assertTrue(updater.updateFlowControl(serviceId, ""));
            updater.refreshAddress(serviceId, List.of(westAddress));
            assertEquals(List.of(westAddress), updater.queryAllAddresses(serviceId));
            assertEquals("west", remote.find(query()).getDescription());
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void updateAnActiveRateLimitWithoutRecreatingTheClient() throws Exception {
        // 使用单独线程观察正在等待配额的调用，业务实现不包含测试同步逻辑。
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        ExecutorService calls = Executors.newSingleThreadExecutor();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            DirectRpcExample.publish(server, provider("available"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");
            MetUpdater updater = client.getUpdater();
            updater.appendAddress(service.getBindID(), List.of(server.getListenAddress("rsf")));

            // 60 秒窗口提供最初 60 个配额，消耗后新的调用必须等待。
            String rule = """
                    <controlSet>
                        <flowControl type="speed" enable="true">
                            <action>service</action>
                            <rate>1</rate>
                            <peak>60</peak>
                            <timeWindow>60000</timeWindow>
                        </flowControl>
                    </controlSet>
                    """;
            assertTrue(updater.updateFlowControl(service.getBindID(), rule));
            OrderDemoService remote = client.getRsfClient().getRemote(service);
            for (int index = 0; index < 60; index++) {
                assertEquals("available", remote.find(query()).getDescription());
            }
            CountDownLatch submitted = new CountDownLatch(1);
            var waiting = calls.submit(() -> {
                submitted.countDown();
                return remote.find(query());
            });
            assertTrue(submitted.await(3, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> waiting.get(100, TimeUnit.MILLISECONDS));

            // 清除规则后原有等待调用继续完成，代理与连接不需要重建。
            assertTrue(updater.updateFlowControl(service.getBindID(), ""));
            assertEquals("available", waiting.get(3, TimeUnit.SECONDS).getDescription());
        } finally {
            calls.shutdownNow();
            assertTrue(calls.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static OrderDemoService provider(String description) {
        OrderDemoService implementation = new OrderDemoServiceImpl();
        Order order = new Order();
        order.setId(42);
        order.setDescription(description);
        implementation.save(order);
        return implementation;
    }

    private static OrderQuery query() {
        OrderQuery query = new OrderQuery();
        query.setId(42);
        return query;
    }
}
