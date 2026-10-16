/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** 每个编码与传输组合独立从 bootstrap 完成真实 RPC。 */
public class GrpcSecureEncodingTest {

    @Test(timeout = 20000)
    public void jsonH2cPriorKnowledgeCallsPublishedService() throws Exception {
        // 为 Json 编码准备明文 HTTP/2 prior knowledge。
        MetConfiguration incoming = this.configuration(true, Security.H2C);
        MetConfiguration outgoing = this.configuration(false, Security.H2C);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Json", 7));
            assertEquals("安全调用 Json", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void jsonTlsServerAuthenticationCallsPublishedService() throws Exception {
        // 为 Json 编码准备仅验证服务端证书的 TLS 与 h2 ALPN。
        MetConfiguration incoming = this.configuration(true, Security.TLS);
        MetConfiguration outgoing = this.configuration(false, Security.TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Json", 7));
            assertEquals("安全调用 Json", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void jsonMutualTlsCallsPublishedService() throws Exception {
        // 为 Json 编码准备分别持有服务端和客户端身份的双向 TLS。
        MetConfiguration incoming = this.configuration(true, Security.MUTUAL_TLS);
        MetConfiguration outgoing = this.configuration(false, Security.MUTUAL_TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Json") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Json", 7));
            assertEquals("安全调用 Json", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufH2cPriorKnowledgeCallsPublishedService() throws Exception {
        // 为 Protobuf 编码准备明文 HTTP/2 prior knowledge。
        MetConfiguration incoming = this.configuration(true, Security.H2C);
        MetConfiguration outgoing = this.configuration(false, Security.H2C);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Protobuf", 7));
            assertEquals("安全调用 Protobuf", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsServerAuthenticationCallsPublishedService() throws Exception {
        // 为 Protobuf 编码准备仅验证服务端证书的 TLS 与 h2 ALPN。
        MetConfiguration incoming = this.configuration(true, Security.TLS);
        MetConfiguration outgoing = this.configuration(false, Security.TLS);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Protobuf", 7));
            assertEquals("安全调用 Protobuf", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void protobufMutualTlsCallsPublishedService() throws Exception {
        // 为 Protobuf 编码准备分别持有服务端和客户端身份的双向 TLS。
        MetConfiguration incoming = this.configuration(true, Security.MUTUAL_TLS);
        MetConfiguration outgoing = this.configuration(false, Security.MUTUAL_TLS);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Protobuf", 7));
            assertEquals("安全调用 Protobuf", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void javaH2cPriorKnowledgeCallsPublishedService() throws Exception {
        // 为 Java 编码准备明文 HTTP/2 prior knowledge。
        MetConfiguration incoming = this.configuration(true, Security.H2C);
        MetConfiguration outgoing = this.configuration(false, Security.H2C);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Java") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Java") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Java", 7));
            assertEquals("安全调用 Java", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void javaTlsServerAuthenticationCallsPublishedService() throws Exception {
        // 为 Java 编码准备仅验证服务端证书的 TLS 与 h2 ALPN。
        MetConfiguration incoming = this.configuration(true, Security.TLS);
        MetConfiguration outgoing = this.configuration(false, Security.TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Java") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Java") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Java", 7));
            assertEquals("安全调用 Java", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void javaMutualTlsCallsPublishedService() throws Exception {
        // 为 Java 编码准备分别持有服务端和客户端身份的双向 TLS。
        MetConfiguration incoming = this.configuration(true, Security.MUTUAL_TLS);
        MetConfiguration outgoing = this.configuration(false, Security.MUTUAL_TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Java") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Java") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Java", 7));
            assertEquals("安全调用 Java", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hessianH2cPriorKnowledgeCallsPublishedService() throws Exception {
        // 为 Hessian 编码准备明文 HTTP/2 prior knowledge。
        MetConfiguration incoming = this.configuration(true, Security.H2C);
        MetConfiguration outgoing = this.configuration(false, Security.H2C);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hessian") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hessian") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Hessian", 7));
            assertEquals("安全调用 Hessian", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hessianTlsServerAuthenticationCallsPublishedService() throws Exception {
        // 为 Hessian 编码准备仅验证服务端证书的 TLS 与 h2 ALPN。
        MetConfiguration incoming = this.configuration(true, Security.TLS);
        MetConfiguration outgoing = this.configuration(false, Security.TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hessian") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hessian") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Hessian", 7));
            assertEquals("安全调用 Hessian", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hessianMutualTlsCallsPublishedService() throws Exception {
        // 为 Hessian 编码准备分别持有服务端和客户端身份的双向 TLS。
        MetConfiguration incoming = this.configuration(true, Security.MUTUAL_TLS);
        MetConfiguration outgoing = this.configuration(false, Security.MUTUAL_TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hessian") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hessian") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Hessian", 7));
            assertEquals("安全调用 Hessian", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hproseH2cPriorKnowledgeCallsPublishedService() throws Exception {
        // 为 Hprose 编码准备明文 HTTP/2 prior knowledge。
        MetConfiguration incoming = this.configuration(true, Security.H2C);
        MetConfiguration outgoing = this.configuration(false, Security.H2C);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hprose") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Hprose", 7));
            assertEquals("安全调用 Hprose", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hproseTlsServerAuthenticationCallsPublishedService() throws Exception {
        // 为 Hprose 编码准备仅验证服务端证书的 TLS 与 h2 ALPN。
        MetConfiguration incoming = this.configuration(true, Security.TLS);
        MetConfiguration outgoing = this.configuration(false, Security.TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hprose") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Hprose", 7));
            assertEquals("安全调用 Hprose", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    @Test(timeout = 20000)
    public void hproseMutualTlsCallsPublishedService() throws Exception {
        // 为 Hprose 编码准备分别持有服务端和客户端身份的双向 TLS。
        MetConfiguration incoming = this.configuration(true, Security.MUTUAL_TLS);
        MetConfiguration outgoing = this.configuration(false, Security.MUTUAL_TLS);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅同一个业务接口，保留编码声明和绑定资源。
            server.start();
            client.start();
            Echo implementation = new EchoImpl();
            server.publisher() //
                    .rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hprose") //
                    .register();
            MetBindInfo<Echo> info = client.publisher() //
                    .rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Hprose") //
                    .register();

            // 通过远端地址调用，检查中文字段和服务端计算后的数值。
            Echo remote = client.getRsfClient(server.getListenAddress("grpc")).getRemote(info);
            Message response = remote.echo(Message.of("安全调用 Hprose", 7));
            assertEquals("安全调用 Hprose", response.getValue());
            assertEquals(8, response.getCount());
        }
    }

    private MetConfiguration configuration(boolean server, Security security) {
        // 只复用无资源的连接器配置，调用生命周期保留在每个场景中。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (server) {
            http.bind("127.0.0.1", 0);
        }
        if (security != Security.H2C) {
            http.option("tlsEnabled", true);
            http.option("httpScheme", "https");
            http.option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12");
            http.option("tlsTrustStorePassword", "meteor-test");
            http.option("tlsAlpn", "h2");
            if (security == Security.MUTUAL_TLS) {
                http.option("tlsClientAuth", "REQUIRE");
            }
            if (server || security == Security.MUTUAL_TLS) {
                String identity = server ? "server" : "client";
                http.option("tlsKeyStore", "classpath:/tls/acceptance/" + identity + ".p12");
                http.option("tlsKeyStorePassword", "meteor-test");
            }
        }
        var grpc = http.protocol("grpc");
        if (security == Security.H2C) {
            grpc.addressScheme("http");
        } else {
            grpc.addressScheme("https");
        }
        return configuration;
    }

    private enum Security {
        H2C,
        TLS,
        MUTUAL_TLS
    }
}
