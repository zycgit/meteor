/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.SSLHandshakeException;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.realmeteor.environment.container.ContainerProcess;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.apache.thrift.TException;
import org.junit.Test;
import static org.junit.Assert.*;

/** 官方独立 JVM 与 Meteor 的 TLS、双向证书和 HTTPS 互通。 */
public class ThriftOfficialSecureScenarioTest {
    @Test(timeout = 30000)
    public void officialBinaryClientCallsMeteorWithTlsServerAuthentication() throws Exception {
        // 官方客户端只持有受信 CA，服务端证书由 Meteor 的 TCP 传输提供。
        MetConfiguration configuration = this.tlsServer("binary", "NONE", "TLSv1.3,TLSv1.2");
        String trust = this.storeFile("trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 独立 JVM 使用官方 Binary Client，严格验证证书及访问主机身份。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=tls", //
                    "--protocol=binary", "--framed=true", "--multiplexed=false", "--port=" + port, //
                    "--scenario=echo", "--value=TLS 官方客户端 中文", //
                    "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireResult("TLS 官方客户端 中文");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryClientCallsOfficialServerWithTlsServerAuthentication() throws Exception {
        // 官方独立服务端持有私钥，Meteor 客户端仅加载用于服务端认证的 CA。
        MetConfiguration configuration = this.tlsClient("binary", null, "trust");
        String identity = this.storeFile("server");
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", "--transport=tls", //
                "--protocol=binary", "--framed=true", "--multiplexed=false", "--port=0", //
                "--key-store=" + identity, "--password=meteor-test"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // Meteor 启动后订阅生成服务契约，目标端口来自官方服务端就绪通知。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            InterAddress address = new InterAddress("thrifts", peer.host(), port, "default");

            // 公开 Thrift 客户端通过真实 TLS 连接调用官方 Processor。
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("TLS Meteor 客户端 中文", remote.echo("TLS Meteor 客户端 中文"));
        }
    }

    @Test(timeout = 30000)
    public void officialCompactClientCallsMeteorWithMutualTls() throws Exception {
        // Meteor 同时校验客户端证书；官方 Compact Client 加载独立客户端身份。
        MetConfiguration configuration = this.tlsServer("compact", "REQUIRE", "TLSv1.3,TLSv1.2");
        String identity = this.storeFile("client");
        String trust = this.storeFile("trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 两端完成双向证书认证后再交换正式的 Compact RPC。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=tls", //
                    "--protocol=compact", "--framed=true", "--multiplexed=false", "--port=" + port, //
                    "--scenario=echo", "--value=mTLS 官方客户端 中文", //
                    "--key-store=" + identity, "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireResult("mTLS 官方客户端 中文");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorCompactClientCallsOfficialServerWithMutualTls() throws Exception {
        // 官方服务端要求受信客户端身份，Meteor 加载自己的客户端证书。
        MetConfiguration configuration = this.tlsClient("compact", "client", "trust");
        String identity = this.storeFile("server");
        String trust = this.storeFile("trust");
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", "--transport=tls", //
                "--protocol=compact", "--framed=true", "--multiplexed=false", "--port=0", //
                "--client-auth=true", "--key-store=" + identity, //
                "--trust-store=" + trust, "--password=meteor-test"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 启动后订阅服务，保留 Meteor 对连接和 RPC 生命周期的所有权。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            InterAddress address = new InterAddress("thrifts", peer.host(), port, "default");

            // IDL 代理经双向 TLS 到达官方服务实现，返回原始字符串。
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("mTLS Meteor 客户端 中文", remote.echo("mTLS Meteor 客户端 中文"));
        }
    }

