/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.websocket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.scenario.rpc.websocket.example.WebSocketConfigurations;
import net.hasor.meteor.scenario.rpc.websocket.example.WebSocketSample;
import net.hasor.meteor.scenario.rpc.websocket.example.WebSocketSampleService;
import org.junit.Test;
import static org.junit.Assert.*;

/** 从公开 bootstrap、发布、订阅和代理调用验证 WebSocket 应用协议。 */
public class WebSocketBootstrapTest {
    @Test(timeout = 30000)
    public void textFramesCarryRealJsonRpcWithUnicode() throws Exception {
        // 两端明确选择文本消息，接收不同帧类型会使示例协议拒绝请求。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 1, false);
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 1), 1, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 服务仅在服务端有实现，订阅端必须经过网络与真实 Dispatcher。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // 公开代理返回业务结果，并观察服务端真正执行一次。
            WebSocketSample remote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(service);
            assertEquals("server:中文🙂", remote.echo("中文🙂"));
            assertEquals(1, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void binaryFramesPreserveTypedByteArrayArgumentsAndResults() throws Exception {
        // JSON 信封放入二进制消息，参数仍按公开方法签名还原为 byte[]。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 1, true);
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 1), 1, true);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 分别发布与订阅普通 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // 非 UTF-8 字节内容必须原样往返，不能依赖字符串转换。
            byte[] value = { 0, 1, 127, -128, -1, 13, 10 };
            WebSocketSample remote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(service);
            assertArrayEquals(value, remote.binary(value));
            assertEquals(1, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void websocketAndOrdinaryHttpShareOneListenerWithSeparateContexts() throws Exception {
        // 相同 HTTP 连接器在 /ws 升级，在 /http 保留普通 HTTP 应用协议。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 1, false);
        listener.protocol("wshttp").contextPath("/http");
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 1);
        WebSocketConfigurations.websocket(sender, 1, false);
        sender.protocol("wshttp").contextPath("/http");
        WebSocketSampleService websocket = new WebSocketSampleService();
        WebSocketSampleService ordinary = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两个独立服务分别限制在自己的协议中，避免默认全协议暴露。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, websocket).name("websocket") //
                    .protocol("jsonws").serialize("Json").register();
            server.publisher().rsfService(WebSocketSample.class, ordinary) //
                    .group("http").name("http.echo").version("1").protocol("wshttp").serialize("Java").register();
            MetBindInfo<WebSocketSample> wsService = client.publisher().rsfService(WebSocketSample.class) //
                    .name("websocket").protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> httpService = client.publisher().rsfService(WebSocketSample.class) //
                    .group("http").name("http.echo").version("1").protocol("wshttp").serialize("Java").register();

            // 同一个监听端口同时承载升级消息与普通 HTTP，实际调用落入各自服务。
            assertEquals(server.bindAddress("jsonws").getPort(), server.bindAddress("wshttp").getPort());
            WebSocketSample wsRemote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(wsService);
            WebSocketSample httpRemote = client.getRsfClient(server.bindAddress("wshttp")).getRemote(httpService);
            assertEquals("server:ws", wsRemote.echo("ws"));
            assertEquals("server:http", httpRemote.echo("http"));
            assertEquals(1, websocket.calls());
            assertEquals(1, ordinary.calls());
        }
    }

    @Test(timeout = 30000)
    public void http2WebSocketAndOrdinaryHttpShareOneListenerWithSeparateContexts() throws Exception {
        // 相同 HTTP 连接器在 /ws 升级，在 /http 保留普通 HTTP 应用协议。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 2).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 2, false);
        listener.protocol("wshttp").contextPath("/http");
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 2);
        WebSocketConfigurations.websocket(sender, 2, false);
        sender.protocol("wshttp").contextPath("/http");
        WebSocketSampleService websocket = new WebSocketSampleService();
        WebSocketSampleService ordinary = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两个独立服务分别限制在自己的协议中，避免默认全协议暴露。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, websocket).name("websocket") //
                    .protocol("jsonws").serialize("Json").register();
            server.publisher().rsfService(WebSocketSample.class, ordinary) //
                    .group("http").name("http.echo").version("1").protocol("wshttp").serialize("Java").register();
            MetBindInfo<WebSocketSample> wsService = client.publisher().rsfService(WebSocketSample.class) //
                    .name("websocket").protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> httpService = client.publisher().rsfService(WebSocketSample.class) //
                    .group("http").name("http.echo").version("1").protocol("wshttp").serialize("Java").register();

            // 同一个监听端口同时承载升级消息与普通 HTTP，实际调用落入各自服务。
            assertEquals(server.bindAddress("jsonws").getPort(), server.bindAddress("wshttp").getPort());
            WebSocketSample wsRemote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(wsService);
            WebSocketSample httpRemote = client.getRsfClient(server.bindAddress("wshttp")).getRemote(httpService);
            assertEquals("server:ws", wsRemote.echo("ws"));
            assertEquals("server:http", httpRemote.echo("http"));
            assertEquals(1, websocket.calls());
            assertEquals(1, ordinary.calls());
        }
    }

    @Test(timeout = 30000)
    public void websocketHandshakeCannotUpgradeTheOrdinaryHttpContext() throws Exception {
        // 只有 /ws 声明升级；客户端故意向 /http 发送 WebSocket 握手。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 1, false);
        listener.protocol("wshttp").contextPath("/http");
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 1);
        WebSocketConfigurations.websocket(sender, 1, false).option("requestPath", "/http");
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 真实服务已发布，失败必须发生在升级边界，不能进入业务方法。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // Caller 得到明确连接失败，不能等到业务超时后才结束。
            MetClient remote = client.getRsfClient(server.bindAddress("jsonws"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "blocked" };
            MetFuture call = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, implementation.calls());
        }
    }
}
