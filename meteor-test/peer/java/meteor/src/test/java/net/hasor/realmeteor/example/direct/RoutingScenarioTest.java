/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.List;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetUpdater;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 路由规则使用环境、元信息和直接业务参数选择订单提供者。 */
public class RoutingScenarioTest {
    private static final String SERVICE_RULE  = """
            <route>
                <bind name="port" value="service.meta[env.applicationName]"/>
                <if test="service.meta['port'] != null">
                    <bind name="port" value="service.meta['port']"/>
                </if>
                <if test="utils.equals(method.name, 'find') and args[0].id == 1">
                    <bind name="port" value="service.meta[env.applicationName]"/>
                </if>
                <filter name="selected" from="addresses" item="a"
                        test="a.port == port and utils.equals(a.formUnit, env.unitName)"/>
                <return value="selected"/>
            </route>
            """;
    private static final String METHOD_RULE   = """
            <route>
                <filter name="selected" from="addresses" item="a"
                        test="utils.equals(method.name, 'find')"/>
                <return value="selected"/>
            </route>
            """;
    private static final String ARGUMENT_RULE = """
            <route>
                <filter name="selected" from="addresses" item="a"
                        test="a.port == service.meta[args[0].id == 1 ? 'east' : 'west']"/>
                <return value="selected"/>
            </route>
            """;

    @Test
    public void routeByTheApplicationEnvironment() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 应用名称来自框架环境，规则选择对应的实例端口与单元。
            assertTrue(updater.updateServiceRoute(serviceId, SERVICE_RULE));
            assertEquals("east", remote.find(query(42)).getDescription());
        }
    }

    @Test
    public void metadataUpdatesAffectTheNextCall() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 无需重建订阅，更新元信息即可改变下一次调用的目标。
            assertTrue(updater.updateServiceRoute(serviceId, SERVICE_RULE));
            assertEquals("east", remote.find(query(42)).getDescription());
            service.setMetaData("port", westPort);
            assertEquals("west", remote.find(query(42)).getDescription());
        }
    }

    @Test
    public void anInvocationArgumentCanSelectTheDefaultProvider() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 当前元信息指向 west，特定订单查询仍可选择应用默认的 east。
            service.setMetaData("port", westPort);
            assertTrue(updater.updateServiceRoute(serviceId, SERVICE_RULE));
            assertEquals("east", remote.find(query(1)).getDescription());
            assertEquals("west", remote.find(query(42)).getDescription());
        }
    }

    @Test
    public void anInvalidRuleUpdateKeepsTheWorkingRule() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 无效规则更新被拒绝，已生效的 west 选址规则继续工作。
            service.setMetaData("port", westPort);
            assertTrue(updater.updateServiceRoute(serviceId, SERVICE_RULE));
            assertFalse(updater.updateServiceRoute(serviceId, "<route><invalid/></route>"));
            assertEquals("west", remote.find(query(42)).getDescription());
        }
    }

    @Test
    public void aRouteEvaluationFailureIsVisibleToTheCaller() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 规则可以编译但在调用时访问不存在的参数，由调用方感知失败。
            String invalidArgument = "<route><return value='args[9]'/></route>";
            assertTrue(updater.updateServiceRoute(serviceId, invalidArgument));
            assertThrows(IllegalArgumentException.class, () -> remote.find(query(42)));
        }
    }

    @Test
    public void argumentRulesChooseBetweenProviders() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 服务和方法层依次放行，参数层根据订单内容挑选提供者。
            assertTrue(updater.updateServiceRoute(serviceId, "<route><return value='addresses'/></route>"));
            String methodRule = "<route><return value='addresses'/></route>";
            assertTrue(updater.updateMethodRoute(serviceId, methodRule));
            assertTrue(updater.updateArgsRoute(serviceId, ARGUMENT_RULE));
            assertEquals("east", remote.find(query(1)).getDescription());
            assertEquals("west", remote.find(query(42)).getDescription());
        }
    }

    @Test
    public void methodRulesRejectExcludedMethods() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 方法规则仅允许 find，同一接口的 save 无可用目标。
            assertTrue(updater.updateServiceRoute(serviceId, SERVICE_RULE));
            assertTrue(updater.updateMethodRoute(serviceId, METHOD_RULE));
            assertEquals("east", remote.find(query(42)).getDescription());
            assertThrows(Exception.class, () -> remote.save(order(43, "blocked")));
        }
    }

    @Test
    public void clearingMethodAndArgumentRulesRestoresExcludedMethods() throws Exception {
        // 先启动提供者和消费者，再通过同一个订单接口发布与订阅。
        MetConfiguration incomingEast = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration incomingWest = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf").setApplicationName("east");
        try (MetContext east = incomingEast.buildContext(); //
             MetContext west = incomingWest.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            east.start();
            west.start();
            client.start();
            DirectRpcExample.publish(east, provider("east"));
            DirectRpcExample.publish(west, provider("west"));
            MetBindInfo<OrderDemoService> service = DirectRpcExample.subscribe(client, "rsf");

            // 路由脚本通过端口元信息选择地址，返回的订单辨认实际提供者。
            int eastPort = east.getListenAddress("rsf").getPort();
            int westPort = west.getListenAddress("rsf").getPort();
            service.setMetaData("east", eastPort);
            service.setMetaData("west", westPort);
            MetUpdater updater = client.getUpdater();
            String serviceId = service.getBindID();
            updater.appendAddress(serviceId, List.of(east.getListenAddress("rsf"), west.getListenAddress("rsf")));
            OrderDemoService remote = client.getRsfClient().getRemote(service);

            // 原有规则拒绝 save；清空后同一个代理恢复该方法。
            assertTrue(updater.updateMethodRoute(serviceId, METHOD_RULE));
            assertTrue(updater.updateArgsRoute(serviceId, ARGUMENT_RULE));
            assertThrows(Exception.class, () -> remote.save(order(43, "east")));
            assertTrue(updater.updateMethodRoute(serviceId, ""));
            assertTrue(updater.updateArgsRoute(serviceId, ""));
            assertEquals("restored", remote.save(order(43, "restored")).getDescription());
        }
    }

    private static OrderDemoService provider(String description) {
        OrderDemoService implementation = new OrderDemoServiceImpl();
        implementation.save(order(1, description));
        implementation.save(order(42, description));
        return implementation;
    }

    private static Order order(int id, String description) {
        Order order = new Order();
        order.setId(id);
        order.setDescription(description);
        return order;
    }

    private static OrderQuery query(int id) {
        OrderQuery query = new OrderQuery();
        query.setId(id);
        return query;
    }
}