    @Test(timeout = 30000)
    public void officialJsonClientCallsMeteorOverHttps() throws Exception {
        // JSON Thrift 正文保持官方格式，HTTP/1 传输负责 TLS 和消息边界。
        MetConfiguration configuration = this.httpsServer("json", "NONE", "server");
        String trust = this.storeFile("trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();

            // 官方 THttpClient 校验服务端证书并直接请求公开挂载路径。
            int port = server.getListenAddress("thrift-http").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=https", //
                    "--protocol=json", "--multiplexed=false", "--path=/thrift", "--port=" + port, //
                    "--scenario=echo", "--value=HTTPS 官方客户端 中文", //
                    "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireResult("HTTPS 官方客户端 中文");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorJsonClientCallsOfficialServerOverHttps() throws Exception {
        // 官方 JSON Processor 运行在独立 JDK HTTPS 服务端，Meteor 只配出站连接。
        MetConfiguration configuration = this.httpsClient("json", null, "trust");
        String identity = this.storeFile("server");
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", "--transport=https", //
                "--protocol=json", "--multiplexed=false", "--path=/thrift", "--port=0", //
                "--key-store=" + identity, "--password=meteor-test"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 启动与服务订阅在测试方法中可见，调用地址选择 HTTPS 挂载。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();
            InterAddress address = new InterAddress("https", peer.host(), port, "default");

            // 正式 Thrift 客户端完成证书校验，并由官方服务端返回 JSON 结果。
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("HTTPS Meteor 客户端 中文", remote.echo("HTTPS Meteor 客户端 中文"));
        }
    }

