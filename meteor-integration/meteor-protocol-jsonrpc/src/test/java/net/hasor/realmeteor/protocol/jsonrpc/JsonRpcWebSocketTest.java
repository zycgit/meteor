/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoService;
import net.hasor.example.protocol.jsonrpc.JsonRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** WebSocket 与 WSS 复用 HTTP 挂载，实际业务仍经过公开 Caller 和 Dispatcher。 */
public class JsonRpcWebSocketTest {
    @Test(timeout = 20000)
    public void http1UpgradeCarriesPublishedService() throws Exception {
        // 在 HTTP/1 端点上声明升级挂载，两端使用相同 context 与公开地址别名。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        listener.protocol("jsonrpc").addressScheme("ws").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 1);
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 1);
        sender.protocol("jsonrpc").addressScheme("ws").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 1);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器再发布与订阅，协议选择不改变 Java 服务接口。
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();

            // 真正的服务结果证明握手、升级、文本解码和 RPC 派发全部完成。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("WebSocket 中文🙂", remote.echo("WebSocket 中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void http2ExtendedConnectCarriesPublishedService() throws Exception {
        // 在 HTTP/2 端点上声明升级挂载，两端使用相同 context 与公开地址别名。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        listener.protocol("jsonrpc").addressScheme("ws").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 2);
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 2);
        sender.protocol("jsonrpc").addressScheme("ws").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 2);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器再发布与订阅，协议选择不改变 Java 服务接口。
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();

            // 真正的服务结果证明握手、升级、文本解码和 RPC 派发全部完成。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("WebSocket 中文🙂", remote.echo("WebSocket 中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void wssHttp1AuthenticatesServer() throws Exception {
        // 在 HTTP/1 端点上声明升级挂载，两端使用相同 context 与公开地址别名。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        JsonRpcTlsUtils.serverTls(listener, 1, "NONE");
        listener.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 1);
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 1);
        JsonRpcTlsUtils.clientTls(sender, 1, null);
        sender.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 1);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器再发布与订阅，协议选择不改变 Java 服务接口。
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();

            // 真正的服务结果证明握手、升级、文本解码和 RPC 派发全部完成。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("WebSocket 中文🙂", remote.echo("WebSocket 中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void wssHttp2NegotiatesAlpn() throws Exception {
        // 在 HTTP/2 端点上声明升级挂载，两端使用相同 context 与公开地址别名。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        JsonRpcTlsUtils.serverTls(listener, 2, "NONE");
        listener.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 2);
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 2);
        JsonRpcTlsUtils.clientTls(sender, 2, null);
        sender.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 2);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器再发布与订阅，协议选择不改变 Java 服务接口。
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();

            // 真正的服务结果证明握手、升级、文本解码和 RPC 派发全部完成。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("WebSocket 中文🙂", remote.echo("WebSocket 中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void wssHttp1AuthenticatesBothPeers() throws Exception {
        // 在 HTTP/1 端点上声明升级挂载，两端使用相同 context 与公开地址别名。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        JsonRpcTlsUtils.serverTls(listener, 1, "REQUIRE");
        listener.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 1);
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 1);
        JsonRpcTlsUtils.clientTls(sender, 1, "client");
        sender.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 1);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器再发布与订阅，协议选择不改变 Java 服务接口。
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();

            // 真正的服务结果证明握手、升级、文本解码和 RPC 派发全部完成。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("WebSocket 中文🙂", remote.echo("WebSocket 中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void wssHttp2AuthenticatesBothPeers() throws Exception {
        // 在 HTTP/2 端点上声明升级挂载，两端使用相同 context 与公开地址别名。
        MetConfiguration incoming = new MetConfiguration();
        ConnectorSettings listener = incoming.connector("http").bind("127.0.0.1", 0);
        JsonRpcTlsUtils.serverTls(listener, 2, "REQUIRE");
        listener.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 2);
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        ConnectorSettings sender = outgoing.connector("http").option("httpVersion", 2);
        JsonRpcTlsUtils.clientTls(sender, 2, "client");
        sender.protocol("jsonrpc").addressScheme("wss").contextPath("/ws") //
                .option("upgrade", "websocket").option("httpVersion", 2);

        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器再发布与订阅，协议选择不改变 Java 服务接口。
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();

            // 真正的服务结果证明握手、升级、文本解码和 RPC 派发全部完成。
            JsonRpcDemoService remote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(service);
            assertEquals("WebSocket 中文🙂", remote.echo("WebSocket 中文🙂"));
        }
    }

    @Test(timeout = 20000)
    public void http1WebSocketAndOrdinaryHttpShareOnePort() throws Exception {
        // 一个 JSON-RPC 挂载同时声明普通 HTTP 和 WebSocket 所使用的目录。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc") //
                .contextPath("/rpc").addContextPath("/ws").option("upgrade", "websocket");
        MetConfiguration ordinaryConfig = new MetConfiguration().setRequestTimeout(5000);
        ordinaryConfig.connector("http").option("httpVersion", 1) //
                .protocol("jsonrpc").contextPath("/rpc");
        MetConfiguration upgradedConfig = new MetConfiguration().setRequestTimeout(5000);
        upgradedConfig.connector("http").option("httpVersion", 1).protocol("jsonrpc") //
                .contextPath("/ws").option("upgrade", "websocket").option("httpVersion", 1);
        try (MetContext server = incoming.buildContext(); //
             MetContext ordinaryClient = ordinaryConfig.buildContext(); //
             MetContext upgradedClient = upgradedConfig.buildContext()) {
            // 服务明确发布到两个目录，两个消费端各自选择普通请求或升级通道。
            server.start();
            ordinaryClient.start();
            upgradedClient.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").contextPathByPrefix("jsonrpc", "/rpc", "/ws").register();
            MetBindInfo<JsonRpcDemoService> ordinary = ordinaryClient.publisher() //
                    .rsfService(JsonRpcDemoService.class).aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> upgraded = upgradedClient.publisher() //
                    .rsfService(JsonRpcDemoService.class).aliasName("jsonrpc", "echo").register();

            // 两种客户端使用唯一的公开地址，证明端口复用并未要求额外监听器。
            InterAddress address = server.getListenAddress("jsonrpc");
            assertEquals(1, server.getListenAddresses("jsonrpc").size());
            JsonRpcDemoService http = ordinaryClient.getRsfClient(address).getRemote(ordinary);
            JsonRpcDemoService websocket = upgradedClient.getRsfClient(address).getRemote(upgraded);
            assertEquals("ordinary", http.echo("ordinary"));
            assertEquals("upgraded", websocket.echo("upgraded"));
        }
    }

    @Test(timeout = 20000)
    public void http2WebSocketAndOrdinaryHttpShareOnePort() throws Exception {
        // 一个 JSON-RPC 挂载同时声明普通 HTTP 和 WebSocket 所使用的目录。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc") //
                .contextPath("/rpc").addContextPath("/ws").option("upgrade", "websocket");
        MetConfiguration ordinaryConfig = new MetConfiguration().setRequestTimeout(5000);
        ordinaryConfig.connector("http").option("httpVersion", 2) //
                .protocol("jsonrpc").contextPath("/rpc");
        MetConfiguration upgradedConfig = new MetConfiguration().setRequestTimeout(5000);
        upgradedConfig.connector("http").option("httpVersion", 2).protocol("jsonrpc") //
                .contextPath("/ws").option("upgrade", "websocket").option("httpVersion", 2);
        try (MetContext server = incoming.buildContext(); //
             MetContext ordinaryClient = ordinaryConfig.buildContext(); //
             MetContext upgradedClient = upgradedConfig.buildContext()) {
            // 服务明确发布到两个目录，两个消费端各自选择普通请求或升级通道。
            server.start();
            ordinaryClient.start();
            upgradedClient.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").contextPathByPrefix("jsonrpc", "/rpc", "/ws").register();
            MetBindInfo<JsonRpcDemoService> ordinary = ordinaryClient.publisher() //
                    .rsfService(JsonRpcDemoService.class).aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> upgraded = upgradedClient.publisher() //
                    .rsfService(JsonRpcDemoService.class).aliasName("jsonrpc", "echo").register();

            // 两种客户端使用唯一的公开地址，证明端口复用并未要求额外监听器。
            InterAddress address = server.getListenAddress("jsonrpc");
            assertEquals(1, server.getListenAddresses("jsonrpc").size());
            JsonRpcDemoService http = ordinaryClient.getRsfClient(address).getRemote(ordinary);
            JsonRpcDemoService websocket = upgradedClient.getRsfClient(address).getRemote(upgraded);
            assertEquals("ordinary", http.echo("ordinary"));
            assertEquals("upgraded", websocket.echo("upgraded"));
        }
    }

    @Test(timeout = 20000)
    public void independentJdkWebSocketAuthenticatesTlsServer() throws Exception {
        // 独立 WebSocket 客户端只发送标准 JSON-RPC 文本，不依赖 Meteor 客户端实现。
        MetConfiguration configuration = new MetConfiguration();
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        JsonRpcTlsUtils.serverTls(listener, 1, "NONE");
        listener.protocol("jsonrpc").addressScheme("wss").contextPath("/ws").option("upgrade", "websocket");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();

            // JDK 执行 HTTP/1.1 Upgrade，场景直接观察线上 JSON 响应。
            int port = server.getListenAddress("jsonrpc").getPort();
            URI uri = URI.create("wss://127.0.0.1:" + port + "/ws");
            JsonRpcWebSocketPeer peer = new JsonRpcWebSocketPeer();
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(JsonRpcTlsUtils.clientContext("trust", null)).build();
            WebSocket socket = client.newWebSocketBuilder() //
                    .connectTimeout(Duration.ofSeconds(5)).buildAsync(uri, peer).get(5, TimeUnit.SECONDS);
            try {
                String request = "{\"jsonrpc\":\"2.0\",\"method\":\"echo.echo\",\"params\":[\"JDK🙂\"],\"id\":\"peer\"}";
                socket.sendText(request, true).get(5, TimeUnit.SECONDS);
                JsonNode response = new ObjectMapper().readTree(peer.nextMessage());
                assertEquals("peer", response.path("id").asText());
                assertEquals("JDK🙂", response.path("result").asText());
            } finally {
                socket.abort();
            }
        }
    }

    @Test(timeout = 20000)
    public void notificationCallerCompletesWhileTheBusinessIsStillRunning() throws Exception {
        // 业务方法受闩锁控制；消息模式只能等待写出，不能等待业务执行完成。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("http").protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        JsonRpcCallGate implementation = new JsonRpcCallGate();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));

            // 写入 Future 完成时远端已进入受控业务，仍未到达业务完成点。
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "blocked" };
                MetFuture notification = remote.asyncInvoke(service, "notify", parameterTypes, arguments);
                assertNull(notification.get(5, TimeUnit.SECONDS).getData());
                assertTrue(notification.getRequest().isMessage());
                assertTrue(implementation.awaitEntered());
                assertFalse(implementation.isCompleted());
            } finally {
                implementation.release();
            }
        }
    }

    @Test(timeout = 20000)
    public void concurrentCallsKeepTheirResponseIdsIsolated() throws Exception {
        // 多个请求复用一个 WS 会话，结果必须按照 ID 对应到自己的 Future。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(5000);
        outgoing.connector("http").protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, new JsonRpcDemoServiceImpl()) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));

            // 先提交全部调用，再逐个核对业务值，避免退化成串行往返。
            List<MetFuture> calls = new ArrayList<>();
            Class<?>[] parameterTypes = { String.class };
            for (int index = 0; index < 32; index++) {
                Object[] arguments = { "message-" + index };
                calls.add(remote.asyncInvoke(service, "echo", parameterTypes, arguments));
            }
            for (int index = 0; index < calls.size(); index++) {
                assertEquals("message-" + index, calls.get(index).getData(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 20000)
    public void cancellingOneCallLeavesTheWebSocketUsable() throws Exception {
        // 为阻塞调用和后续调用分别保留工作线程，避免业务队列串行掩盖连接隔离。
        MetConfiguration incoming = new MetConfiguration().setQueueMinPoolSize(2);
        incoming.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(10000);
        outgoing.connector("http").protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        JsonRpcCallGate implementation = new JsonRpcCallGate();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));

            // 确保请求进入 Dispatcher 后再取消，排除未发出请求的伪隔离结果。
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "blocked" };
                MetFuture cancelled = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
                assertTrue(implementation.awaitEntered());
                assertTrue(cancelled.cancel());
                assertTrue(cancelled.isCancelled());
                assertEquals("surviving", remote.getRemote(service).echo("surviving"));
            } finally {
                implementation.release();
            }
        }
    }

    @Test(timeout = 20000)
    public void closingTheServerFailsPendingCallsWithoutWaitingForTimeout() throws Exception {
        // 关闭时一个网络调用仍在业务闩锁内，调用方应直接感知连接终止。
        MetConfiguration incoming = new MetConfiguration();
        incoming.connector("http").bind("127.0.0.1", 0).protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        MetConfiguration outgoing = new MetConfiguration().setRequestTimeout(10000);
        outgoing.connector("http").protocol("jsonrpc") //
                .addressScheme("ws").contextPath("/ws").option("upgrade", "websocket");
        JsonRpcCallGate implementation = new JsonRpcCallGate();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(JsonRpcDemoService.class, implementation) //
                    .aliasName("jsonrpc", "echo").register();
            MetBindInfo<JsonRpcDemoService> service = client.publisher().rsfService(JsonRpcDemoService.class) //
                    .aliasName("jsonrpc", "echo").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));

            // 监听关闭以后已有物理会话终结，业务期限还没有到期。
            try {
                Class<?>[] parameterTypes = { String.class };
                Object[] arguments = { "blocked" };
                MetFuture pending = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
                assertTrue(implementation.awaitEntered());
                server.close();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    pending.get(5, TimeUnit.SECONDS);
                });
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
            } finally {
                implementation.release();
            }
        }
    }
}
