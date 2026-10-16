/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hessian;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.protocol.hessian.HessianDemoService;
import net.hasor.example.protocol.hessian.HessianDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianContextScenarioTest {
    @Test(timeout = 15000)
    public void unspecifiedServiceContextUsesOnlyTheDefault() throws Exception {
        // 一个端点挂载两个上下文，服务发布配置决定实际暴露范围。
        MetConfiguration incoming = this.incoming();
        MetConfiguration defaults = this.outgoing("/rpc");
        MetConfiguration isolated = this.outgoing("/private");
        AtomicInteger calls = new AtomicInteger();
        HessianDemoServiceImpl implementation = new HessianDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext defaultClient = defaults.buildContext(); //
             MetContext privateClient = isolated.buildContext()) {
            // 所有容器先启动，然后按服务的上下文声明发布并订阅。
            server.start();
            defaultClient.start();
            privateClient.start();
            server.publisher().rsfService(HessianDemoService.class, implementation) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .register();
            MetBindInfo<HessianDemoService> accepted = defaultClient.publisher() //
                    .rsfService(HessianDemoService.class).aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> excluded = privateClient.publisher() //
                    .rsfService(HessianDemoService.class).aliasName("hessian", "demo").serialize("Hessian").register();

            // 被选择的挂载点可以调用同一个 Java 服务。
            MetClient chosen = defaultClient.getRsfClient(server.getListenAddress("hessian"));
            assertEquals("selected", chosen.getRemote(accepted).echo("selected"));

            // 另一个真实挂载点不能穿透服务范围，业务对象只执行一次。
            MetClient other = privateClient.getRsfClient(server.getListenAddress("hessian"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "excluded" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                other.asyncInvoke(excluded, "echo", types, arguments).getData(5, TimeUnit.SECONDS);
            });
            assertNotNull(failure.getCause());
            assertEquals(1, calls.get());
        }
    }

    @Test(timeout = 15000)
    public void explicitServiceContextExcludesTheDefault() throws Exception {
        // 一个端点挂载两个上下文，服务发布配置决定实际暴露范围。
        MetConfiguration incoming = this.incoming();
        MetConfiguration defaults = this.outgoing("/rpc");
        MetConfiguration isolated = this.outgoing("/private");
        AtomicInteger calls = new AtomicInteger();
        HessianDemoServiceImpl implementation = new HessianDemoServiceImpl() {
            @Override
            public String echo(String value) {
                calls.incrementAndGet();
                return value;
            }
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext defaultClient = defaults.buildContext(); //
             MetContext privateClient = isolated.buildContext()) {
            // 所有容器先启动，然后按服务的上下文声明发布并订阅。
            server.start();
            defaultClient.start();
            privateClient.start();
            server.publisher().rsfService(HessianDemoService.class, implementation) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .contextPathByPrefix("hessian", "/private") //
                    .register();
            MetBindInfo<HessianDemoService> accepted = privateClient.publisher() //
                    .rsfService(HessianDemoService.class).aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> excluded = defaultClient.publisher() //
                    .rsfService(HessianDemoService.class).aliasName("hessian", "demo").serialize("Hessian").register();

            // 被选择的挂载点可以调用同一个 Java 服务。
            MetClient chosen = privateClient.getRsfClient(server.getListenAddress("hessian"));
            assertEquals("selected", chosen.getRemote(accepted).echo("selected"));

            // 另一个真实挂载点不能穿透服务范围，业务对象只执行一次。
            MetClient other = defaultClient.getRsfClient(server.getListenAddress("hessian"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "excluded" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                other.asyncInvoke(excluded, "echo", types, arguments).getData(5, TimeUnit.SECONDS);
            });
            assertNotNull(failure.getCause());
            assertEquals(1, calls.get());
        }
    }

    @Test(timeout = 15000)
    public void explicitMultipleContextsExposeOneServiceOnOnePort() throws Exception {
        // 一个服务同时选择默认与附加上下文，两个入口共用监听端口。
        MetConfiguration incoming = this.incoming();
        MetConfiguration defaults = this.outgoing("/rpc");
        MetConfiguration isolated = this.outgoing("/private");
        try (MetContext server = incoming.buildContext(); //
             MetContext defaultClient = defaults.buildContext(); //
             MetContext privateClient = isolated.buildContext()) {
            server.start();
            defaultClient.start();
            privateClient.start();
            server.publisher().rsfService(HessianDemoService.class, new HessianDemoServiceImpl()) //
                    .aliasName("hessian", "demo").serialize("Hessian") //
                    .contextPathByPrefix("hessian", "/rpc", "/private").register();
            MetBindInfo<HessianDemoService> firstService = defaultClient.publisher() //
                    .rsfService(HessianDemoService.class).aliasName("hessian", "demo").serialize("Hessian").register();
            MetBindInfo<HessianDemoService> secondService = privateClient.publisher() //
                    .rsfService(HessianDemoService.class).aliasName("hessian", "demo").serialize("Hessian").register();

            // 相同目标端口通过两个客户端挂载配置访问已发布服务。
            HessianDemoService first = defaultClient.getRsfClient(server.getListenAddress("hessian")) //
                    .getRemote(firstService);
            HessianDemoService second = privateClient.getRsfClient(server.getListenAddress("hessian")) //
                    .getRemote(secondService);
            assertEquals("default", first.echo("default"));
            assertEquals("additional", second.echo("additional"));
        }
    }

    private MetConfiguration incoming() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").bind("127.0.0.1", 0) //
                .protocol("hessian").contextPath("/rpc").addContextPath("/private");
        return configuration;
    }

    private MetConfiguration outgoing(String path) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("hessian");
        configuration.connector("http").protocol("hessian").contextPath(path);
        return configuration;
    }
}
