/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.oncrpc;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.oncrpc.OncRpcDemoCodecs;
import net.hasor.example.protocol.oncrpc.OncRpcDemoService;
import net.hasor.example.protocol.oncrpc.OncRpcDemoServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过公开启动入口验证 ONC RPC 的 TLS 与 DTLS 传输；不声明 RPC-with-TLS 扩展。 */
public class OncRpcSecureScenarioTest {
    @Test(timeout = 30000)
    public void tcpTls12ServerAuthentication() throws Exception {
        // 固定双方的安全协议，验证 TCP TLS 1.2 服务端认证 下的真实 XDR 往返。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 目标地址选择安全挂载，业务调用始终通过 Meteor 客户端。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("oncrpc-tls", address.getSchema());
            String message = "ONC RPC TCP TLS 1.2 server authentication";
            assertEquals(message, remote.echo(message));
        }
    }

    @Test(timeout = 30000)
    public void tcpTls12MutualAuthentication() throws Exception {
        // 固定双方的安全协议，验证 TCP TLS 1.2 双向认证 下的真实 XDR 往返。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp("client", "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 目标地址选择安全挂载，业务调用始终通过 Meteor 客户端。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("oncrpc-tls", address.getSchema());
            String message = "ONC RPC TCP TLS 1.2 mutual authentication";
            assertEquals(message, remote.echo(message));
        }
    }

    @Test(timeout = 30000)
    public void tcpTls13ServerAuthentication() throws Exception {
        // 固定双方的安全协议，验证 TCP TLS 1.3 服务端认证 下的真实 XDR 往返。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 目标地址选择安全挂载，业务调用始终通过 Meteor 客户端。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("oncrpc-tls", address.getSchema());
            String message = "ONC RPC TCP TLS 1.3 server authentication";
            assertEquals(message, remote.echo(message));
        }
    }

    @Test(timeout = 30000)
    public void tcpTls13MutualAuthentication() throws Exception {
        // 固定双方的安全协议，验证 TCP TLS 1.3 双向认证 下的真实 XDR 往返。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingTcp("client", "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 目标地址选择安全挂载，业务调用始终通过 Meteor 客户端。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("oncrpc-tls", address.getSchema());
            String message = "ONC RPC TCP TLS 1.3 mutual authentication";
            assertEquals(message, remote.echo(message));
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsMissingClientCertificate() throws Exception {
        // 服务器要求客户端证书，客户端没有提供身份。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 安全连接失败要及时交给调用方，不能用业务超时代替认证失败。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsUntrustedClientCertificate() throws Exception {
        // 客户端证书由另一 CA 签发，服务器不接受该身份。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp("untrusted-client", "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 安全连接失败要及时交给调用方，不能用业务超时代替认证失败。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsUntrustedServerCertificate() throws Exception {
        // 客户端的信任集不包含服务端证书的签发 CA。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "untrusted-trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 安全连接失败要及时交给调用方，不能用业务超时代替认证失败。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsWrongServerHostname() throws Exception {
        // 证书链可信，但证书 SAN 没有实际访问的 127.0.0.1。
        MetConfiguration incoming = this.incomingTcp("wrong-host-server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 安全连接失败要及时交给调用方，不能用业务超时代替认证失败。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsIncompatibleTlsVersion() throws Exception {
        // 服务端只允许 TLS 1.2，客户端只允许 TLS 1.3。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 安全连接失败要及时交给调用方，不能用业务超时代替认证失败。
            InterAddress address = server.getListenAddress("oncrpc", "oncrpc-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void plaintextAndSecureTcpMountsRemainIndependent() throws Exception {
        // 两个 TCP 端点分别提供明文与 TLS，不复制协议解析器。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.3,TLSv1.2");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("oncrpc");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3,TLSv1.2");
        outgoing.connector("tcp").protocol("oncrpc");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc") //
                    .bindResource("oncrpc", OncRpcDemoCodecs.resource()).register();

            // 同一服务通过地址选择不同挂载，两个监听端口各自完成往返。
            InterAddress plainAddress = server.getListenAddress("oncrpc", "oncrpc");
            InterAddress secureAddress = server.getListenAddress("oncrpc", "oncrpc-tls");
            OncRpcDemoService plain = client.getRsfClient(plainAddress).getRemote(service);
            OncRpcDemoService secure = client.getRsfClient(secureAddress).getRemote(service);
            assertNotEquals(plainAddress.getPort(), secureAddress.getPort());
            assertEquals("ONC RPC plain", plain.echo("ONC RPC plain"));
            assertEquals("ONC RPC TLS", secure.echo("ONC RPC TLS"));
        }
    }

    @Test(timeout = 30000)
    public void udpDtls12ServerAuthentication() throws Exception {
        // UDP 传输在 XDR 报文之外提供 DTLS 1.2 服务端认证，保留一个数据报一条消息。
        MetConfiguration incoming = this.incomingUdp("NONE");
        MetConfiguration outgoing = this.outgoingUdp(null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc-udp") //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc-udp") //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 小报文在同一安全 peer 上完成请求和响应，不采用 TCP record 标记。
            InterAddress address = server.getListenAddress("oncrpc-udp", "oncrpc-dtls");
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("oncrpc-dtls", address.getSchema());
            String message = "ONC RPC DTLS 1.2 server authentication";
            assertEquals(message, remote.echo(message));
        }
    }

    @Test(timeout = 30000)
    public void udpDtls12MutualAuthentication() throws Exception {
        // UDP 传输在 XDR 报文之外提供 DTLS 1.2 双向认证，保留一个数据报一条消息。
        MetConfiguration incoming = this.incomingUdp("REQUIRE");
        MetConfiguration outgoing = this.outgoingUdp("client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 容器启动后才发布与订阅；两端使用同一份本地 XDR 契约。
            server.start();
            client.start();
            server.publisher().rsfService(OncRpcDemoService.class, new OncRpcDemoServiceImpl()) //
                    .protocol("oncrpc-udp") //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();
            MetBindInfo<OncRpcDemoService> service = client.publisher().rsfService(OncRpcDemoService.class) //
                    .protocol("oncrpc-udp") //
                    .bindResource("oncrpc-udp", OncRpcDemoCodecs.resource()).register();

            // 小报文在同一安全 peer 上完成请求和响应，不采用 TCP record 标记。
            InterAddress address = server.getListenAddress("oncrpc-udp", "oncrpc-dtls");
            OncRpcDemoService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("oncrpc-dtls", address.getSchema());
            String message = "ONC RPC DTLS 1.2 mutual authentication";
            assertEquals(message, remote.echo(message));
        }
    }

    private MetConfiguration incomingTcp(String identity, String clientAuth, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc").setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp").bind("127.0.0.1", 0);
        OncRpcTlsUtils.server(connector, identity, clientAuth, protocols);
        connector.protocol("oncrpc").addressScheme("oncrpc-tls");
        return configuration;
    }

    private MetConfiguration outgoingTcp(String identity, String trust, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc").setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp");
        OncRpcTlsUtils.client(connector, identity, trust, protocols);
        connector.protocol("oncrpc").addressScheme("oncrpc-tls");
        return configuration;
    }

    private MetConfiguration incomingUdp(String clientAuth) throws IOException {
        // UDP 端点使用实际可用的本地端口，DTLS 握手由既有 UDP 传输实现完成。
        int port;
        try (DatagramSocket reservation = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            port = reservation.getLocalPort();
        }
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("udp").bind("127.0.0.1", port);
        OncRpcTlsUtils.server(connector, "server", clientAuth, "DTLSv1.2");
        connector.protocol("oncrpc-udp").addressScheme("oncrpc-dtls");
        return configuration;
    }

    private MetConfiguration outgoingUdp(String identity) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("udp");
        OncRpcTlsUtils.client(connector, identity, "trust", "DTLSv1.2");
        connector.protocol("oncrpc-udp").addressScheme("oncrpc-dtls");
        return configuration;
    }
}
