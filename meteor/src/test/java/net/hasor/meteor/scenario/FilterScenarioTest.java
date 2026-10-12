/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

/** 全局和服务过滤器、请求/响应选项，以及直接返回缓存结果。 */
public class FilterScenarioTest {
    @Test
    public void carryOptionsAcrossTheNetworkAndOverrideAGlobalFilterForOneService() throws Exception {
        MetConfiguration incoming = new MetConfiguration();
        incoming.setResponseOption("region", "east");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration outgoing = new MetConfiguration();
        outgoing.setRequestOption("tenant", "public");
        outgoing.connector("tcp").protocol("rsf");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 全局过滤器可读取随请求传入的选项，并在响应中回传信息。
            AtomicInteger globalCalls = new AtomicInteger();
            server.publisher().bindFilter("tenant", (request, response, chain) -> {
                globalCalls.incrementAndGet();
                response.addOption("tenant", request.getOption("tenant"));
                chain.doFilter(request, response);
            });
            server.publisher().rsfService(Echo.class, value -> value).register();
            MetBindInfo<Echo> normal = client.publisher().rsfService(Echo.class).register();

            // 服务过滤器使用同名 ID 覆盖全局过滤器，仅对本服务生效。
            var special = server.publisher().rsfService(Echo.class, value -> value).name("special");
            special.bindFilter("tenant", (request, response, chain) -> {
                response.addOption("tenant", "service-only");
                chain.doFilter(request, response);
            }).register();
            MetBindInfo<Echo> scoped = client.publisher().rsfService(Echo.class).name("special").register();
            server.start();
            client.start();

            // withOptions 产生独立客户端，原客户端继续使用配置中的默认选项。
            MetClient base = client.getRsfClient(server.bindAddress("rsf"));
            MetClient tenant = base.withOptions(Map.of("tenant", "orders"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "payload" };
            MetResponse response = tenant.asyncInvoke(normal, "echo", types, arguments).get(5, TimeUnit.SECONDS);
            assertEquals("payload", response.getData());
            assertEquals("east", response.getOption("region"));
            assertEquals("orders", response.getOption("tenant"));
            MetResponse unchanged = base.asyncInvoke(normal, "echo", types, arguments).get(5, TimeUnit.SECONDS);
            assertEquals("public", unchanged.getOption("tenant"));
            MetResponse overridden = tenant.asyncInvoke(scoped, "echo", types, arguments).get(5, TimeUnit.SECONDS);
            assertEquals("service-only", overridden.getOption("tenant"));
            assertEquals(2, globalCalls.get());
        }
    }

    @Test
    public void serveACachedValueWithoutAnAddressOrNetworkConnection() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            // 出站过滤器可短路请求；未命中缓存时才交给后续链路。
            context.publisher().bindFilter("cache", (request, response, chain) -> {
                if (StringUtils.equals(request.getOption("cache"), "hit")) {
                    response.sendData("cached");
                    return;
                }
                chain.doFilter(request, response);
            });
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            context.start();
            MetClient cached = context.getRsfClient().withOptions(Map.of("cache", "hit"));
            Class<?>[] types = { String.class };
            MetFuture result = cached.asyncInvoke(service, "echo", types, new Object[] { "unused" });
            assertEquals("cached", result.getData(2, TimeUnit.SECONDS));
            assertTrue(context.getUpdater().queryAllAddresses(service.getBindID()).isEmpty());
            assertNull(context.bindAddress("rsf"));
        }
    }

    public interface Echo {
        String echo(String value);
    }
}
