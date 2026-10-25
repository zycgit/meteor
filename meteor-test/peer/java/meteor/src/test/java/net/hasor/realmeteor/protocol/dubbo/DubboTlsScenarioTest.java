/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.dubbo;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.dubbo.interop.DubboInteropService;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import org.junit.Test;
import static org.junit.Assert.*;

/** Dubbo 二进制帧通过公开启动入口组合 TCP、TLS 身份校验与独立端口。 */
public class DubboTlsScenarioTest {
    @Test(timeout = 30000)
    public void serverAuthenticationUsesTls12() throws Exception {
        // 双方仅启用 TLS 1.2，客户端信任服务器证书而不提供客户端身份。
        MetConfiguration incoming = this.server("server", "NONE", "TLSv1.2");
        MetConfiguration outgoing = this.client(null, "trust", "TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 使用安全地址建立真实连接，按 Dubbo 请求/响应完成往返。
            InterAddress address = server.getListenAddress("dubbo", "dubbos");
            DubboInteropService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("dubbos", address.getSchema());
            assertEquals("Dubbo TLS 1.2", remote.echo("Dubbo TLS 1.2"));
        }
    }

    @Test(timeout = 30000)
    public void serverAuthenticationUsesTls13() throws Exception {
        // 双方仅启用 TLS 1.3，避免测试被其他版本协商结果替代。
        MetConfiguration incoming = this.server("server", "NONE", "TLSv1.3");
        MetConfiguration outgoing = this.client(null, "trust", "TLSv1.3");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 安全端点仍使用相同的 Dubbo 编解码器，不另造协议镜像。
            InterAddress address = server.getListenAddress("dubbo", "dubbos");
            DubboInteropService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("Dubbo TLS 1.3", remote.echo("Dubbo TLS 1.3"));
        }
    }

    @Test(timeout = 30000)
    public void mutualAuthenticationUsesClientCertificate() throws Exception {
        // 服务器要求客户端证书，两端分别使用 serverAuth 和 clientAuth 测试身份。
        MetConfiguration incoming = this.server("server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.client("client", "trust", "TLSv1.3,TLSv1.2");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 只有完成双向身份校验后，业务调用才能得到响应。
            InterAddress address = server.getListenAddress("dubbo", "dubbos");
            DubboInteropService remote = client.getRsfClient(address).getRemote(service);
            assertEquals("Dubbo mutual TLS", remote.echo("Dubbo mutual TLS"));
        }
    }

    @Test(timeout = 30000)
    public void missingClientCertificateIsRejected() throws Exception {
        // 服务器要求客户端证书，客户端仅配置可信 CA。
        MetConfiguration incoming = this.server("server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.client(null, "trust", "TLSv1.3,TLSv1.2");
        DubboInteropServiceImpl provider = new DubboInteropServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, provider).protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 认证失败直接结束调用，不能执行 notice，也不能等待业务超时才结束。
            InterAddress address = server.getListenAddress("dubbo", "dubbos");
            MetFuture call = client.getRsfClient(address) //
                    .asyncInvoke(service, "notice", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, provider.notices());
        }
    }

    @Test(timeout = 30000)
    public void untrustedClientCertificateIsRejected() throws Exception {
        // 客户端携带另一 CA 签发的证书，服务器不能接受该身份。
        MetConfiguration incoming = this.server("server", "REQUIRE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.client("untrusted-client", "trust", "TLSv1.3,TLSv1.2");
        DubboInteropServiceImpl provider = new DubboInteropServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, provider).protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 失败必须发生在业务分派前，通知计数保持零。
            InterAddress address = server.getListenAddress("dubbo", "dubbos");
            MetFuture call = client.getRsfClient(address) //
                    .asyncInvoke(service, "notice", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, provider.notices());
        }
    }

    @Test(timeout = 30000)
    public void untrustedServerCertificateIsRejected() throws Exception {
        // 客户端只信任另一个 CA，不能接受当前服务器的证书链。
        MetConfiguration incoming = this.server("server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.client(null, "untrusted-trust", "TLSv1.3,TLSv1.2");
        DubboInteropServiceImpl provider = new DubboInteropServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, provider).protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 不可信证书不能回退为明文，也不能将请求送到业务实现。
            InterAddress address = server.getListenAddress("dubbo", "dubbos");
            MetFuture call = client.getRsfClient(address) //
                    .asyncInvoke(service, "notice", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, provider.notices());
        }
    }

    @Test(timeout = 30000)
    public void serverHostnameMismatchIsRejected() throws Exception {
        // 证书链可信，但 SAN 中没有实际访问的 127.0.0.1。
        MetConfiguration incoming = this.server("wrong-host-server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = this.client(null, "trust", "TLSv1.3,TLSv1.2");
        DubboInteropServiceImpl provider = new DubboInteropServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, provider).protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 即使同一 CA 签发，也必须通过目标主机身份校验。
            InterAddress address = server.getListenAddress("dubbo", "dubbos");
            MetFuture call = client.getRsfClient(address) //
                    .asyncInvoke(service, "notice", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, provider.notices());
        }
    }

    @Test(timeout = 30000)
    public void plaintextAndSecureEndpointsCanCoexist() throws Exception {
        // 同一进程使用两个 TCP 监听器，安全属性和地址别名各自独立。
        MetConfiguration incoming = this.server("server", "NONE", "TLSv1.3,TLSv1.2");
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("dubbo");
        MetConfiguration outgoing = this.client(null, "trust", "TLSv1.3,TLSv1.2");
        outgoing.connector("tcp").protocol("dubbo");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, new DubboInteropServiceImpl()) //
                    .protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 逻辑协议选择覆盖两个挂载，两个独立端口都调用相同服务实现。
            InterAddress plainAddress = server.getListenAddress("dubbo", "dubbo");
            InterAddress secureAddress = server.getListenAddress("dubbo", "dubbos");
            DubboInteropService plain = client.getRsfClient(plainAddress).getRemote(service);
            DubboInteropService secure = client.getRsfClient(secureAddress).getRemote(service);
            assertNotEquals(plainAddress.getPort(), secureAddress.getPort());
            assertEquals("Dubbo plaintext", plain.echo("Dubbo plaintext"));
            assertEquals("Dubbo TLS", secure.echo("Dubbo TLS"));
        }
    }

    @Test(timeout = 30000)
    public void secureClientNeverFallsBackToPlaintext() throws Exception {
        // 服务器只有明文 Dubbo，客户端却要求 TLS。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("dubbo").setDefaultTimeout(10000);
        incoming.connector("tcp").bind("127.0.0.1", 0).protocol("dubbo");
        MetConfiguration outgoing = this.client(null, "trust", "TLSv1.3,TLSv1.2");
        DubboInteropServiceImpl provider = new DubboInteropServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, provider).protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 故意用安全地址访问明文端口，连接失败且不得自动降级。
            InterAddress bound = server.getListenAddress("dubbo");
            InterAddress target = new InterAddress("dubbos", bound.getHost(), bound.getPort(), bound.getFormUnit());
            MetFuture call = client.getRsfClient(target) //
                    .asyncInvoke(service, "notice", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, provider.notices());
        }
    }

    @Test(timeout = 30000)
    public void secureListenerRejectsPlaintextClient() throws Exception {
        // 服务器只有 TLS Dubbo，客户端只准备明文连接。
        MetConfiguration incoming = this.server("server", "NONE", "TLSv1.3,TLSv1.2");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("dubbo").setDefaultTimeout(10000);
        outgoing.connector("tcp").protocol("dubbo");
        DubboInteropServiceImpl provider = new DubboInteropServiceImpl();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(DubboInteropService.class, provider).protocol("dubbo").register();
            MetBindInfo<DubboInteropService> service = client.publisher().rsfService(DubboInteropService.class) //
                    .protocol("dubbo").register();

            // 相同 Dubbo 帧不能跨过安全边界，TLS 监听不得执行明文请求。
            InterAddress bound = server.getListenAddress("dubbo", "dubbos");
            InterAddress target = new InterAddress("dubbo", bound.getHost(), bound.getPort(), bound.getFormUnit());
            MetFuture call = client.getRsfClient(target) //
                    .asyncInvoke(service, "notice", new Class<?>[] { String.class }, new Object[] { "rejected" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> call.get(8, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, provider.notices());
        }
    }

    private MetConfiguration server(String identity, String clientAuth, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo").setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp").bind("127.0.0.1", 0);
        connector.option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/tls/acceptance/" + identity + ".p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsProtocols", protocols);
        connector.protocol("dubbo").addressScheme("dubbos");
        return configuration;
    }

    private MetConfiguration client(String identity, String trust, String protocols) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("dubbo").setDefaultTimeout(10000);
        ConnectorSettings connector = configuration.connector("tcp");
        connector.option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/tls/acceptance/" + trust + ".p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsProtocols", protocols);
        if (StringUtils.isNotBlank(identity)) {
            connector.option("tlsKeyStore", "classpath:/tls/acceptance/" + identity + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
        connector.protocol("dubbo").addressScheme("dubbos");
        return configuration;
    }
}
