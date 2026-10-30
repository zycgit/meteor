/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.example.composition.socket.SocketEchoService;
import net.hasor.example.composition.thrift.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.junit.Test;
import static org.junit.Assert.*;

/** 在相同物理监听上验证协议探测与传输认证组合；协议只负责各自的业务报文。 */
public class SocketSecurityCompositionScenarioTest {
    @Test(timeout = 60000)
    public void fourProtocolsShareServerAuthenticatedTlsListener() throws Exception {
        // 一个 TLS 监听预装四个协议，客户端只校验服务器身份。
        MetConfiguration incoming = this.serverConfiguration("NONE", false);
        MetConfiguration outgoing = this.clientConfiguration(false, false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 容器启动后分别发布，服务名明确隔离同一个 Java 接口的协议绑定。
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsfEcho").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "oncrpc:" + value) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            EchoService.Iface thriftProvider = value -> "thrift:" + value;
            server.publisher().rsfService(EchoService.Iface.class, thriftProvider) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsfEcho").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            MetBindInfo<SocketEchoService> onc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();

            // 四个协议地址必须指向同一 TLS 端口，解密后再各自完成请求和响应。
            InterAddress rsfAddress = server.getListenAddress("rsf", "rsf-tls");
            InterAddress dubboAddress = server.getListenAddress("dubbo", "dubbo-tls");
            InterAddress oncAddress = server.getListenAddress("oncrpc", "oncrpc-tls");
            InterAddress thriftAddress = server.getListenAddress("thrift", "thrift-tls");
            assertEquals(rsfAddress.getPort(), dubboAddress.getPort());
            assertEquals(rsfAddress.getPort(), oncAddress.getPort());
            assertEquals(rsfAddress.getPort(), thriftAddress.getPort());
            assertEquals("rsf:single", client.getRsfClient(rsfAddress).getRemote(rsf).echo("single"));
            assertEquals("dubbo:single", client.getRsfClient(dubboAddress).getRemote(dubbo).echo("single"));
            assertEquals("oncrpc:single", client.getRsfClient(oncAddress).getRemote(onc).echo("single"));
            EchoService.Iface remote = new ThriftClient(client.getRsfClient(thriftAddress)).getRemote(thrift);
            assertEquals("thrift:single", remote.echo("single"));
        }
    }

    @Test(timeout = 60000)
    public void fourProtocolsShareMutuallyAuthenticatedTlsListener() throws Exception {
        // 四个协议共用同一个必须验证客户端证书的 TLS 监听。
        MetConfiguration incoming = this.serverConfiguration("REQUIRE", false);
        MetConfiguration outgoing = this.clientConfiguration(true, false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 身份认证是端点配置；服务发布保持各协议的标准元数据。
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsfEcho").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "oncrpc:" + value) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            EchoService.Iface thriftProvider = value -> "thrift:" + value;
            server.publisher().rsfService(EchoService.Iface.class, thriftProvider) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsfEcho").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            MetBindInfo<SocketEchoService> onc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();

            // 每个客户端连接都完成双向认证，服务端协议探测仍然共享同一端口。
            InterAddress rsfAddress = server.getListenAddress("rsf", "rsf-tls");
            InterAddress dubboAddress = server.getListenAddress("dubbo", "dubbo-tls");
            InterAddress oncAddress = server.getListenAddress("oncrpc", "oncrpc-tls");
            InterAddress thriftAddress = server.getListenAddress("thrift", "thrift-tls");
            assertEquals(rsfAddress.getPort(), dubboAddress.getPort());
            assertEquals(rsfAddress.getPort(), oncAddress.getPort());
            assertEquals(rsfAddress.getPort(), thriftAddress.getPort());
            assertEquals("rsf:mutual", client.getRsfClient(rsfAddress).getRemote(rsf).echo("mutual"));
            assertEquals("dubbo:mutual", client.getRsfClient(dubboAddress).getRemote(dubbo).echo("mutual"));
            assertEquals("oncrpc:mutual", client.getRsfClient(oncAddress).getRemote(onc).echo("mutual"));
            EchoService.Iface remote = new ThriftClient(client.getRsfClient(thriftAddress)).getRemote(thrift);
            assertEquals("thrift:mutual", remote.echo("mutual"));
        }
    }

