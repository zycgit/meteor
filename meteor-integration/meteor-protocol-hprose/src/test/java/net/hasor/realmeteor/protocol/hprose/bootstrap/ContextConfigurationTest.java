/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.hprose.Signal;
import net.hasor.example.protocol.hprose.TextService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.realmeteor.protocol.hprose.support.BootstrapTestSupport;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextConfigurationTest extends BootstrapTestSupport {

    @Test
    public void explicitOutboundEndpointReplacesDefaultListener() throws Exception {
        // 显式选择仅用于主动连接的 TCP 端点。
        MetConfiguration source = new MetConfiguration();
        source.connector("tcp").protocol("rsf");
        try (MetContext context = source.buildContext()) {
            context.start();

            // 启动后仍没有绑定地址，预编译的出口协议计划可以被查询。
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            assertFalse(endpoint.bindEnabled());
            assertNull(context.getListenAddress("rsf"));
            assertEquals(Collections.singleton("rsf"), context.runProtocols());
        }
    }

    @Test
    public void portZeroOpensAnEphemeralListenerOnlyAtStart() throws Exception {
        // 配置动态端口，构建上下文本身不应开启监听。
        MetConfiguration source = new MetConfiguration();
        source.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext context = source.buildContext()) {
            assertNull(context.getListenAddress("rsf"));

            // 显式启动后才分配可用端口。
            context.start();
            assertTrue(context.getListenAddress("rsf").getPort() > 0);
        }
    }

    @Test
    public void tcpClientInvokesWithoutOpeningAListener() throws Exception {
        // 服务端监听、客户端仅配置出口，二者使用 RSF 的 TCP 通道。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("tcp").protocol("rsf");
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            // 注册服务与消费声明，再启动服务端和客户端。
            server.publisher().rsfService(TextService.class, value -> "remote:" + value).register();
            MetBindInfo<TextService> service = client.publisher().rsfService(TextService.class).register();
            server.start();
            client.start();

            // 指定服务端地址完成远程调用，客户端仍不创建任何监听。
            TextService remote = client.getRsfClient(server.getListenAddress("rsf")).getRemote(service);
            assertEquals("remote:value", remote.echo("value"));
            assertNull(client.getListenAddress("rsf"));
        }
    }

    @Test
    public void httpClientInvokesWithoutOpeningAListener() throws Exception {
        // 服务端监听、客户端仅配置出口，二者使用相同协议路径。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/echo");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("http").protocol("hprose").contextPath("/echo");
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            // 注册服务与消费声明，再启动服务端和客户端。
            server.publisher().rsfService(TextService.class, value -> "remote:" + value).register();
            MetBindInfo<TextService> service = client.publisher().rsfService(TextService.class).register();
            server.start();
            client.start();

            // 指定服务端地址完成远程调用，客户端仍不创建任何监听。
            TextService remote = client.getRsfClient(server.getListenAddress("hprose")).getRemote(service);
            assertEquals("remote:value", remote.echo("value"));
            assertNull(client.getListenAddress("hprose"));
        }
    }

    @Test
    public void hproseMessageCompletesAfterRemoteAdmission() throws Exception {
        // 通过公开配置入口准备 Hprose 服务端和仅出站客户端。
        MetConfiguration serverConfig = new MetConfiguration();
        serverConfig.connector("http").bind("127.0.0.1", 0).protocol("hprose").contextPath("/signal");
        MetConfiguration clientConfig = new MetConfiguration();
        clientConfig.connector("http").protocol("hprose").contextPath("/signal");
        try (MetContext server = serverConfig.buildContext(); //
             MetContext client = clientConfig.buildContext()) {
            server.start();
            client.start();

            // 方法级 @MetMessage 决定两端消息模式，处理方法保持阻塞以区分确认和执行完成。
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch finished = new CountDownLatch(1);
            Signal handler = value -> {
                entered.countDown();
                assertTrue("Handler was not released", release.await(10, TimeUnit.SECONDS));
                finished.countDown();
            };
            server.publisher().rsfService(Signal.class, handler).register();
            MetBindInfo<Signal> service = client.publisher().rsfService(Signal.class).register();

            try {
                // Hprose 成功确认结束调用，此时服务实现仍在等待放行。
                MetFuture sent = client.getRsfClient(server.getListenAddress("hprose")) //
                        .asyncInvoke(service, "accept", new Class<?>[] { String.class }, new Object[] { "message" });
                sent.getData(5, TimeUnit.SECONDS);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(1, finished.getCount());
            } finally {
                release.countDown();
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
    }
}
