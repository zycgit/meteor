/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.composition.http;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.net.URL;

import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import net.hasor.example.composition.http.HttpCompositionConfiguration;
import net.hasor.example.composition.http.HttpCompositionService;
import net.hasor.example.composition.http.HttpCompositionServiceImpl;
import net.hasor.example.composition.http.grpc.GrpcFamilyMessage;
import net.hasor.example.composition.http.grpc.GrpcFamilyService;
import net.hasor.example.composition.http.grpc.GrpcFamilyServiceImpl;
import net.hasor.example.composition.thrift.EchoService;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.thrift.ThriftClient;
import net.hasor.meteor.protocol.thrift.ThriftServiceResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Timeout;

/** 安全传输由一个 HTTP 端点统一负责，各个协议仍使用相同挂载和业务入口。 */
public class HttpSecureCompositionTest {
    @NxnRpc(name = "hprose")
    @NxnRpc(name = "soap", version = "1.1")
    @NxnRpc(name = "jsonrpc", version = "2.0")
    @NxnRpc(name = "xmlrpc")
    @NxnRpc(name = "hessian", version = "2")
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void fiveProtocolsShareOneHttps1Listener() throws Exception {
        // HTTPS 只在传输配置上启用，不新增协议实现，也不分配额外监听端口。
        MetConfiguration incoming = HttpCompositionConfiguration.incoming();
        HttpCompositionTlsUtils.server(incoming, "http/1.1", "NONE");
        MetConfiguration outgoing = HttpCompositionConfiguration.outgoing(1);
        HttpCompositionTlsUtils.client(outgoing, "http/1.1", null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 先启动所有容器，再发布同签名、不同协议的服务。
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("hprose:")) //
                    .group("hprose").aliasName("hprose", "echo").protocol("hprose").register();
            MetBindInfo<HttpCompositionService> hprose = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("hprose").aliasName("hprose", "echo").protocol("hprose").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("soap:")) //
                    .group("soap").aliasName("soap", "echo").protocol("soap").register();
            MetBindInfo<HttpCompositionService> soap = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("soap").aliasName("soap", "echo").protocol("soap").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("jsonrpc:")) //
                    .group("jsonrpc").aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            MetBindInfo<HttpCompositionService> jsonrpc = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("jsonrpc").aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("xmlrpc:")) //
                    .group("xmlrpc").aliasName("xmlrpc", "echo").protocol("xmlrpc").register();
            MetBindInfo<HttpCompositionService> xmlrpc = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("xmlrpc").aliasName("xmlrpc", "echo").protocol("xmlrpc").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("hessian:")) //
                    .group("hessian").aliasName("hessian", "echo").protocol("hessian").register();
            MetBindInfo<HttpCompositionService> hessian = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("hessian").aliasName("hessian", "echo").protocol("hessian").register();

            // 独立 JDK 握手确认 ALPN 选择 HTTP/1.1，而非 TLS 成功但协议协商错误。
            int port = server.getListenAddress("jsonrpc").getPort();
            SSLContext tls = HttpCompositionTlsUtils.clientContext(null);
            try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "http/1.1" });
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                assertEquals("http/1.1", socket.getApplicationProtocol());
            }

            // 五种编码共用同一 HTTPS 监听，服务端证书由每个出站连接正常校验。
            assertEquals(port, server.getListenAddress("hprose").getPort());
            HttpCompositionService hproseRemote = client.getRsfClient(server.getListenAddress("hprose")) //
                    .getRemote(hprose);
            assertEquals("hprose:secure", hproseRemote.echo("secure"));
            assertEquals(port, server.getListenAddress("soap").getPort());
            HttpCompositionService soapRemote = client.getRsfClient(server.getListenAddress("soap")) //
                    .getRemote(soap);
            assertEquals("soap:secure", soapRemote.echo("secure"));
            assertEquals(port, server.getListenAddress("jsonrpc").getPort());
            HttpCompositionService jsonrpcRemote = client.getRsfClient(server.getListenAddress("jsonrpc")) //
                    .getRemote(jsonrpc);
            assertEquals("jsonrpc:secure", jsonrpcRemote.echo("secure"));
            assertEquals(port, server.getListenAddress("xmlrpc").getPort());
            HttpCompositionService xmlrpcRemote = client.getRsfClient(server.getListenAddress("xmlrpc")) //
                    .getRemote(xmlrpc);
            assertEquals("xmlrpc:secure", xmlrpcRemote.echo("secure"));
            assertEquals(port, server.getListenAddress("hessian").getPort());
            HttpCompositionService hessianRemote = client.getRsfClient(server.getListenAddress("hessian")) //
                    .getRemote(hessian);
            assertEquals("hessian:secure", hessianRemote.echo("secure"));
        }
    }

    @NxnRpc(name = "soap", version = "1.1")
    @NxnRpc(name = "grpc")
    @NxnRpc(name = "jsonrpc", version = "2.0")
    @NxnRpc(name = "thrift")
    @Test
    @Timeout(value = 30000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void soapGrpcJsonRpcAndThriftShareAnAuthenticatedHttp2Listener() throws Exception {
        // 跨越 XML、JSON、Protobuf 和 Thrift，四个协议统一使用双向 TLS 与 h2 ALPN。
        MetConfiguration incoming = HttpCompositionConfiguration.incoming();
        HttpCompositionTlsUtils.server(incoming, "h2", "REQUIRE");
        MetConfiguration outgoing = HttpCompositionConfiguration.outgoing(2);
        HttpCompositionTlsUtils.client(outgoing, "h2", "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 普通 Java 服务只公开给声明的协议，IDL 资源属于各自的服务绑定。
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("soap:")) //
                    .group("soap").aliasName("soap", "echo").protocol("soap").register();
            MetBindInfo<HttpCompositionService> soap = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("soap").aliasName("soap", "echo").protocol("soap").register();
            server.publisher().rsfService(HttpCompositionService.class, new HttpCompositionServiceImpl("jsonrpc:")) //
                    .group("jsonrpc").aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            MetBindInfo<HttpCompositionService> json = client.publisher().rsfService(HttpCompositionService.class) //
                    .group("jsonrpc").aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            URL proto = this.getClass().getResource("/protocol/proto/composition/family.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            server.publisher().rsfService(GrpcFamilyService.class, new GrpcFamilyServiceImpl()) //
                    .aliasName("grpc", "meteor.composition.FamilyService").protocol("grpc") //
                    .serialize("Protobuf").bindResource("grpc", resource).register();
            MetBindInfo<GrpcFamilyService> grpc = client.publisher().rsfService(GrpcFamilyService.class) //
                    .aliasName("grpc", "meteor.composition.FamilyService").protocol("grpc") //
                    .serialize("Protobuf").bindResource("grpc", resource).register();
            ThriftServiceResource thriftResource = new ThriftServiceResource(EchoService.class);
            server.publisher().rsfService(EchoService.Iface.class, value -> "thrift:" + value) //
                    .protocol("thrift-http").bindResource("thrift-http", thriftResource).register();
            MetBindInfo<EchoService.Iface> thrift = client.publisher().rsfService(EchoService.Iface.class) //
                    .protocol("thrift-http").bindResource("thrift-http", thriftResource).register();

            // 独立 JDK 客户端携带客户端证书，验证实际协商得到 h2。
            int port = server.getListenAddress("grpc").getPort();
            SSLContext tls = HttpCompositionTlsUtils.clientContext("client");
            try (SSLSocket socket = (SSLSocket) tls.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 5000);
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "h2" });
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                assertEquals("h2", socket.getApplicationProtocol());
            }

            // 各协议的真实代理通过同一个受保护端口取得自己的结果。
            assertEquals(port, server.getListenAddress("soap").getPort());
            assertEquals(port, server.getListenAddress("jsonrpc").getPort());
            assertEquals(port, server.getListenAddress("thrift-http").getPort());
            HttpCompositionService soapRemote = client.getRsfClient(server.getListenAddress("soap")).getRemote(soap);
            assertEquals("soap:mutual", soapRemote.echo("mutual"));
            HttpCompositionService jsonRemote = client.getRsfClient(server.getListenAddress("jsonrpc")).getRemote(json);
            assertEquals("jsonrpc:mutual", jsonRemote.echo("mutual"));
            GrpcFamilyService grpcRemote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(grpc);
            assertEquals("family:mutual", grpcRemote.echo(GrpcFamilyMessage.of("mutual", 3)).getText());
            ThriftClient thriftClient = new ThriftClient(client.getRsfClient(server.getListenAddress("thrift-http")));
            assertEquals("thrift:mutual", thriftClient.getRemote(thrift).echo("mutual"));
        }
    }

    @NxnRpc(name = "jsonrpc", version = "2.0")
    @NxnRpc(name = "soap", version = "1.1")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void aMissingClientIdentityCannotReachAnyMountedBusinessService() throws Exception {
        // 监听统一要求客户端证书，不能由某一个协议路径绕过身份校验。
        MetConfiguration incoming = HttpCompositionConfiguration.incoming();
        HttpCompositionTlsUtils.server(incoming, "h2", "REQUIRE");
        MetConfiguration outgoing = HttpCompositionConfiguration.outgoing(2);
        HttpCompositionTlsUtils.client(outgoing, "h2", null);
        MetConfiguration authenticatedConfiguration = HttpCompositionConfiguration.outgoing(2);
        HttpCompositionTlsUtils.client(authenticatedConfiguration, "h2", "client");
        AtomicInteger calls = new AtomicInteger();
        HttpCompositionService implementation = value -> {
            calls.incrementAndGet();
            return value;
        };
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext(); //
             MetContext authenticated = authenticatedConfiguration.buildContext()) {
            server.start();
            client.start();
            authenticated.start();
            server.publisher().rsfService(HttpCompositionService.class, implementation) //
                    .aliasName("jsonrpc", "echo").aliasName("soap", "echo") //
                    .protocol("jsonrpc", "soap").register();
            MetBindInfo<HttpCompositionService> json = client.publisher().rsfService(HttpCompositionService.class) //
                    .aliasName("jsonrpc", "echo").protocol("jsonrpc").register();

            // 缺少证书的调用及时失败，且业务没有进入；随后用仅增加身份的配置作为对照。
            MetClient remote = client.getRsfClient(server.getListenAddress("jsonrpc"));
            Class<?>[] types = { String.class };
            Object[] arguments = { "denied" };
            ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                remote.asyncInvoke(json, "echo", types, arguments).getData(5, TimeUnit.SECONDS);
            });
            assertFalse(failure.getCause() instanceof MetTimeoutException);
            assertEquals(0, calls.get());

            // 同一 TLS 端点与同一协议只增加客户端证书便能完成，排除路由、编码或服务配置错误。
            MetBindInfo<HttpCompositionService> authenticatedBinding = authenticated.publisher() //
                    .rsfService(HttpCompositionService.class) //
                    .aliasName("jsonrpc", "echo").protocol("jsonrpc").register();
            MetClient authenticatedClient = authenticated.getRsfClient(server.getListenAddress("jsonrpc"));
            HttpCompositionService accepted = authenticatedClient.getRemote(authenticatedBinding);
            assertEquals("authenticated", accepted.echo("authenticated"));
            assertEquals(1, calls.get());
        }
    }
}
