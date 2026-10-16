/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.rpc.websocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.example.rpc.websocket.WebSocketConfigurations;
import net.hasor.example.rpc.websocket.WebSocketSample;
import net.hasor.example.rpc.websocket.WebSocketSampleService;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import static org.junit.Assert.*;

/** 取消、断开和握手错误均从公开调用方观察，不把业务超时算作连接失败。 */
public class WebSocketLifecycleTest {
    @Test(timeout = 15000)
    public void closingClientReleasesAnUpgradeWaitingForHttp2Settings() throws Exception {
        // 独立 TCP 对端接收 HTTP/2 前言，但故意不回复任何 SETTINGS。
        MetConfiguration outgoing = new MetConfiguration();
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 2), 2, false);
        try (ServerSocket listener = new ServerSocket(0); //
             MetContext client = outgoing.buildContext()) {
            listener.setSoTimeout(5000);
            client.start();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();
            InterAddress address = new InterAddress("jsonws", "127.0.0.1", listener.getLocalPort(), "default");
            MetClient remote = client.getRsfClient(address);
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "pending" };
            MetFuture call = remote.asyncInvoke(service, "echo", parameterTypes, arguments);

            // 前言已物理写出，CONNECT 仍等待远端协商；上下文关闭必须释放该等待。
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                byte[] preface = peer.getInputStream().readNBytes(24);
                assertEquals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", new String(preface, StandardCharsets.US_ASCII));
                assertFalse(call.isDone());
                client.close();
                assertTrue(call.isDone());
                ThrowingRunnable result = () -> call.get(3, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, result);
                assertFalse(failure.getCause() instanceof MetTimeoutException);
            }
        }
    }

    @Test(timeout = 30000)
    public void cancellingOneRpcLeavesTheWebSocketUsable() throws Exception {
        // 一个业务方法受闩锁控制，其他方法继续使用同一个升级通道。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 1, false);
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 1), 1, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布和订阅的生命周期留在场景内。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonws"));

            // 请求确实进入 Dispatcher 后再取消；后续调用仍能复用该会话。
            try {
                MetFuture blocked = remote.asyncInvoke(service, "block", new Class<?>[0], new Object[0]);
                assertTrue(implementation.entered().await(5, TimeUnit.SECONDS));
                assertTrue(blocked.cancel());
                assertTrue(blocked.isDone());
                assertTrue(blocked.isCancelled());
                assertNull(blocked.getResult());
                assertEquals("server:still-open", remote.getRemote(service).echo("still-open"));
                assertEquals(2, implementation.calls());
            } finally {
                implementation.release();
            }
            assertTrue(implementation.completed().await(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 30000)
    public void closingTheServerFailsAnInFlightWebSocketRpc() throws Exception {
        // 让服务保持执行中，观察正式上下文关闭带来的连接终止。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.websocket(listener, 1, false);
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 1), 1, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 客户端只订阅，没有可绕过网络的本地服务实现。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonws"));

            // 关闭端点应主动失败挂起调用，不能留到十秒业务期限。
            try {
                MetFuture blocked = remote.asyncInvoke(service, "block", new Class<?>[0], new Object[0]);
                assertTrue(implementation.entered().await(5, TimeUnit.SECONDS));
                server.close();
                ThrowingRunnable result = () -> blocked.get(5, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, result);
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
            } finally {
                implementation.release();
            }
        }
    }

    @Test(timeout = 30000)
    public void rejectedHttpUpgradeFailsThePublicCallerWithoutDispatch() throws Exception {
        // 独立 HTTP 对端拒绝升级；Meteor 一侧仍从正式订阅和 Caller 发起请求。
        MetConfiguration outgoing = new MetConfiguration();
        WebSocketConfigurations.websocket(WebSocketConfigurations.http(outgoing, 1), 1, false);
        try (ServerSocket listener = new ServerSocket(0); //
             MetContext client = outgoing.buildContext()) {
            listener.setSoTimeout(5000);
            client.start();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();
            InterAddress address = new InterAddress("jsonws", "127.0.0.1", listener.getLocalPort(), "default");
            MetClient remote = client.getRsfClient(address);
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "rejected" };
            MetFuture call = remote.asyncInvoke(service, "echo", parameterTypes, arguments);

            // 对端确认收到标准握手，再返回明确的 HTTP 403。
            try (Socket peer = listener.accept()) {
                peer.setSoTimeout(5000);
                String request = this.readHeaders(peer);
                assertTrue(request, request.startsWith("GET /ws HTTP/1.1"));
                String response = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                peer.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                peer.getOutputStream().flush();
                ThrowingRunnable result = () -> call.get(5, TimeUnit.SECONDS);
                ExecutionException failure = assertThrows(ExecutionException.class, result);
                assertNotNull(failure.getCause());
                assertFalse(failure.getCause() instanceof MetTimeoutException);
            }
        }
    }

    private String readHeaders(Socket socket) throws IOException {
        // 原始对端只注入握手失败；不复制任何 WebSocket 帧或 Meteor RPC 实现。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int ending = 0;
        while (bytes.size() < 16384) {
            int value = socket.getInputStream().read();
            if (value < 0) {
                throw new IOException("Peer closed before the HTTP handshake headers");
            }
            bytes.write(value);
            ending = ending << 8 | value;
            if (ending == 0x0d0a0d0a) {
                return bytes.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("Handshake headers exceed the example peer limit");
    }
}
