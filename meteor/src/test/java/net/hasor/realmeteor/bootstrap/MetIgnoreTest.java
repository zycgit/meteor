/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.bootstrap;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.example.bootstrap.SelectiveServiceExample.Published;
import net.hasor.example.bootstrap.SelectiveServiceExample.RemoteView;
import net.hasor.example.bootstrap.SelectiveServiceExample.Service;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import static org.junit.Assert.*;

public class MetIgnoreTest {
    @Test
    public void localProxyCallsPublishedOverloadAndInheritedDefaults() throws Exception {
        // 通过公开发布入口创建本地代理，验证允许暴露的方法。
        MetConfiguration configuration = new MetConfiguration();
        Service target = new Service();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Published> service = context.publisher().rsfService(Published.class, target).register();
            context.start();
            Published proxy = context.getRsfClient().getRemote(service);

            // 普通重载、可见继承方法和相似方法名各自保持可调用。
            assertEquals("local", proxy.echo("local"));
            assertEquals("visible default", proxy.inheritedVisible());
            assertEquals("visible overload name", proxy.echoint());
            assertEquals(0, target.hiddenCalls());
        }
    }

    @Test
    public void localProxyRejectsIgnoredOverload() throws Exception {
        // 公开发布契约包含带注解的整型重载。
        MetConfiguration configuration = new MetConfiguration();
        Service target = new Service();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Published> service = context.publisher().rsfService(Published.class, target).register();
            context.start();
            Published proxy = context.getRsfClient().getRemote(service);

            // 代理拒绝本次调用，目标实现不得执行隐藏方法。
            assertForbidden(() -> proxy.echo(1));
            assertEquals(0, target.hiddenCalls());
        }
    }

    @Test
    public void localProxyRejectsIgnoredMethod() throws Exception {
        // 公开发布契约包含带注解的普通方法。
        MetConfiguration configuration = new MetConfiguration();
        Service target = new Service();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Published> service = context.publisher().rsfService(Published.class, target).register();
            context.start();
            Published proxy = context.getRsfClient().getRemote(service);

            // 代理拒绝本次调用，目标实现不得执行隐藏方法。
            assertForbidden(proxy::hidden);
            assertEquals(0, target.hiddenCalls());
        }
    }

    @Test
    public void localProxyRejectsIgnoredInheritedDefault() throws Exception {
        // 公开发布契约包含继承的带注解默认方法。
        MetConfiguration configuration = new MetConfiguration();
        Service target = new Service();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Published> service = context.publisher().rsfService(Published.class, target).register();
            context.start();
            Published proxy = context.getRsfClient().getRemote(service);

            // 代理拒绝本次调用，目标实现不得执行隐藏方法。
            assertForbidden(proxy::inheritedHidden);
            assertEquals(0, target.hiddenCalls());
        }
    }

    @Test
    public void unrestrictedClientCallsPublishedOverloadAndInheritedDefaults() throws Exception {
        // 客户端使用不带注解的独立接口，通过 TCP 访问服务端的公开方法。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        Service target = new Service();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher() //
                    .rsfService(Published.class, target) //
                    .name("SelectiveService").register();
            MetBindInfo<RemoteView> service = client.publisher() //
                    .rsfService(RemoteView.class) //
                    .name("SelectiveService").register();
            server.start();
            client.start();
            RemoteView remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 服务端允许普通重载及公开默认方法，调用结果经真实 RPC 返回。
            assertEquals("remote", remote.echo("remote"));
            assertEquals("visible default", remote.inheritedVisible());
            assertEquals("visible overload name", remote.echoint());
            assertEquals(0, target.hiddenCalls());
        }
    }

    @Test
    public void serverRejectsIgnoredOverloadWithoutPoisoningLaterCalls() throws Exception {
        // 不受客户端注解过滤限制的接口尝试调用带注解的整型重载。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        Service target = new Service();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher() //
                    .rsfService(Published.class, target) //
                    .name("SelectiveService").register();
            MetBindInfo<RemoteView> service = client.publisher() //
                    .rsfService(RemoteView.class) //
                    .name("SelectiveService").register();
            server.start();
            client.start();
            RemoteView remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 服务端拒绝本次调用，不执行目标方法，后续合法调用仍可用。
            assertForbidden(() -> remote.echo(1));
            assertEquals(0, target.hiddenCalls());
            assertEquals("still available", remote.echo("still available"));
        }
    }

    @Test
    public void serverRejectsIgnoredMethodWithoutPoisoningLaterCalls() throws Exception {
        // 不受客户端注解过滤限制的接口尝试调用带注解的普通方法。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        Service target = new Service();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher() //
                    .rsfService(Published.class, target) //
                    .name("SelectiveService").register();
            MetBindInfo<RemoteView> service = client.publisher() //
                    .rsfService(RemoteView.class) //
                    .name("SelectiveService").register();
            server.start();
            client.start();
            RemoteView remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 服务端拒绝本次调用，不执行目标方法，后续合法调用仍可用。
            assertForbidden(remote::hidden);
            assertEquals(0, target.hiddenCalls());
            assertEquals("still available", remote.echo("still available"));
        }
    }

    @Test
    public void serverRejectsIgnoredInheritedDefaultWithoutPoisoningLaterCalls() throws Exception {
        // 不受客户端注解过滤限制的接口尝试调用继承的带注解默认方法。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        Service target = new Service();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher() //
                    .rsfService(Published.class, target) //
                    .name("SelectiveService").register();
            MetBindInfo<RemoteView> service = client.publisher() //
                    .rsfService(RemoteView.class) //
                    .name("SelectiveService").register();
            server.start();
            client.start();
            RemoteView remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 服务端拒绝本次调用，不执行目标方法，后续合法调用仍可用。
            assertForbidden(remote::inheritedHidden);
            assertEquals(0, target.hiddenCalls());
            assertEquals("still available", remote.echo("still available"));
        }
    }

    @Test
    public void serverRejectsStaticMethodWithoutPoisoningLaterCalls() throws Exception {
        // 不受客户端注解过滤限制的接口尝试调用接口静态方法。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        Service target = new Service();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher() //
                    .rsfService(Published.class, target) //
                    .name("SelectiveService").register();
            MetBindInfo<RemoteView> service = client.publisher() //
                    .rsfService(RemoteView.class) //
                    .name("SelectiveService").register();
            server.start();
            client.start();
            RemoteView remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 服务端拒绝本次调用，不执行目标方法，后续合法调用仍可用。
            assertForbidden(remote::helper);
            assertEquals(0, target.hiddenCalls());
            assertEquals("still available", remote.echo("still available"));
        }
    }

    @Test
    public void serverRejectsImplementationOnlyMethodWithoutPoisoningLaterCalls() throws Exception {
        // 不受客户端注解过滤限制的接口尝试调用仅实现类提供的方法。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        Service target = new Service();
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.publisher() //
                    .rsfService(Published.class, target) //
                    .name("SelectiveService").register();
            MetBindInfo<RemoteView> service = client.publisher() //
                    .rsfService(RemoteView.class) //
                    .name("SelectiveService").register();
            server.start();
            client.start();
            RemoteView remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);

            // 服务端拒绝本次调用，不执行目标方法，后续合法调用仍可用。
            assertForbidden(remote::implementationOnly);
            assertEquals(0, target.hiddenCalls());
            assertEquals("still available", remote.echo("still available"));
        }
    }

    private static void assertForbidden(ThrowingRunnable action) {
        // RPC 代理可能包裹异常，沿原因链检查调用者感知的禁止状态。
        Throwable failure = assertThrows(Throwable.class, action);
        while (!(failure instanceof MetException) && failure.getCause() != null) {
            failure = failure.getCause();
        }
        assertTrue(failure.toString(), failure instanceof MetException);
        assertEquals(ProtocolStatus.Forbidden, ((MetException) failure).getStatus());
    }
}
