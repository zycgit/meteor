/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextPublisherTest extends BootstrapTestSupport {

    @Test
    public void allPublisherOverloadsApplyHproseDefaultsBeforeRegistration() throws Exception {
        // 各注册入口都安装 Hprose 别名，查询结果使用同一服务容器。
        MetConfiguration runtimeConfiguration = this.configuration();
        try (MetContext runtime = runtimeConfiguration.buildContext()) {
            MetPublisher publisher = runtime.publisher();
            assertSame(runtime.getSettings(), publisher.getSettings());
            MetFilter filter = (request, response, chain) -> chain.doFilter(request, response);
            assertSame(publisher, publisher.bindFilter("test", filter));
            Supplier<Echo> provider = EchoService::new;
            List<MetBindInfo<Echo>> services = new ArrayList<>();
            services.add(publisher.rsfService(Echo.class).name("consumer").register());
            services.add(publisher.rsfService(Echo.class, new EchoService()).name("instance").register());
            services.add(publisher.rsfService(Echo.class, EchoService.class).name("class").register());
            services.add(publisher.rsfService(Echo.class, provider).name("supplier").register());

            // 各入口的默认别名一致，名称相同的别名查询命中首个已注册服务。
            for (MetBindInfo<Echo> service : services) {
                assertEquals("echo", service.getAliasName("Hprose"));
            }
            assertEquals(Collections.singletonList("echo"), runtime.getServiceIDs("Hprose"));
            assertEquals(services.get(0).getBindID(), runtime.getServiceInfo("Hprose", "echo").getBindID());
        }
    }

    @Test
    public void registrationQueriesAndLocalCallsShareTheContainerBeforeStart() throws Exception {
        // 构建后可注册及本地调用，网络监听仍需显式启动。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {
            EchoService instance = new EchoService();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, instance).register();
            assertEquals(service.getBindID(), context.getServiceInfo(Echo.class).getBindID());
            assertEquals(service.getBindID(), context.getServiceInfo(service.getBindID()).getBindID());
            String group = service.getBindGroup();
            String name = service.getBindName();
            String version = service.getBindVersion();
            MetBindInfo<?> byIdentity = context.getServiceInfo(group, name, version);
            assertEquals(service.getBindID(), byIdentity.getBindID());
            assertEquals(service.getBindID(), context.getServiceInfo("Hprose", "echo").getBindID());
            assertSame(instance, context.getServiceProvider(service).get());

            // 别名查询和元信息修改都作用于同一个容器对象。
            service.setMetaData("tag", "value");
            assertEquals("value", context.getServiceInfo(service.getBindID()).getMetaData("tag"));
            service.removeMetaData("tag");
            assertNull(service.getMetaData("tag"));
            var duplicate = context.publisher().rsfService(Echo.class, new EchoService());
            assertThrows(IllegalStateException.class, duplicate::register);
            assertEquals(Collections.singletonList(service.getBindID()), context.getServiceIDs());
            assertEquals(Collections.singletonList("echo"), context.getServiceIDs("Hprose"));

            // 启动前本地调用可用，但没有网络监听且不能提前上线。
            assertEquals("local", context.getRsfClient().getRemote(service).echo("local"));
            assertFalse(context.isOnline());
            assertTrue(context.runProtocols().isEmpty());
            assertNull(context.bindAddress("rsf"));
            assertThrows(IllegalStateException.class, context::online);
        }
    }

    @Test
    public void filterCanCompleteAnOutboundCallWithoutContinuingTheChain() throws Exception {
        // 过滤器直接返回缓存结果时，不建立网络连接。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {
            context.publisher().bindFilter("cached", (request, response, chain) -> response.sendData("cached"));
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            Class<?>[] types = { String.class };
            MetFuture call = context.getRsfClient().asyncInvoke(service, "echo", types, new Object[] { "value" });
            assertTrue(call.isDone());
            assertEquals("cached", call.getData(1, TimeUnit.SECONDS));
            assertTrue(context.runProtocols().isEmpty());
        }
    }
}
