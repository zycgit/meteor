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

/** WSS 复用 HTTP 连接器的正式 TLS 配置与严格信任校验。 */
public class WebSocketTlsBootstrapTest {
    @Test(timeout = 30000)
    public void trustedServerCertificateAllowsTextRpcOverWss() throws Exception {
        // 单向 TLS 只配置服务端身份；客户端只有独立测试 CA 信任库。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.tlsServer(listener, 1, "NONE");
        WebSocketConfigurations.websocket(listener, 1, false);
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 1);
        WebSocketConfigurations.tlsClient(sender, 1, null);
        WebSocketConfigurations.websocket(sender, 1, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布与订阅仍使用业务接口，不通过裸帧回显替代 RPC。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // TLS 主机名和 CA 校验成功后才能升级并执行服务。
            WebSocketSample remote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(service);
            assertEquals("server:单向TLS🙂", remote.echo("单向TLS🙂"));
            assertEquals(1, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void requiredClientCertificateAllowsBinaryRpcOverWss() throws Exception {
        // REQUIRE 验证双方独立身份，客户端与服务器分别使用 clientAuth/serverAuth 证书。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.tlsServer(listener, 1, "REQUIRE");
        WebSocketConfigurations.websocket(listener, 1, true);
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 1);
        WebSocketConfigurations.tlsClient(sender, 1, "client");
        WebSocketConfigurations.websocket(sender, 1, true);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // TLS 和 WS 升级均通过配置的正式连接器发生。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // 真实代理调用检查 mTLS 后的二进制消息与参数类型。
            byte[] value = { -1, 0, -128, 127 };
            WebSocketSample remote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(service);
            assertArrayEquals(value, remote.binary(value));
            assertEquals(1, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void http2ExtendedConnectUsesTlsWithAlpn() throws Exception {
        // ALPN 选择 h2，随后通过 RFC 8441 extended CONNECT 建立文本通道。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 2).bind("127.0.0.1", 0);
        WebSocketConfigurations.tlsServer(listener, 2, "NONE");
        WebSocketConfigurations.websocket(listener, 2, false);
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 2);
        WebSocketConfigurations.tlsClient(sender, 2, null);
        WebSocketConfigurations.websocket(sender, 2, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布与订阅仍使用业务接口，不通过裸帧回显替代 RPC。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // TLS 主机名和 CA 校验成功后才能升级并执行服务。
            WebSocketSample remote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(service);
            assertEquals("server:单向TLS🙂", remote.echo("单向TLS🙂"));
            assertEquals(1, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void http2ExtendedConnectRequiresMutualTls() throws Exception {
        // 同一 TLS 连接协商 h2 并验证客户端证书，再建立二进制 WS stream。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 2).bind("127.0.0.1", 0);
        WebSocketConfigurations.tlsServer(listener, 2, "REQUIRE");
        WebSocketConfigurations.websocket(listener, 2, true);
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 2);
        WebSocketConfigurations.tlsClient(sender, 2, "client");
        WebSocketConfigurations.websocket(sender, 2, true);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // TLS 和 WS 升级均通过配置的正式连接器发生。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // 真实代理调用检查 mTLS 后的二进制消息与参数类型。
            byte[] value = { -1, 0, -128, 127 };
            WebSocketSample remote = client.getRsfClient(server.bindAddress("jsonws")).getRemote(service);
            assertArrayEquals(value, remote.binary(value));
            assertEquals(1, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void requiredClientCertificateRejectsAnAnonymousWssCaller() throws Exception {
        // 服务端要求身份，客户端只配置 CA，不提供任何客户端证书。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.tlsServer(listener, 1, "REQUIRE");
        WebSocketConfigurations.websocket(listener, 1, false);
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 1);
        WebSocketConfigurations.tlsClient(sender, 1, null);
        WebSocketConfigurations.websocket(sender, 1, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 真实服务已发布，保证失败由身份验证触发。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // 认证失败应终结 Caller，而不是进入业务或耗尽业务超时。
            MetClient remote = client.getRsfClient(server.bindAddress("jsonws"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "anonymous" };
            MetFuture call = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, implementation.calls());
        }
    }

    @Test(timeout = 30000)
    public void requiredClientCertificateRejectsTheOtherTestCa() throws Exception {
        // 客户端证书来自另一套 CA；不允许用信任全部证书规避该失败。
        MetConfiguration incoming = new MetConfiguration();
        MetConfiguration outgoing = new MetConfiguration();
        ConnectorSettings listener = WebSocketConfigurations.http(incoming, 1).bind("127.0.0.1", 0);
        WebSocketConfigurations.tlsServer(listener, 1, "REQUIRE");
        WebSocketConfigurations.websocket(listener, 1, false);
        ConnectorSettings sender = WebSocketConfigurations.http(outgoing, 1);
        WebSocketConfigurations.tlsClient(sender, 1, "untrusted-client");
        WebSocketConfigurations.websocket(sender, 1, false);
        WebSocketSampleService implementation = new WebSocketSampleService();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 订阅端具备正确服务信息，唯一错误是客户端证书信任关系。
            server.start();
            client.start();
            server.publisher().rsfService(WebSocketSample.class, implementation) //
                    .protocol("jsonws").serialize("Json").register();
            MetBindInfo<WebSocketSample> service = client.publisher().rsfService(WebSocketSample.class) //
                    .protocol("jsonws").serialize("Json").register();

            // 认证阶段拒绝连接，公开调用方收到失败且业务没有执行。
            MetClient remote = client.getRsfClient(server.bindAddress("jsonws"));
            Class<?>[] parameterTypes = { String.class };
            Object[] arguments = { "untrusted" };
            MetFuture call = remote.asyncInvoke(service, "echo", parameterTypes, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, implementation.calls());
        }
    }
}