    @Test(timeout = 60000)
    public void plainAndTlsListenersServeAllFourProtocolsInSameRuntime() throws Exception {
        // 相同运行实例显式配置明文与 TLS 两个端点，每个端点均挂载四个协议。
        MetConfiguration incoming = this.serverConfiguration("NONE", true);
        MetConfiguration outgoing = this.clientConfiguration(false, true);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 服务按协议发布一次，同协议在明文和 TLS 挂载上均可访问。
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsfEcho").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "oncrpc:" + value) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            EchoService.Iface thriftProvider = value -> "thrift:" + value;
            server.publisher().rsfService(EchoService.Iface.class, thriftProvider) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsfEcho").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            MetBindInfo<SocketEchoService> onc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();

            // 明文与 TLS 端口不同，每个协议都通过自己的地址别名选择正确的安全模式。
            InterAddress rsfPlain = server.getListenAddress("rsf", "rsf");
            InterAddress rsfTls = server.getListenAddress("rsf", "rsf-tls");
            InterAddress dubboPlain = server.getListenAddress("dubbo", "dubbo");
            InterAddress dubboTls = server.getListenAddress("dubbo", "dubbo-tls");
            InterAddress oncPlain = server.getListenAddress("oncrpc", "oncrpc");
            InterAddress oncTls = server.getListenAddress("oncrpc", "oncrpc-tls");
            InterAddress thriftPlain = server.getListenAddress("thrift", "thrift");
            InterAddress thriftTls = server.getListenAddress("thrift", "thrift-tls");
            assertNotEquals(rsfPlain.getPort(), rsfTls.getPort());
            assertEquals(rsfPlain.getPort(), dubboPlain.getPort());
            assertEquals(rsfPlain.getPort(), oncPlain.getPort());
            assertEquals(rsfPlain.getPort(), thriftPlain.getPort());
            assertEquals(rsfTls.getPort(), dubboTls.getPort());
            assertEquals(rsfTls.getPort(), oncTls.getPort());
            assertEquals(rsfTls.getPort(), thriftTls.getPort());
            assertEquals("rsf:plain", client.getRsfClient(rsfPlain).getRemote(rsf).echo("plain"));
            assertEquals("rsf:tls", client.getRsfClient(rsfTls).getRemote(rsf).echo("tls"));
            assertEquals("dubbo:plain", client.getRsfClient(dubboPlain).getRemote(dubbo).echo("plain"));
            assertEquals("dubbo:tls", client.getRsfClient(dubboTls).getRemote(dubbo).echo("tls"));
            assertEquals("oncrpc:plain", client.getRsfClient(oncPlain).getRemote(onc).echo("plain"));
            assertEquals("oncrpc:tls", client.getRsfClient(oncTls).getRemote(onc).echo("tls"));
            EchoService.Iface plain = new ThriftClient(client.getRsfClient(thriftPlain)).getRemote(thrift);
            EchoService.Iface secure = new ThriftClient(client.getRsfClient(thriftTls)).getRemote(thrift);
            assertEquals("thrift:plain", plain.echo("plain"));
            assertEquals("thrift:tls", secure.echo("tls"));
        }
    }

    @Test(timeout = 60000)
    public void missingMutualTlsIdentityDoesNotDisablePlainProtocolListener() throws Exception {
        // 明文端点继续对外服务，另一个端点要求客户端证书。
        MetConfiguration incoming = this.serverConfiguration("REQUIRE", true);
        MetConfiguration outgoing = this.clientConfiguration(false, true);
        MetConfiguration authenticatedConfiguration = this.clientConfiguration(true, true);
        AtomicInteger rejectedCalls = new AtomicInteger();
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext(); //
             MetContext authenticated = authenticatedConfiguration.buildContext()) {
            server.start();
            client.start();
            authenticated.start();

            // 真实业务服务均已发布，认证失败不能被服务不存在掩盖。
            server.publisher().rsfService(SocketEchoService.class, value -> "rsf:" + value) //
                    .name("rsfEcho").protocol("rsf").register();
            server.publisher().rsfService(SocketEchoService.class, value -> "dubbo:" + value) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            SocketEchoService oncProvider = value -> {
                rejectedCalls.incrementAndGet();
                return "oncrpc:" + value;
            };
            server.publisher().rsfService(SocketEchoService.class, oncProvider) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            EchoService.Iface thriftProvider = value -> "thrift:" + value;
            server.publisher().rsfService(EchoService.Iface.class, thriftProvider) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();
            MetBindInfo<SocketEchoService> rsf = client.publisher().rsfService(SocketEchoService.class) //
                    .name("rsfEcho").protocol("rsf").register();
            MetBindInfo<SocketEchoService> dubbo = client.publisher().rsfService(SocketEchoService.class) //
                    .name("dubboEcho").aliasName("dubbo", "composition.DubboEcho").protocol("dubbo").register();
            MetBindInfo<SocketEchoService> onc = client.publisher().rsfService(SocketEchoService.class) //
                    .name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .name("thriftEcho").protocol("thrift") //
                    .bindResource("thrift", new ThriftServiceResource(EchoService.class)).register();

            // 缺少证书的连接及时失败，业务方法没有执行，不能用 RPC 超时代表认证拒绝。
            InterAddress denied = server.getListenAddress("oncrpc", "oncrpc-tls");
            MetFuture pending = client.getRsfClient(denied) //
                    .asyncInvoke(onc, "echo", new Class<?>[] { String.class }, new Object[] { "denied" });
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                pending.getData(8, TimeUnit.SECONDS);
            });
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, rejectedCalls.get());

            // 仅增加客户端证书后访问同一个 TLS 端点、相同服务，确认拒绝源自身份而非协议路由。
            MetBindInfo<SocketEchoService> authenticatedBinding = authenticated.publisher() //
                    .rsfService(SocketEchoService.class).name("oncEcho").protocol("oncrpc") //
                    .bindResource("oncrpc", new OncRpcServiceResource(SocketEchoService.class)).register();
            SocketEchoService accepted = authenticated.getRsfClient(denied).getRemote(authenticatedBinding);
            assertEquals("oncrpc:authenticated", accepted.echo("authenticated"));
            assertEquals(1, rejectedCalls.get());

            // 安全握手失败后，同实例上的明文端点仍可路由全部四个协议。
            InterAddress rsfPlain = server.getListenAddress("rsf", "rsf");
            InterAddress dubboPlain = server.getListenAddress("dubbo", "dubbo");
            InterAddress oncPlain = server.getListenAddress("oncrpc", "oncrpc");
            InterAddress thriftPlain = server.getListenAddress("thrift", "thrift");
            assertEquals("rsf:alive", client.getRsfClient(rsfPlain).getRemote(rsf).echo("alive"));
            assertEquals("dubbo:alive", client.getRsfClient(dubboPlain).getRemote(dubbo).echo("alive"));
            assertEquals("oncrpc:alive", client.getRsfClient(oncPlain).getRemote(onc).echo("alive"));
            EchoService.Iface remote = new ThriftClient(client.getRsfClient(thriftPlain)).getRemote(thrift);
            assertEquals("thrift:alive", remote.echo("alive"));
            assertEquals(2, rejectedCalls.get());
        }
    }

    private MetConfiguration serverConfiguration(String clientAuth, boolean includePlain) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("rsf").setDefaultTimeout(10000);
        ConnectorSettings tls = configuration.connector("tcp").bind("127.0.0.1", 0);
        tls.option("tlsEnabled", true) //
                .option("tlsKeyStore", "classpath:/certs/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsProtocols", "TLSv1.3,TLSv1.2");
        this.protocols(tls, true);
        if (includePlain) {
            ConnectorSettings plain = configuration.connector("tcp").bind("127.0.0.1", 0);
            this.protocols(plain, false);
        }
        return configuration;
    }

    private MetConfiguration clientConfiguration(boolean withIdentity, boolean includePlain) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("rsf").setDefaultTimeout(10000);
        ConnectorSettings tls = configuration.connector("tcp");
        tls.option("tlsEnabled", true) //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsProtocols", "TLSv1.3,TLSv1.2");
        if (withIdentity) {
            tls.option("tlsKeyStore", "classpath:/certs/acceptance/client.p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
        this.protocols(tls, true);
        if (includePlain) {
            this.protocols(configuration.connector("tcp"), false);
        }
        return configuration;
    }

    private void protocols(ConnectorSettings connector, boolean secure) {
        if (secure) {
            connector.protocol("rsf").addressScheme("rsf-tls");
            connector.protocol("dubbo").addressScheme("dubbo-tls");
            connector.protocol("thrift").addressScheme("thrift-tls");
            connector.protocol("oncrpc").addressScheme("oncrpc-tls");
        } else {
            connector.protocol("rsf");
            connector.protocol("dubbo");
            connector.protocol("thrift");
            connector.protocol("oncrpc");
        }
    }
}
