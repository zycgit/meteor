/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.bootstrap;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.protocol.hprose.scenario.example.TextService;
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

            // 启动后仍没有绑定地址或入站监听协议。
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            assertFalse(endpoint.bindEnabled());
            assertNull(context.getSettings().getBindAddressSet("rsf"));
            assertNull(context.bindAddress("rsf"));
            assertTrue(context.runProtocols().isEmpty());
        }
    }

    @Test
    public void portZeroOpensAnEphemeralListenerOnlyAtStart() throws Exception {
        // 配置动态端口，构建上下文本身不应开启监听。
        MetConfiguration source = new MetConfiguration();
        source.connector("tcp").bind("127.0.0.1", 0).protocol("rsf");
        try (MetContext context = source.buildContext()) {
            assertNull(context.bindAddress("rsf"));

            // 显式启动后才分配可用端口。
            context.start();
            assertTrue(context.bindAddress("rsf").getPort() > 0);
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
            TextService remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            assertEquals("remote:value", remote.echo("value"));
            assertNull(client.bindAddress("rsf"));
            assertNull(client.getSettings().getBindAddressSet("rsf"));
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
            TextService remote = client.getRsfClient(server.bindAddress("hprose")).getRemote(service);
            assertEquals("remote:value", remote.echo("value"));
            assertNull(client.bindAddress("hprose"));
            assertNull(client.getSettings().getBindAddressSet("hprose"));
        }
    }
}
