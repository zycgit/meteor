/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.web;

import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.web.GrpcWebService;
import net.hasor.example.protocol.grpc.web.GrpcWebServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcWebContextTest {
    @Test(timeout = 20000)
    public void serviceWithoutContextIsVisibleOnlyAtTheDefault() throws Exception {
        // 一个端口配置两个挂载点，服务的发布配置决定实际可见范围。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc-web");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").addContextPath("/private").option("streamBody", true);
        MetConfiguration defaultOutgoing = this.outgoing("/web");
        MetConfiguration privateOutgoing = this.outgoing("/private");
        try (MetContext server = incoming.buildContext(); //
             MetContext defaults = defaultOutgoing.buildContext(); //
             MetContext extra = privateOutgoing.buildContext()) {
            // 先启动容器，再发布服务并为两个上下文分别订阅同一个 Java 接口。
            server.start();
            defaults.start();
            extra.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json") //
                    .register();
            MetBindInfo<GrpcWebService> defaultInfo = defaults.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> privateInfo = extra.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 客户端以自己的挂载路径发起请求，服务不能越过声明的上下文边界。
            var address = server.getListenAddress("grpc-web");
            GrpcWebService defaultRemote = defaults.getRsfClient(address).getRemote(defaultInfo);
            GrpcWebService privateRemote = extra.getRsfClient(address).getRemote(privateInfo);
            assertEquals("visible", defaultRemote.echo(Message.of("visible", 1)).getValue());
            Exception failure = assertThrows(Exception.class, () -> {
                privateRemote.echo(Message.of("hidden", 1));
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(12, status.getCode());
        }
    }

    @Test(timeout = 20000)
    public void explicitContextReplacesTheDefault() throws Exception {
        // 一个端口配置两个挂载点，服务的发布配置决定实际可见范围。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc-web");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").addContextPath("/private").option("streamBody", true);
        MetConfiguration defaultOutgoing = this.outgoing("/web");
        MetConfiguration privateOutgoing = this.outgoing("/private");
        try (MetContext server = incoming.buildContext(); //
             MetContext defaults = defaultOutgoing.buildContext(); //
             MetContext extra = privateOutgoing.buildContext()) {
            // 先启动容器，再发布服务并为两个上下文分别订阅同一个 Java 接口。
            server.start();
            defaults.start();
            extra.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc-web", "/private") //
                    .register();
            MetBindInfo<GrpcWebService> defaultInfo = defaults.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> privateInfo = extra.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 客户端以自己的挂载路径发起请求，服务不能越过声明的上下文边界。
            var address = server.getListenAddress("grpc-web");
            GrpcWebService defaultRemote = defaults.getRsfClient(address).getRemote(defaultInfo);
            GrpcWebService privateRemote = extra.getRsfClient(address).getRemote(privateInfo);
            assertEquals("visible", privateRemote.echo(Message.of("visible", 1)).getValue());
            Exception failure = assertThrows(Exception.class, () -> {
                defaultRemote.echo(Message.of("hidden", 1));
            });
            GrpcStatusException status = GrpcStatusException.find(failure);
            assertNotNull(status);
            assertEquals(12, status.getCode());
        }
    }

    @Test(timeout = 20000)
    public void multipleContextsShareTheSamePublishedService() throws Exception {
        // 一个端口配置两个挂载点，服务的发布配置决定实际可见范围。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc-web");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc-web") //
                .contextPath("/web").addContextPath("/private").option("streamBody", true);
        MetConfiguration defaultOutgoing = this.outgoing("/web");
        MetConfiguration privateOutgoing = this.outgoing("/private");
        try (MetContext server = incoming.buildContext(); //
             MetContext defaults = defaultOutgoing.buildContext(); //
             MetContext extra = privateOutgoing.buildContext()) {
            // 先启动容器，再发布服务并为两个上下文分别订阅同一个 Java 接口。
            server.start();
            defaults.start();
            extra.start();
            server.publisher().rsfService(GrpcWebService.class, new GrpcWebServiceImpl()) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc-web", "/web", "/private") //
                    .register();
            MetBindInfo<GrpcWebService> defaultInfo = defaults.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();
            MetBindInfo<GrpcWebService> privateInfo = extra.publisher().rsfService(GrpcWebService.class) //
                    .aliasName("grpc-web", "web.Echo").serialize("Json").register();

            // 客户端以自己的挂载路径发起请求，服务不能越过声明的上下文边界。
            var address = server.getListenAddress("grpc-web");
            GrpcWebService defaultRemote = defaults.getRsfClient(address).getRemote(defaultInfo);
            GrpcWebService privateRemote = extra.getRsfClient(address).getRemote(privateInfo);
            assertEquals("default", defaultRemote.echo(Message.of("default", 1)).getValue());
            assertEquals("private", privateRemote.echo(Message.of("private", 1)).getValue());
        }
    }

    private MetConfiguration outgoing(String path) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc-web");
        configuration.connector("http").option("httpVersion", 1).protocol("grpc-web") //
                .contextPath(path).option("streamBody", true);
        return configuration;
    }
}