    @Test(timeout = 30000)
    public void officialBinaryClientCallsMeteorOverMutualHttps() throws Exception {
        // HTTPS 监听要求客户端证书，服务仍由公开发布的普通业务接口实现。
        MetConfiguration configuration = this.httpsServer("binary", "REQUIRE", "server");
        String identity = this.storeFile("client");
        String trust = this.storeFile("trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();

            // 官方 Binary Client 以客户端证书完成 HTTPS 双向认证。
            int port = server.getListenAddress("thrift-http").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=https", //
                    "--protocol=binary", "--multiplexed=false", "--path=/thrift", "--port=" + port, //
                    "--scenario=echo", "--value=HTTPS mTLS 官方客户端 中文", //
                    "--key-store=" + identity, "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireResult("HTTPS mTLS 官方客户端 中文");
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorBinaryClientCallsOfficialServerOverMutualHttps() throws Exception {
        // 官方 HTTPS 对端要求双向认证，Meteor 同时验证服务端身份并提供自己的身份。
        MetConfiguration configuration = this.httpsClient("binary", "client", "trust");
        String identity = this.storeFile("server");
        String trust = this.storeFile("trust");
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", "--transport=https", //
                "--protocol=binary", "--multiplexed=false", "--path=/thrift", "--port=0", //
                "--client-auth=true", "--key-store=" + identity, //
                "--trust-store=" + trust, "--password=meteor-test"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 启动 Meteor 并订阅官方生成契约，不引入本地服务实现。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();
            InterAddress address = new InterAddress("https", peer.host(), port, "default");

            // 真实 HTTPS 请求经过官方 Processor，双向认证后返回业务结果。
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("HTTPS mTLS Meteor 客户端 中文", remote.echo("HTTPS mTLS Meteor 客户端 中文"));
        }
    }

    @Test(timeout = 30000)
    public void meteorMutualTlsRejectsOfficialClientWithoutCertificate() throws Exception {
        // TLS 1.2 使客户端在握手阶段直接收到缺失证书的拒绝。
        MetConfiguration configuration = this.tlsServer("binary", "REQUIRE", "TLSv1.2");
        String trust = this.storeFile("trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 官方客户端信任服务端，但没有可提供的客户端证书。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=tls", //
                    "--protocol=binary", "--framed=true", "--multiplexed=false", "--port=" + port, //
                    "--scenario=echo", "--value=missing-client-certificate", //
                    "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireFailure("SSLSocketImpl.startHandshake");
                String output = Files.readString(peer.logPath());
                assertTrue(output, output.contains("SSLHandshake"));
                assertFalse(output, output.contains("SocketTimeoutException"));
                assertFalse(output, output.contains("RESULT "));
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorMutualTlsRejectsOfficialClientWithUntrustedCertificate() throws Exception {
        // 客户端确实持有证书，但它不属于 Meteor 服务端配置的 CA 信任集。
        MetConfiguration configuration = this.tlsServer("binary", "REQUIRE", "TLSv1.2");
        String identity = this.storeFile("untrusted-client");
        String trust = this.storeFile("trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 拒绝可表现为握手中断；须保留 JSSE 握手阶段的明确栈证据。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=tls", //
                    "--protocol=binary", "--framed=true", "--multiplexed=false", "--port=" + port, //
                    "--scenario=echo", "--value=untrusted-client-certificate", //
                    "--key-store=" + identity, "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireFailure("SSLSocketImpl.startHandshake");
                String output = Files.readString(peer.logPath());
                assertTrue(output, output.contains("SSLHandshake"));
                assertFalse(output, output.contains("SocketTimeoutException"));
                assertFalse(output, output.contains("RESULT "));
            }
        }
    }

    @Test(timeout = 30000)
    public void officialTlsClientRejectsUntrustedMeteorServerCertificate() throws Exception {
        // 官方客户端只信任另一 CA，不能接受当前 Meteor 服务端的证书。
        MetConfiguration configuration = this.tlsServer("binary", "NONE", "TLSv1.3,TLSv1.2");
        String trust = this.storeFile("untrusted-trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();

            // 证书校验失败必须呈现为官方 JSSE 的握手异常，不能产生业务结果。
            int port = server.getListenAddress("thrift").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=tls", //
                    "--protocol=binary", "--framed=true", "--multiplexed=false", "--port=" + port, //
                    "--scenario=echo", "--value=untrusted-server-certificate", //
                    "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireFailure("SSLHandshakeException");
                String output = Files.readString(peer.logPath());
                assertTrue(output, output.contains("PKIX"));
                assertFalse(output, output.contains("SocketTimeoutException"));
                assertFalse(output, output.contains("RESULT "));
            }
        }
    }

    @Test(timeout = 30000)
    public void officialHttpsClientRejectsMeteorServerWithWrongHostname() throws Exception {
        // 证书的 CA 受信，但证书名称不匹配实际连接的 Runner 容器域名。
        MetConfiguration configuration = this.httpsServer("binary", "NONE", "wrong-host-server");
        String trust = this.storeFile("trust");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();

            // 官方 THttpClient 保持默认 HTTPS 主机名校验，不绕过证书身份检查。
            int port = server.getListenAddress("thrift-http").getPort();
            try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=client", "--transport=https", //
                    "--protocol=binary", "--multiplexed=false", "--path=/thrift", "--port=" + port, //
                    "--scenario=echo", "--value=wrong-server-hostname", //
                    "--trust-store=" + trust, "--password=meteor-test")) {
                peer.requireFailure("SSLHandshakeException");
                String output = Files.readString(peer.logPath());
                assertTrue(output, output.contains("subject alternative"));
                assertFalse(output, output.contains("SocketTimeoutException"));
                assertFalse(output, output.contains("RESULT "));
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorTlsClientRejectsUntrustedOfficialServerCertificate() throws Exception {
        // 官方服务端已明确监听，Meteor 客户端只信任另一 CA。
        MetConfiguration configuration = this.tlsClient("binary", null, "untrusted-trust");
        String identity = this.storeFile("server");
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", "--transport=tls", //
                "--protocol=binary", "--framed=true", "--multiplexed=false", "--port=0", //
                "--key-store=" + identity, "--password=meteor-test"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // 启动后从正式客户端入口发起调用，不能把端口未就绪误判为证书拒绝。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift", "ExtendedInteropService").register();
            InterAddress address = new InterAddress("thrifts", peer.host(), port, "default");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 调用方保留原始证书异常，不接受 RPC 超时作为身份拒绝的证据。
            TException failure = assertThrows(TException.class, () -> remote.echo("untrusted-server"));
            Throwable certificateFailure = failure;
            while (certificateFailure.getCause() != null && !(certificateFailure instanceof SSLHandshakeException)) {
                certificateFailure = certificateFailure.getCause();
            }
            assertTrue(failure.toString(), certificateFailure instanceof SSLHandshakeException);
        }
    }

