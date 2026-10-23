/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.thrift;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.ThriftDemoServiceImpl;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** Thrift 的 TCP TLS、HTTPS 版本、双向认证和明文边界均通过公开启动入口验证。 */
public class ThriftSecureScenarioTest {
    @Test(timeout = 30000)
    public void tcpTls12ServerAuthentication() throws Exception {
        // TCP 两端只启用 TLS 1.2，通过服务端认证传输 framed binary Thrift。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("thrift-tls", address.getSchema());
            assertEquals("Thrift TCP TLS 1.2 服务端认证", remote.echo("Thrift TCP TLS 1.2 服务端认证"));
        }
    }

    @Test(timeout = 30000)
    public void tcpTls12MutualAuthentication() throws Exception {
        // TCP 两端只启用 TLS 1.2，通过双向认证传输 framed binary Thrift。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp("client", "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("thrift-tls", address.getSchema());
            assertEquals("Thrift TCP TLS 1.2 双向认证", remote.echo("Thrift TCP TLS 1.2 双向认证"));
        }
    }

    @Test(timeout = 30000)
    public void tcpTls13ServerAuthentication() throws Exception {
        // TCP 两端只启用 TLS 1.3，通过服务端认证传输 framed binary Thrift。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("thrift-tls", address.getSchema());
            assertEquals("Thrift TCP TLS 1.3 服务端认证", remote.echo("Thrift TCP TLS 1.3 服务端认证"));
        }
    }

    @Test(timeout = 30000)
    public void tcpTls13MutualAuthentication() throws Exception {
        // TCP 两端只启用 TLS 1.3，通过双向认证传输 framed binary Thrift。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingTcp("client", "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("thrift-tls", address.getSchema());
            assertEquals("Thrift TCP TLS 1.3 双向认证", remote.echo("Thrift TCP TLS 1.3 双向认证"));
        }
    }

    @Test(timeout = 30000)
    public void https1Tls12ServerAuthentication() throws Exception {
        // 双方固定 TLS 1.2 与 HTTP/1 的 ALPN，通过服务端认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(1, "server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, null, "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/1 TLS 1.2 服务端认证", remote.echo("Thrift HTTPS/1 TLS 1.2 服务端认证"));
        }
    }

    @Test(timeout = 30000)
    public void https1Tls12MutualAuthentication() throws Exception {
        // 双方固定 TLS 1.2 与 HTTP/1 的 ALPN，通过双向认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(1, "server", "REQUIRE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, "client", "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/1 TLS 1.2 双向认证", remote.echo("Thrift HTTPS/1 TLS 1.2 双向认证"));
        }
    }

    @Test(timeout = 30000)
    public void https1Tls13ServerAuthentication() throws Exception {
        // 双方固定 TLS 1.3 与 HTTP/1 的 ALPN，通过服务端认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(1, "server", "NONE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingHttp(1, null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/1 TLS 1.3 服务端认证", remote.echo("Thrift HTTPS/1 TLS 1.3 服务端认证"));
        }
    }

    @Test(timeout = 30000)
    public void https1Tls13MutualAuthentication() throws Exception {
        // 双方固定 TLS 1.3 与 HTTP/1 的 ALPN，通过双向认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(1, "server", "REQUIRE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingHttp(1, "client", "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/1 TLS 1.3 双向认证", remote.echo("Thrift HTTPS/1 TLS 1.3 双向认证"));
        }
    }

    @Test(timeout = 30000)
    public void https2Tls12ServerAuthentication() throws Exception {
        // 双方固定 TLS 1.2 与 HTTP/2 的 ALPN，通过服务端认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(2, "server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, null, "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/2 TLS 1.2 服务端认证", remote.echo("Thrift HTTPS/2 TLS 1.2 服务端认证"));
        }
    }

    @Test(timeout = 30000)
    public void https2Tls12MutualAuthentication() throws Exception {
        // 双方固定 TLS 1.2 与 HTTP/2 的 ALPN，通过双向认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(2, "server", "REQUIRE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, "client", "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/2 TLS 1.2 双向认证", remote.echo("Thrift HTTPS/2 TLS 1.2 双向认证"));
        }
    }

    @Test(timeout = 30000)
    public void https2Tls13ServerAuthentication() throws Exception {
        // 双方固定 TLS 1.3 与 HTTP/2 的 ALPN，通过服务端认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(2, "server", "NONE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingHttp(2, null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/2 TLS 1.3 服务端认证", remote.echo("Thrift HTTPS/2 TLS 1.3 服务端认证"));
        }
    }

    @Test(timeout = 30000)
    public void https2Tls13MutualAuthentication() throws Exception {
        // 双方固定 TLS 1.3 与 HTTP/2 的 ALPN，通过双向认证传输 Thrift。
        MetConfiguration incoming = this.incomingHttp(2, "server", "REQUIRE", "TLSv1.3");
        MetConfiguration outgoing = this.outgoingHttp(2, "client", "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后再发布和订阅，客户端不持有业务实现。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 使用安全监听地址完成一次真实的 Thrift 请求与响应。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            ThriftDemoService remote = new ThriftClient(client.getRsfClient(address)).getRemote(service);
            assertEquals("https", address.getSchema());
            assertEquals("Thrift HTTPS/2 TLS 1.3 双向认证", remote.echo("Thrift HTTPS/2 TLS 1.3 双向认证"));
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsMissingClientCertificate() throws Exception {
        // 服务器要求客户端证书，客户端只信任 CA 而不提供身份。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 认证拒绝必须及时通知调用方，不能等待业务调用期限。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsUntrustedClientCertificate() throws Exception {
        // 客户端提供另一 CA 签发的证书，服务器的信任集不接受该身份。
        MetConfiguration incoming = this.incomingTcp("server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp("untrusted-client", "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 不可信客户端不能完成握手，也不能靠业务超时表示认证失败。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsUntrustedServerCertificate() throws Exception {
        // 客户端只信任另一 CA，当前服务器的证书链不在其信任集内。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "untrusted-trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 合法 Thrift 调用不能绕过服务端证书链校验。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsWrongServerHostname() throws Exception {
        // 证书由受信 CA 签发，但 SAN 中没有实际访问的 127.0.0.1。
        MetConfiguration incoming = this.incomingTcp("wrong-host-server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 保留默认主机名校验，可信 CA 不能替代目标主机身份。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https1RejectsMissingClientCertificate() throws Exception {
        // 服务器要求客户端证书，客户端只信任 CA 而不提供身份。
        MetConfiguration incoming = this.incomingHttp(1, "server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 认证拒绝必须及时通知调用方，不能等待业务调用期限。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https1RejectsUntrustedClientCertificate() throws Exception {
        // 客户端提供另一 CA 签发的证书，服务器的信任集不接受该身份。
        MetConfiguration incoming = this.incomingHttp(1, "server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, "untrusted-client", "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 不可信客户端不能完成握手，也不能靠业务超时表示认证失败。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https1RejectsUntrustedServerCertificate() throws Exception {
        // 客户端只信任另一 CA，当前服务器的证书链不在其信任集内。
        MetConfiguration incoming = this.incomingHttp(1, "server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, null, "untrusted-trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 合法 Thrift 调用不能绕过服务端证书链校验。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https1RejectsWrongServerHostname() throws Exception {
        // 证书由受信 CA 签发，但 SAN 中没有实际访问的 127.0.0.1。
        MetConfiguration incoming = this.incomingHttp(1, "wrong-host-server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 保留默认主机名校验，可信 CA 不能替代目标主机身份。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https1RejectsIncompatibleAlpn() throws Exception {
        // 服务器只接受 HTTP/1，客户端只宣告 HTTP/2 的 ALPN。
        MetConfiguration incoming = this.incomingHttp(1, "server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // ALPN 没有交集必须在握手时失败，不能降级到另一 HTTP 版本。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https2RejectsMissingClientCertificate() throws Exception {
        // 服务器要求客户端证书，客户端只信任 CA 而不提供身份。
        MetConfiguration incoming = this.incomingHttp(2, "server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 认证拒绝必须及时通知调用方，不能等待业务调用期限。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https2RejectsUntrustedClientCertificate() throws Exception {
        // 客户端提供另一 CA 签发的证书，服务器的信任集不接受该身份。
        MetConfiguration incoming = this.incomingHttp(2, "server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, "untrusted-client", "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 不可信客户端不能完成握手，也不能靠业务超时表示认证失败。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https2RejectsUntrustedServerCertificate() throws Exception {
        // 客户端只信任另一 CA，当前服务器的证书链不在其信任集内。
        MetConfiguration incoming = this.incomingHttp(2, "server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, null, "untrusted-trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 合法 Thrift 调用不能绕过服务端证书链校验。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https2RejectsWrongServerHostname() throws Exception {
        // 证书由受信 CA 签发，但 SAN 中没有实际访问的 127.0.0.1。
        MetConfiguration incoming = this.incomingHttp(2, "wrong-host-server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 保留默认主机名校验，可信 CA 不能替代目标主机身份。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https2RejectsIncompatibleAlpn() throws Exception {
        // 服务器只接受 HTTP/2，客户端只宣告 HTTP/1 的 ALPN。
        MetConfiguration incoming = this.incomingHttp(2, "server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // ALPN 没有交集必须在握手时失败，不能降级到另一 HTTP 版本。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void tcpTlsRejectsIncompatibleTlsVersion() throws Exception {
        // 服务器仅启用 TLS 1.2，客户端仅启用 TLS 1.3。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 版本没有交集时，安全连接必须在发出业务请求之前失败。
            InterAddress address = server.getListenAddress("thrift", "thrift-tls");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https1RejectsIncompatibleTlsVersion() throws Exception {
        // HTTP/1 两端的 TLS 版本没有交集，但服务元数据完全一致。
        MetConfiguration incoming = this.incomingHttp(1, "server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(1, null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 版本协商失败必须及时返回传输错误。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void https2RejectsIncompatibleTlsVersion() throws Exception {
        // HTTP/2 两端的 TLS 版本没有交集，但服务元数据完全一致。
        MetConfiguration incoming = this.incomingHttp(2, "server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.outgoingHttp(2, null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 发布完整的 Thrift 元数据，排除服务不存在导致的假失败。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift-http") //
                    .bindResource("thrift-http", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 版本协商失败必须及时返回传输错误。
            InterAddress address = server.getListenAddress("thrift-http", "https");
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void plaintextAndSecureTcpEndpointsRemainIndependent() throws Exception {
        // 同一上下文使用两个独立 TCP 端口，分别承载明文与 TLS Thrift。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.3,TLSv1.2");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("thrift");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3,TLSv1.2");
        outgoing.connector("tcp").protocol("thrift");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 同一服务发布到两个挂载，调用方只使用协议公开的地址别名选择端口。
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 端口与安全策略独立，两个地址都能完成真正的 Thrift 往返。
            InterAddress plainAddress = server.getListenAddress("thrift", "thrift");
            InterAddress secureAddress = server.getListenAddress("thrift", "thrift-tls");
            ThriftDemoService plain = new ThriftClient(client.getRsfClient(plainAddress)).getRemote(service);
            ThriftDemoService secure = new ThriftClient(client.getRsfClient(secureAddress)).getRemote(service);
            assertNotEquals(plainAddress.getPort(), secureAddress.getPort());
            assertEquals("Thrift 明文", plain.echo("Thrift 明文"));
            assertEquals("Thrift TLS", secure.echo("Thrift TLS"));
        }
    }

    @Test(timeout = 30000)
    public void secureTcpClientNeverFallsBackToPlaintext() throws Exception {
        // 服务器只提供明文监听，客户端明确要求 TLS。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("thrift").setDefaultTimeout(10000);
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("thrift");
        MetConfiguration outgoing = this.outgoingTcp(null, "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // 将明文端口表达成安全目标，握手失败不能触发自动降级调用。
            InterAddress bound = server.getListenAddress("thrift", "thrift");
            String host = bound.getHost();
            InterAddress address = new InterAddress("thrift-tls", host, bound.getPort(), bound.getFormUnit());
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    @Test(timeout = 30000)
    public void secureTcpListenerRejectsPlaintextClient() throws Exception {
        // 服务器只提供 TLS，客户端只准备普通 TCP Thrift。
        MetConfiguration incoming = this.incomingTcp("server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("thrift").setDefaultTimeout(10000);
        outgoing.connector("tcp").protocol("thrift");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(ThriftDemoService.class, new ThriftDemoServiceImpl()) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();
            MetBindInfo<ThriftDemoService> service = client.publisher().rsfService(ThriftDemoService.class) //
                    .protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class)).register();

            // TLS 监听收到普通 framed Thrift 字节后必须拒绝连接。
            InterAddress bound = server.getListenAddress("thrift", "thrift-tls");
            InterAddress address = new InterAddress("thrift", bound.getHost(), bound.getPort(), bound.getFormUnit());
            MetFuture pending = client.getRsfClient(address) //
                    .asyncInvoke(service, "echo", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse("TLS failure must precede the RPC deadline", failure.getCause() instanceof MetTimeoutException);
        }
    }

    private MetConfiguration incomingTcp(String identity, String clientAuth, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift").setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp").bind("127.0.0.1", 0);
        ThriftTlsUtils.serverTls(connector, identity, clientAuth, protocols);
        connector.protocol("thrift").addressScheme("thrift-tls");
        return configuration;
    }

    private MetConfiguration outgoingTcp(String identity, String trust, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift").setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp");
        ThriftTlsUtils.clientTls(connector, identity, trust, protocols);
        connector.protocol("thrift").addressScheme("thrift-tls");
        return configuration;
    }

    private MetConfiguration incomingHttp(int httpVersion, String identity, String clientAuth, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("http").bind("127.0.0.1", 0);
        ThriftTlsUtils.serverTls(connector, identity, clientAuth, protocols);
        ThriftTlsUtils.https(connector, httpVersion);
        connector.protocol("thrift-http").addressScheme("https").contextPath("/rpc");
        return configuration;
    }

    private MetConfiguration outgoingHttp(int httpVersion, String identity, String trust, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("thrift-http");
        configuration.setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("http").option("httpVersion", httpVersion);
        ThriftTlsUtils.clientTls(connector, identity, trust, protocols);
        ThriftTlsUtils.https(connector, httpVersion);
        connector.protocol("thrift-http").addressScheme("https").contextPath("/rpc");
        return configuration;
    }
}
