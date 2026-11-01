/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.bootstrap;
import net.hasor.example.connector.protocol.HttpEchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.bootstrap.MetConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 仅通过发布、查询及实际调用入口验证协议拒绝与登记异常的隔离。 */
public class ServiceProtocolPublicationTest {
    @Test
    public void rejectingHttpPublicationPreservesAnotherProtocolsRealRpc() throws Exception {
        // HTTP 适配拒绝当前服务，应用同时配置可接受该服务的 RSF 监听。
        MetConfiguration serverConfiguration = new MetConfiguration();
        serverConfiguration.connector("http").protocol("httpa") //
                .contextPath("/orders").option("rejectService", true);
        serverConfiguration.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfiguration = new MetConfiguration();
        clientConfiguration.connector("tcp").protocol("rsf");
        try (MetContext server = serverConfiguration.buildContext(); //
             MetContext client = clientConfiguration.buildContext()) {
            MetBindInfo<HttpEchoService> provider = server.publisher() //
                    .rsfService(HttpEchoService.class, value -> "remote:" + value) //
                    .contextPathByPrefix("httpa", "/orders").register();
            MetBindInfo<HttpEchoService> consumer = client.publisher() //
                    .rsfService(HttpEchoService.class).register();
            server.start();
            client.start();

            // 不读取协议内部发布状态，从另一实例经 RSF 真实调用同一个服务。
            MetBindInfo<?> published = server.getServiceInfo(provider.getBindID());
            assertNotNull(published);
            assertEquals(provider.getBindID(), published.getBindID());
            HttpEchoService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(consumer);
            assertEquals("remote:value", remote.echo("value"));
        }
    }

    @Test
    public void allProtocolEndpointsMayRejectWithoutRemovingTheLocalImplementation() throws Exception {
        // 当前应用唯一的网络协议拒绝服务，本地实现仍是有效的容器注册。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("httpa") //
                .contextPath("/orders").option("rejectService", true);
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<HttpEchoService> service = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> "local:" + value) //
                    .contextPathByPrefix("httpa", "/orders") //
                    .aliasName("demo", "local-only").register();
            context.start();

            // 公开服务查询、别名和本地调用不依赖网络协议的接纳结果。
            MetBindInfo<?> published = context.getServiceInfo("demo", "local-only");
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
            HttpEchoService local = context.getRsfClient().getRemote(service);
            assertEquals("local:value", local.echo("value"));
        }
    }

    @Test
    public void validationFailureRollsBackTheAttemptAndAllowsCorrectedRepublication() throws Exception {
        // 第一协议先准备成功，第二协议抛异常，区别于正常返回 null 的端点拒绝。
        MetConfiguration configuration = new MetConfiguration();
        MetConfiguration.ConnectorSettings http = configuration.connector("http");
        http.protocol("httpb").contextPath("/other");
        http.protocol("httpa").option("failService", true);
        try (MetContext context = configuration.buildContext()) {
            MetPublisher.ConfigurationBuilder<HttpEchoService> publication = context.publisher() //
                    .rsfService(HttpEchoService.class, value -> "corrected:" + value) //
                    .contextPathByPrefix("httpb", "/other") //
                    .aliasName("demo", "corrected");

            // 真正异常撤销服务和别名，公开查询不能看到半套登记。
            assertThrows(IllegalArgumentException.class, publication::register);
            assertNull(context.getServiceInfo(HttpEchoService.class));
            assertNull(context.getServiceInfo("demo", "corrected"));

            // 修正协议选择后复用同一构建器重新发布，别名和业务调用都恢复可用。
            MetBindInfo<HttpEchoService> service = publication.protocol("httpb").register();
            context.start();
            MetBindInfo<?> published = context.getServiceInfo("demo", "corrected");
            assertNotNull(published);
            assertEquals(service.getBindID(), published.getBindID());
            HttpEchoService local = context.getRsfClient().getRemote(service);
            assertEquals("corrected:value", local.echo("value"));
        }
    }
}
