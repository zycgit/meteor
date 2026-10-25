/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.example.direct;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.example.direct.DirectRpcExample;
import net.hasor.example.Order;
import net.hasor.example.OrderDemoService;
import net.hasor.example.OrderDemoServiceImpl;
import net.hasor.example.OrderQuery;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 使用公开过滤器 API 展示 Options 传输、服务覆盖及缓存短路。 */
public class FilterScenarioTest {
    @Test(timeout = 30000)
    public void requestAndResponseOptionsCrossTheNetwork() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        incoming.setResponseOption("region", "east");
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        outgoing.setRequestOption("tenant", "orders");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 服务端过滤器读取请求 Options，并在响应中回传租户。
            server.publisher().bindFilter("tenant", (request, response, chain) -> {
                response.addOption("tenant", request.getOption("tenant"));
                chain.doFilter(request, response);
            });
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client, "rsf");

            // 普通订单结果与响应 Options 同时返回到调用方。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            Class<?>[] types = { Order.class };
            Object[] arguments = { order(601, "request options") };
            MetResponse result = remote.asyncInvoke(binding, "save", types, arguments).get(5, TimeUnit.SECONDS);
            assertEquals("request options", ((Order) result.getData()).getDescription());
            assertEquals("orders", result.getOption("tenant"));
            assertEquals("east", result.getOption("region"));
        }
    }

    @Test(timeout = 30000)
    public void withOptionsDoesNotChangeTheOriginalClient() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        outgoing.setRequestOption("tenant", "public");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 回传请求中的选项，便于观察每个客户端自己的覆盖值。
            server.publisher().bindFilter("tenant", (request, response, chain) -> {
                response.addOption("tenant", request.getOption("tenant"));
                chain.doFilter(request, response);
            });
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(client, "rsf");

            // withOptions 返回独立配置的客户端，原客户端继续使用应用默认值。
            MetClient original = client.getRsfClient(server.getListenAddress("rsf"));
            MetClient scoped = original.withOptions(Map.of("tenant", "order-team"));
            Class<?>[] types = { Order.class };
            Object[] arguments = { order(602, "client options") };
            MetResponse overridden = scoped.asyncInvoke(binding, "save", types, arguments).get(5, TimeUnit.SECONDS);
            MetResponse unchanged = original.asyncInvoke(binding, "save", types, arguments).get(5, TimeUnit.SECONDS);
            assertEquals("order-team", overridden.getOption("tenant"));
            assertEquals("public", unchanged.getOption("tenant"));
        }
    }

    @Test(timeout = 30000)
    public void aServiceFilterOverridesTheGlobalFilterWithTheSameId() throws Exception {
        MetConfiguration incoming = DirectRpcExample.server("127.0.0.1", 0, 0);
        MetConfiguration outgoing = DirectRpcExample.client("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 该全局过滤器用于普通服务，专用服务使用相同 ID 覆盖它。
            AtomicInteger globalCalls = new AtomicInteger();
            server.publisher().bindFilter("tenant", (request, response, chain) -> {
                globalCalls.incrementAndGet();
                response.addOption("tenant", "global");
                chain.doFilter(request, response);
            });
            DirectRpcExample.publish(server, new OrderDemoServiceImpl());
            MetBindInfo<OrderDemoService> normal = DirectRpcExample.subscribe(client, "rsf");
            server.publisher().rsfService(OrderDemoService.class, new OrderDemoServiceImpl()) //
                    .protocol("rsf").name("scoped-orders") //
                    .bindFilter("tenant", (request, response, chain) -> {
                        response.addOption("tenant", "service-only");
                        chain.doFilter(request, response);
                    }).register();
            MetBindInfo<OrderDemoService> scoped = client.publisher().rsfService(OrderDemoService.class) //
                    .protocol("rsf").name("scoped-orders").register();

            // 专用服务绕开同 ID 的全局过滤器，普通服务仍然执行它。
            MetClient remote = client.getRsfClient(server.getListenAddress("rsf"));
            Class<?>[] types = { Order.class };
            Object[] arguments = { order(603, "service filter") };
            MetResponse overridden = remote.asyncInvoke(scoped, "save", types, arguments).get(5, TimeUnit.SECONDS);
            assertEquals("service-only", overridden.getOption("tenant"));
            assertEquals(0, globalCalls.get());
            MetResponse unchanged = remote.asyncInvoke(normal, "save", types, arguments).get(5, TimeUnit.SECONDS);
            assertEquals("global", unchanged.getOption("tenant"));
            assertEquals(1, globalCalls.get());
        }
    }

    @Test(timeout = 30000)
    public void anOutboundFilterServesACachedOrderWithoutAnAddress() throws Exception {
        MetConfiguration configuration = DirectRpcExample.client("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 出站过滤器可返回缓存内容，无需创建网络连接或本地提供者。
            Order cachedOrder = order(604, "cached order");
            context.publisher().bindFilter("order-cache", (request, response, chain) -> {
                if (StringUtils.equals(request.getOption("cache"), "hit")) {
                    response.sendData(cachedOrder);
                    return;
                }
                chain.doFilter(request, response);
            });
            MetBindInfo<OrderDemoService> binding = DirectRpcExample.subscribe(context, "rsf");
            MetClient cached = context.getRsfClient().withOptions(Map.of("cache", "hit"));

            // 查询按正常类型接口执行，结果来自前置过滤器而不是远端选址。
            Order result = cached.getRemote(binding).find(query(604));
            assertEquals("cached order", result.getDescription());
            assertTrue(context.getUpdater().queryAllAddresses(binding.getBindID()).isEmpty());
            assertNull(context.getListenAddress("rsf"));
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