    @Test(timeout = 30000)
    public void meteorHttpsClientRejectsOfficialServerWithWrongHostname() throws Exception {
        // 官方服务端使用受信 CA 签发但名称错误的证书，Meteor 必须检查访问主机身份。
        MetConfiguration configuration = this.httpsClient("binary", null, "trust");
        String identity = this.storeFile("wrong-host-server");
        try (ThriftPeerProcess peer = ThriftPeerProcess.start("--command=server", "--transport=https", //
                "--protocol=binary", "--multiplexed=false", "--path=/thrift", "--port=0", //
                "--key-store=" + identity, "--password=meteor-test"); //
             MetContext client = configuration.buildContext()) {
            int port = peer.awaitPort();

            // Meteor 启动、订阅后通过公开 HTTPS 地址发起 Thrift 调用。
            client.start();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)) //
                    .aliasName("thrift-http", "ExtendedInteropService").register();
            InterAddress address = new InterAddress("https", peer.host(), port, "default");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);

            // 原始握手异常到达调用方，验证失败发生在证书身份检查而非业务期限。
            TException failure = assertThrows(TException.class, () -> remote.echo("wrong-hostname"));
            Throwable certificateFailure = failure;
            while (certificateFailure.getCause() != null && !(certificateFailure instanceof SSLHandshakeException)) {
                certificateFailure = certificateFailure.getCause();
            }
            assertTrue(failure.toString(), certificateFailure instanceof SSLHandshakeException);
        }
    }

    private MetConfiguration tlsServer(String encoding, String clientAuth, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings listener = configuration.connector("tcp").bind(ContainerProcess.runnerHost(), 0);
        ThriftTlsUtils.serverTls(listener, "server", clientAuth, protocols);
        listener.protocol("thrift").addressScheme("thrifts") //
                .option("encoding", encoding).option("framed", true);
        return configuration;
    }

    private MetConfiguration tlsClient(String encoding, String identity, String trust) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings sender = configuration.connector("tcp");
        ThriftTlsUtils.clientTls(sender, identity, trust, "TLSv1.3,TLSv1.2");
        sender.protocol("thrift").addressScheme("thrifts") //
                .option("encoding", encoding).option("framed", true);
        return configuration;
    }

    private MetConfiguration httpsServer(String encoding, String clientAuth, String identity) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings listener = configuration.connector("http").bind(ContainerProcess.runnerHost(), 0);
        ThriftTlsUtils.serverTls(listener, identity, clientAuth, "TLSv1.3,TLSv1.2");
        ThriftTlsUtils.https(listener, 1);
        listener.protocol("thrift-http").addressScheme("https").contextPath("/thrift") //
                .option("encoding", encoding);
        return configuration;
    }

    private MetConfiguration httpsClient(String encoding, String identity, String trust) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings sender = configuration.connector("http").option("httpVersion", 1);
        ThriftTlsUtils.clientTls(sender, identity, trust, "TLSv1.3,TLSv1.2");
        ThriftTlsUtils.https(sender, 1);
        sender.protocol("thrift-http").addressScheme("https").contextPath("/thrift") //
                .option("encoding", encoding);
        return configuration;
    }

    private String storeFile(String name) throws Exception {
        String resource = "/certs/acceptance/" + name + ".p12";
        return Path.of(getClass().getResource(resource).toURI()).toString();
    }
}
