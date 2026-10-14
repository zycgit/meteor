/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.bootstrap;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.hasor.meteor.*;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.hprose.scenario.example.Echo;
import net.hasor.meteor.protocol.hprose.scenario.example.EchoService;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextPublisherTest extends BootstrapTestSupport {

    @Test
    public void hproseAliasDefaultsApplyToConsumer() throws Exception {
        // 启动提供 Hprose 默认别名的上下文。
        MetConfiguration configuration = this.configuration();
        try (MetContext runtime = configuration.buildContext()) {
            runtime.start();

            // 仅声明消费接口，使用协议默认别名注册。
            MetPublisher publisher = runtime.publisher();
            MetBindInfo<Echo> service = publisher.rsfService(Echo.class).register();

            // 别名、按别名查询和协议服务列表应指向同一个服务。
            assertEquals("echo", service.getAliasName("Hprose"));
            assertEquals(service.getBindID(), runtime.getServiceInfo("Hprose", "echo").getBindID());
            assertEquals(Collections.singletonList("echo"), runtime.getServiceIDs("Hprose"));
        }
    }

    @Test
    public void hproseAliasDefaultsApplyToInstance() throws Exception {
        // 启动提供 Hprose 默认别名的上下文。
        MetConfiguration configuration = this.configuration();
        try (MetContext runtime = configuration.buildContext()) {
            runtime.start();

            // 以服务实例注册，使用协议默认别名。
            MetPublisher publisher = runtime.publisher();
            MetBindInfo<Echo> service = publisher.rsfService(Echo.class, new EchoService()).register();

            // 别名、按别名查询和协议服务列表应指向同一个服务。
            assertEquals("echo", service.getAliasName("Hprose"));
            assertEquals(service.getBindID(), runtime.getServiceInfo("Hprose", "echo").getBindID());
            assertEquals(Collections.singletonList("echo"), runtime.getServiceIDs("Hprose"));
        }
    }

    @Test
    public void hproseAliasDefaultsApplyToImplementationClass() throws Exception {
        // 启动提供 Hprose 默认别名的上下文。
        MetConfiguration configuration = this.configuration();
        try (MetContext runtime = configuration.buildContext()) {
            runtime.start();

            // 以实现类注册，使用协议默认别名。
            MetPublisher publisher = runtime.publisher();
            MetBindInfo<Echo> service = publisher.rsfService(Echo.class, EchoService.class).register();

            // 别名、按别名查询和协议服务列表应指向同一个服务。
            assertEquals("echo", service.getAliasName("Hprose"));
            assertEquals(service.getBindID(), runtime.getServiceInfo("Hprose", "echo").getBindID());
            assertEquals(Collections.singletonList("echo"), runtime.getServiceIDs("Hprose"));
        }
    }

    @Test
    public void hproseAliasDefaultsApplyToSupplier() throws Exception {
        // 启动提供 Hprose 默认别名的上下文。
        MetConfiguration configuration = this.configuration();
        try (MetContext runtime = configuration.buildContext()) {
            runtime.start();

            // 以实例提供者注册，使用协议默认别名。
            MetPublisher publisher = runtime.publisher();
            Supplier<Echo> provider = EchoService::new;
            MetBindInfo<Echo> service = publisher.rsfService(Echo.class, provider).register();

            // 别名、按别名查询和协议服务列表应指向同一个服务。
            assertEquals("echo", service.getAliasName("Hprose"));
            assertEquals(service.getBindID(), runtime.getServiceInfo("Hprose", "echo").getBindID());
            assertEquals(Collections.singletonList("echo"), runtime.getServiceIDs("Hprose"));
        }
    }

    @Test
    public void duplicateAliasSelectsTheFirstRegistration() throws Exception {
        // 启动上下文，准备同一协议别名下的两个注册项。
        MetConfiguration configuration = this.configuration();
        try (MetContext runtime = configuration.buildContext()) {
            runtime.start();

            // 以不同服务名称注册同一个接口，保留相同的协议别名。
            MetPublisher publisher = runtime.publisher();
            MetBindInfo<Echo> first = publisher.rsfService(Echo.class).name("first").register();
            publisher.rsfService(Echo.class).name("second").register();

            // 按协议别名检索时仍解析到第一个注册项。
            assertEquals(first.getBindID(), runtime.getServiceInfo("Hprose", "echo").getBindID());
            assertEquals(Collections.singletonList("echo"), runtime.getServiceIDs("Hprose"));
        }
    }

    @Test
    public void publisherUsesTheRuntimeSettings() throws Exception {
        // 通过 bootstrap 构建上下文并取得发布入口。
        try (MetContext runtime = this.configuration().buildContext()) {
            MetPublisher publisher = runtime.publisher();

            // 发布入口直接使用所属上下文的配置。
            assertSame(runtime.getSettings(), publisher.getSettings());
        }
    }

    @Test
    public void bindFilterReturnsTheSamePublisher() throws Exception {
        // 准备正常放行的过滤器和发布入口。
        try (MetContext runtime = this.configuration().buildContext()) {
            MetPublisher publisher = runtime.publisher();
            MetFilter filter = (request, response, chain) -> chain.doFilter(request, response);

            // 绑定过滤器后可继续使用同一个发布入口。
            assertSame(publisher, publisher.bindFilter("test", filter));
        }
    }

    @Test
    public void registrationQueriesResolveTheSameService() throws Exception {
        // 构建上下文后注册本地服务实例。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {
            EchoService instance = new EchoService();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, instance).register();

            // 接口类型、服务 ID、服务三元组与协议别名应解析到同一个注册项。
            assertEquals(service.getBindID(), context.getServiceInfo(Echo.class).getBindID());
            String serviceId = service.getBindID();
            assertEquals(serviceId, context.getServiceInfo(serviceId).getBindID());
            String group = service.getBindGroup();
            String name = service.getBindName();
            String version = service.getBindVersion();
            MetBindInfo<?> byIdentity = context.getServiceInfo(group, name, version);
            assertEquals(service.getBindID(), byIdentity.getBindID());
            assertEquals(service.getBindID(), context.getServiceInfo("Hprose", "echo").getBindID());
            assertSame(instance, context.getServiceProvider(service).get());
        }
    }

    @Test
    public void registeredMetadataCanBeUpdated() throws Exception {
        // 构建上下文后注册本地服务实例。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {
            EchoService instance = new EchoService();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, instance).register();

            // 写入元数据后，从上下文重新查询也能读取到该值。
            service.setMetaData("tag", "value");
            assertEquals("value", context.getServiceInfo(service.getBindID()).getMetaData("tag"));

            // 删除同一键后，注册项不再保留该元数据。
            service.removeMetaData("tag");
            assertNull(service.getMetaData("tag"));
        }
    }

    @Test
    public void duplicateServiceRegistrationPreservesOriginal() throws Exception {
        // 构建上下文后注册本地服务实例。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {
            EchoService instance = new EchoService();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, instance).register();

            // 重复注册被拒绝，原服务与别名列表保持不变。
            var duplicate = context.publisher().rsfService(Echo.class, new EchoService());
            assertThrows(IllegalStateException.class, duplicate::register);
            assertEquals(Collections.singletonList(service.getBindID()), context.getServiceIDs());
            assertEquals(Collections.singletonList("echo"), context.getServiceIDs("Hprose"));
        }
    }

    @Test
    public void localCallsWorkBeforeNetworkStartup() throws Exception {
        // 构建上下文后注册本地服务实例。
        MetConfiguration contextConfiguration = this.configuration();
        try (MetContext context = contextConfiguration.buildContext()) {
            EchoService instance = new EchoService();
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class, instance).register();

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

            // 发起异步调用，由过滤器直接完成响应。
            Class<?>[] types = { String.class };
            Object[] arguments = { "value" };
            MetFuture call = context.getRsfClient().asyncInvoke(service, "echo", types, arguments);

            // 调用已经完成且没有开启任何网络监听。
            assertTrue(call.isDone());
            assertEquals("cached", call.getData(1, TimeUnit.SECONDS));
            assertTrue(context.runProtocols().isEmpty());
        }
    }
}
