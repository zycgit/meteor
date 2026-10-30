/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleFlowServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.example.protocol.triple.TripleUnaryService;
import net.hasor.example.protocol.triple.TripleUnaryServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetClient;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFuture;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

/** Triple 在 HTTP 各版本、安全认证与协商中的实际发布和调用。 */
public class TripleTransportScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test(timeout = 20000)
    public void httpJsonUsesHttpsHttp1ServerAuthentication() throws Exception {
        // 普通 HTTP JSON 在 HTTP/1 HTTPS 上验证服务端身份。
        MetConfiguration incoming = this.secureIncoming(1, "json", "NONE");
        MetConfiguration outgoing = this.secureOutgoing(1, "json", null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void httpJsonUsesHttpsHttp1MutualAuthentication() throws Exception {
        // 普通 HTTP JSON 在 HTTP/1 HTTPS 上验证双方身份。
        MetConfiguration incoming = this.secureIncoming(1, "json", "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(1, "json", "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void httpJsonUsesHttpsHttp2ServerAuthentication() throws Exception {
        // 普通 HTTP JSON 通过 h2 ALPN 与服务端证书认证。
        MetConfiguration incoming = this.secureIncoming(2, "json", "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, "json", null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void httpJsonUsesHttpsHttp2MutualAuthentication() throws Exception {
        // 普通 HTTP JSON 通过 h2 ALPN 与双向证书认证。
        MetConfiguration incoming = this.secureIncoming(2, "json", "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, "json", "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Json") //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void wrappedHessian2UsesHttpsServerAuthentication() throws Exception {
        // Hessian2 wrapper 在 TLS 认证后仍保留 Java 参数类型。
        MetConfiguration incoming = this.secureIncoming(2, null, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, null);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2") //
                    .register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2") //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void wrappedHessian2UsesHttpsMutualAuthentication() throws Exception {
        // Hessian2 wrapper 通过 HTTP/2 双向 TLS 传递 DTO。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2") //
                    .register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2") //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleDemoService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeProtobufUsesHttpsServerAuthentication() throws Exception {
        // 原生 Protobuf 在 h2 ALPN 后使用服务端证书认证。
        MetConfiguration incoming = this.secureIncoming(2, null, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, null);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeProtobufUsesHttpsMutualAuthentication() throws Exception {
        // 原生 Protobuf 通过双向 TLS 保持标准 gRPC 封装。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeJsonUsesHttpsServerAuthentication() throws Exception {
        // 绑定 proto 的原生 gRPC JSON 在单向 TLS 上传输。
        MetConfiguration incoming = this.secureIncoming(2, null, "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, null);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeJsonUsesHttpsMutualAuthentication() throws Exception {
        // 绑定 proto 的原生 gRPC JSON 在双向 TLS 上传输。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void httpProtobufUsesHttpsHttp1ServerAuthentication() throws Exception {
        // 裸 Protobuf 在 HTTP/1 HTTPS 上认证服务端。
        MetConfiguration incoming = this.secureIncoming(1, "proto", "NONE");
        MetConfiguration outgoing = this.secureOutgoing(1, "proto", null);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void httpProtobufUsesHttpsHttp1MutualAuthentication() throws Exception {
        // 裸 Protobuf 在 HTTP/1 HTTPS 上认证双方。
        MetConfiguration incoming = this.secureIncoming(1, "proto", "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(1, "proto", "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void httpProtobufUsesHttpsHttp2ServerAuthentication() throws Exception {
        // 裸 Protobuf 的普通 HTTP 调用使用 h2 与服务端身份认证。
        MetConfiguration incoming = this.secureIncoming(2, "proto", "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, "proto", null);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void httpProtobufUsesHttpsHttp2MutualAuthentication() throws Exception {
        // 裸 Protobuf 的普通 HTTP 调用同样可使用 h2 双向 TLS。
        MetConfiguration incoming = this.secureIncoming(2, "proto", "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, "proto", "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 两端启动后发布和订阅，客户端没有本地实现可走本地调用。
            server.start();
            client.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleUnaryService> service = client.publisher().rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 公开客户端完成真实网络调用，验证内容和服务端计算结果。
            TripleUnaryService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            TripleMessage response = remote.echo(TripleMessage.of("Triple 安全调用", 7));
            assertEquals("Triple 安全调用", response.getText());
            assertEquals(8, response.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeProtobufResponseStreamUsesMutualTls() throws Exception {
        // 原生 Protobuf 响应流在双向 TLS 上逐条完成并结束。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 服务容器启动后发布和订阅流式接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 订阅公开代理返回的 Publisher，完成信号证明流正常排空。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            List<TripleMessage> messages = new ArrayList<>();
            MetCaller.consume(remote.count(TripleMessage.of("TLS stream", 3)), messages::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, messages.size());
            assertEquals("TLS stream", messages.get(0).getText());
            assertEquals(2, messages.get(2).getNumber());
        }
    }

    @Test(timeout = 20000)
    public void wrappedHessian2ResponseStreamUsesMutualTls() throws Exception {
        // Hessian2 wrapper 响应流在双向 TLS 上保持消息边界。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 服务容器启动后发布和订阅流式接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2") //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2") //
                    .register();

            // 订阅公开代理返回的 Publisher，完成信号证明流正常排空。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            List<TripleMessage> messages = new ArrayList<>();
            MetCaller.consume(remote.count(TripleMessage.of("TLS stream", 3)), messages::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, messages.size());
            assertEquals("TLS stream", messages.get(0).getText());
            assertEquals(2, messages.get(2).getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeProtobufRequestStreamUsesMutualTls() throws Exception {
        // 原生 Protobuf 的请求流通过双向 TLS 后由服务端汇总。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布和订阅直接使用 Publisher 的 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 通过公开流工具产生有界源，业务结果证明消息方向与终止信号正确。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            List<TripleMessage> requests = List.of(TripleMessage.of("first", 6), TripleMessage.of("second", 7));
            Flow.Publisher<TripleMessage> publisher = MetCaller.fromIterator(requests::iterator);
            TripleMessage result = remote.collect(publisher);
            assertEquals("sum", result.getText());
            assertEquals(13, result.getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeProtobufBidirectionalStreamUsesMutualTls() throws Exception {
        // 原生 Protobuf 的双向流在 TLS 上独立完成两个收发方向。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布和订阅直接使用 Publisher 的 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();

            // 通过公开流工具产生有界源，业务结果证明消息方向与终止信号正确。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            List<TripleMessage> requests = List.of(TripleMessage.of("first", 6), TripleMessage.of("second", 7));
            Flow.Publisher<TripleMessage> publisher = MetCaller.fromIterator(requests::iterator);
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.chat(publisher), responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("first", responses.get(0).getText());
            assertEquals(7, responses.get(1).getNumber());
        }
    }

    @Test(timeout = 20000)
    public void wrappedHessian2BidirectionalStreamUsesMutualTls() throws Exception {
        // Hessian2 wrapper 的双向流在 TLS 上保持逐条 DTO 类型。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布和订阅直接使用 Publisher 的 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2") //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2") //
                    .register();

            // 通过公开流工具产生有界源，业务结果证明消息方向与终止信号正确。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            List<TripleMessage> requests = List.of(TripleMessage.of("first", 6), TripleMessage.of("second", 7));
            Flow.Publisher<TripleMessage> publisher = MetCaller.fromIterator(requests::iterator);
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.chat(publisher), responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, responses.size());
            assertEquals("first", responses.get(0).getText());
            assertEquals(7, responses.get(1).getNumber());
        }
    }

    @Test(timeout = 20000)
    public void nativeJsonResponseStreamUsesMutualTls() throws Exception {
        // 原生 gRPC JSON 响应流在双向 TLS 上传递多条消息。
        MetConfiguration incoming = this.secureIncoming(2, null, "REQUIRE");
        MetConfiguration outgoing = this.secureOutgoing(2, null, "client");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 先启动容器，再发布和订阅直接使用 Publisher 的 Java 接口。
            server.start();
            client.start();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Json") //
                    .bindResource("triple", resource) //
                    .register();

            // 通过公开流工具产生有界源，业务结果证明消息方向与终止信号正确。
            TripleFlowService remote = client.getRsfClient(server.getListenAddress("triple")).getRemote(service);
            List<TripleMessage> responses = new ArrayList<>();
            MetCaller.consume(remote.count(TripleMessage.of("TLS JSON", 3)), responses::add).get(5, TimeUnit.SECONDS);
            assertEquals(3, responses.size());
            assertEquals("TLS JSON", responses.get(0).getText());
            assertEquals(2, responses.get(2).getNumber());
        }
    }

    @Test(timeout = 20000)
    public void independentClientUpgradesHttpJsonCallToH2c() throws Exception {
        // 同一明文端口先接收 HTTP/1 Upgrade，再执行 HTTP/2 的普通 JSON RPC。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("triple") //
                .contextPath("/rpc").option("httpMode", "json");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 独立 JDK 客户端只使用公开协议参数数组，不调用插件内部编解码器。
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "http"), "h2c");
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 实际响应版本证明完成了升级，而不是默默退回 HTTP/1。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            JsonNode message = JSON.readTree(response.body());
            assertEquals("h2c", message.path("text").asText());
            assertEquals(8, message.path("number").asInt());
        }
    }

    @Test(timeout = 20000)
    public void independentHttpsClientNegotiatesHttp2WithAlpn() throws Exception {
        // 服务端只宣告 h2，独立客户端验证信任链后协商 HTTP/2。
        MetConfiguration configuration = this.secureIncoming(2, "json", "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // Triple 普通 JSON 请求采用按参数顺序排列的数组。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_2).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "https"), "ALPN h2");
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            // 校验真实协商版本和业务结果。
            assertEquals(HttpClient.Version.HTTP_2, response.version());
            assertEquals(200, response.statusCode());
            JsonNode message = JSON.readTree(response.body());
            assertEquals("ALPN h2", message.path("text").asText());
        }
    }

    @Test(timeout = 20000)
    public void independentHttpsClientNegotiatesHttp1WithAlpn() throws Exception {
        // HTTP/1.1 的 TLS 端点明确通过 ALPN 宣告其应用协议。
        MetConfiguration configuration = this.secureIncoming(1, "json", "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 独立 TLS 客户端严格校验主机名并同时提出两个 HTTP 协议。
            SSLContext client = clientContext("trust", null);
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "h2", "http/1.1" });
                socket.setSSLParameters(parameters);
                socket.setSoTimeout(5000);
                socket.connect(new InetSocketAddress("127.0.0.1", server.getListenAddress("triple").getPort()), 5000);
                socket.startHandshake();
                assertEquals("http/1.1", socket.getApplicationProtocol());
            }
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsRejectsMissingClientCertificate() throws Exception {
        // Triple 端点要求调用方身份，匿名连接不能进入 HTTP/2 业务层。
        MetConfiguration configuration = this.secureIncoming(2, null, "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Hessian2").register();

            // 直接观察 TLS 拒绝，避免 HTTP 重试把认证失败掩盖成业务超时。
            SSLContext client = clientContext("trust", null);
            try (SSLSocket socket = (SSLSocket) client.getSocketFactory().createSocket()) {
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                parameters.setApplicationProtocols(new String[] { "h2" });
                socket.setSSLParameters(parameters);
                socket.connect(new InetSocketAddress("127.0.0.1", server.getListenAddress("triple").getPort()), 5000);

                // TLS 1.3 的拒绝可晚于本地握手返回；不得获得应用层字节。
                boolean rejected;
                try {
                    socket.startHandshake();
                    rejected = socket.getInputStream().read() < 0;
                } catch (IOException failure) {
                    boolean timedOut = failure instanceof SocketTimeoutException;
                    assertFalse("TLS rejection must not time out: " + failure, timedOut);
                    rejected = true;
                }
                assertTrue("Anonymous client must not receive HTTP/2 application data", rejected);
            }
        }
    }

    @Test(timeout = 20000)
    public void mutualTlsRejectsUntrustedClientCertificate() throws Exception {
        // 客户端虽有证书，但签发者不属于服务端信任集。
        MetConfiguration configuration = this.secureIncoming(1, "json", "REQUIRE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 合法 Triple 请求必须以认证异常结束，而不是等待业务期限。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(clientContext("trust", "untrusted-client")) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "https"), "rejected");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsUntrustedServerCertificate() throws Exception {
        // 客户端只信任另一 CA，不能向当前 HTTPS 端点发送 Triple 正文。
        MetConfiguration configuration = this.secureIncoming(1, "json", "NONE");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 直接验证握手异常，不将 RPC 超时当作证书校验的证据。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(clientContext("untrusted-trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "https"), "rejected");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void clientRejectsWrongServerHostname() throws Exception {
        // 受信证书的名称不属于访问地址，HTTPS 主机名校验应拒绝该连接。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        ConnectorSettings listener = configuration.connector("http").bind("127.0.0.1", 0);
        serverTls(listener, 1, "NONE");
        listener.option("tlsKeyStore", "classpath:/certs/acceptance/wrong-host-server.p12");
        listener.protocol("triple").addressScheme("https").contextPath("/rpc").option("httpMode", "json");
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 独立客户端保留 JDK 默认主机名校验，错误必须发生在业务响应之前。
            HttpClient client = HttpClient.newBuilder() //
                    .sslContext(clientContext("trust", null)) //
                    .version(HttpClient.Version.HTTP_1_1).build();
            HttpRequest request = this.unaryRequest(this.uri(server, "https"), "rejected");
            IOException failure = assertThrows(IOException.class, () -> {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            });
            assertFalse(failure instanceof HttpTimeoutException);
        }
    }

    @Test(timeout = 20000)
    public void incompatibleAlpnFailsBeforeRpcTimeout() throws Exception {
        // 服务端只接受 HTTP/1.1，Triple 客户端只接受 h2。
        MetConfiguration incoming = this.secureIncoming(1, "json", "NONE");
        MetConfiguration outgoing = this.secureOutgoing(2, "json", null);
        outgoing.setDefaultTimeout(10000);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();
            MetBindInfo<TripleDemoService> service = client.publisher().rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService").serialize("Json").register();

            // 协商错误必须及时传给 Caller，不能挂到十秒 RPC 截止时间。
            MetClient remote = client.getRsfClient(server.getListenAddress("triple"));
            Class<?>[] types = { TripleMessage.class };
            Object[] arguments = { TripleMessage.of("mismatch", 7) };
            MetFuture pending = remote.asyncInvoke(service, "echo", types, arguments);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertFalse(failure.getCause() instanceof MetTimeoutException);
        }
    }

    private MetConfiguration secureIncoming(int httpVersion, String mode, String clientAuth) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        ConnectorSettings endpoint = configuration.connector("http").bind("127.0.0.1", 0);
        endpoint.option("httpVersion", httpVersion).option("streamBody", true);
        serverTls(endpoint, httpVersion, clientAuth);
        var protocol = endpoint.protocol("triple").addressScheme("https").contextPath("/rpc");
        if (mode != null) {
            protocol.option("httpMode", mode);
        }
        return configuration;
    }

    private MetConfiguration secureOutgoing(int httpVersion, String mode, String identity) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        ConnectorSettings endpoint = configuration.connector("http").option("httpVersion", httpVersion);
        endpoint.option("streamBody", true);
        clientTls(endpoint, httpVersion, identity);
        var protocol = endpoint.protocol("triple").addressScheme("https").contextPath("/rpc");
        if (mode != null) {
            protocol.option("httpMode", mode);
        }
        return configuration;
    }

    private URI uri(MetContext context, String scheme) {
        int port = context.getListenAddress("triple").getPort();
        String path = "/rpc/meteor.triple.demo.TripleDemoService/echo";
        return URI.create(scheme + "://127.0.0.1:" + port + path);
    }

    private HttpRequest unaryRequest(URI uri, String text) {
        String json = "[{\"text\":\"" + text + "\",\"number\":7}]";
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/json") //
                .header("tri-protocol-version", "1") //
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private static void serverTls(ConnectorSettings connector, int version, String clientAuth) {
        connector.option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsKeyStore", "classpath:/certs/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", clientAuth) //
                .option("tlsAlpn", version == 2 ? "h2" : "http/1.1");
    }

    private static void clientTls(ConnectorSettings connector, int version, String identity) {
        connector.option("tlsEnabled", true) //
                .option("httpScheme", "https") //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsAlpn", version == 2 ? "h2" : "http/1.1");
        if (identity != null) {
            connector.option("tlsKeyStore", "classpath:/certs/acceptance/" + identity + ".p12") //
                    .option("tlsKeyStorePassword", "meteor-test");
        }
    }

    private static SSLContext clientContext(String trustStore, String identity) throws Exception {
        // 独立客户端只信任指定测试 CA，双向认证再载入自己的身份。
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(loadStore(trustStore));
        KeyManagerFactory keys = null;
        if (identity != null) {
            keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(loadStore(identity), "meteor-test".toCharArray());
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys == null ? null : keys.getKeyManagers(), trust.getTrustManagers(), null);
        return context;
    }

    private static KeyStore loadStore(String name) throws Exception {
        String resource = "/certs/acceptance/" + name + ".p12";
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream input = TripleTransportScenarioTest.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing TLS test resource: " + resource);
            }
            store.load(input, "meteor-test".toCharArray());
        }
        return store;
    }
}
