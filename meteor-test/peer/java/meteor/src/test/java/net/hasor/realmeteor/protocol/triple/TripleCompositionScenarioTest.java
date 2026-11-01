/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.bootstrap.MetConfiguration.ConnectorSettings;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Timeout;

/** Triple 与既有协议共享端口时，协议与服务上下文各自保持隔离。 */
@NxnRpc(name = "triple")
public class TripleCompositionScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @NxnRpc(name = "triple")
    @NxnRpc(name = "grpc")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void httpJsonAndGrpcJsonUseSeparateContextsOnOneHttpPort() throws Exception {
        // 一个物理 HTTP 监听分别挂载普通 Triple JSON 与标准 gRPC JSON。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("triple");
        ConnectorSettings endpoint = incoming.connector("http").bind("127.0.0.1", 0);
        endpoint.protocol("triple").contextPath("/triple").option("httpMode", "json");
        endpoint.protocol("grpc").contextPath("/grpc");
        MetConfiguration triple = new MetConfiguration().setDefaultProtocol("triple");
        triple.connector("http").protocol("triple").contextPath("/triple").option("httpMode", "json");
        MetConfiguration grpc = new MetConfiguration().setDefaultProtocol("grpc");
        grpc.connector("http").option("httpVersion", 2).protocol("grpc").contextPath("/grpc");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext tripleClient = triple.buildContext(); //
             MetContext grpcClient = grpc.buildContext()) {
            // 容器全部启动以后发布同一业务接口，各客户端只订阅自己的协议。
            server.start();
            tripleClient.start();
            grpcClient.start();
            server.publisher().rsfService(TripleUnaryService.class, new TripleUnaryServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .aliasName("grpc", "meteor.triple.demo.TripleUnaryService") //
                    .protocol("triple", "grpc").serialize("Json").bindResource("triple", resource) //
                    .bindResource("grpc", resource).register();
            MetBindInfo<TripleUnaryService> tripleService = tripleClient.publisher() //
                    .rsfService(TripleUnaryService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleUnaryService") //
                    .protocol("triple").serialize("Json").bindResource("triple", resource).register();
            MetBindInfo<TripleUnaryService> grpcService = grpcClient.publisher() //
                    .rsfService(TripleUnaryService.class) //
                    .aliasName("grpc", "meteor.triple.demo.TripleUnaryService") //
                    .protocol("grpc").serialize("Json").bindResource("grpc", resource).register();

            // 两个公开地址复用监听端口，调用仍使用各自报文格式与路径。
            int port = server.getListenAddress("triple").getPort();
            assertEquals(port, server.getListenAddress("grpc").getPort());
            TripleUnaryService tripleRemote = tripleClient.getRsfClient(server.getListenAddress("triple")) //
                    .getRemote(tripleService);
            TripleUnaryService grpcRemote = grpcClient.getRsfClient(server.getListenAddress("grpc")) //
                    .getRemote(grpcService);
            TripleMessage tripleResult = tripleRemote.echo(TripleMessage.of("Triple HTTP", 7));
            TripleMessage grpcResult = grpcRemote.echo(TripleMessage.of("gRPC JSON", 11));
            assertEquals("Triple HTTP", tripleResult.getText());
            assertEquals(8, tripleResult.getNumber());
            assertEquals("gRPC JSON", grpcResult.getText());
            assertEquals(12, grpcResult.getNumber());
        }
    }

    @NxnRpc(name = "triple")
    @NxnRpc(name = "grpc")
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void wrappedTripleAndNativeGrpcShareOneMutualTlsPort() throws Exception {
        // 同一 h2 双向 TLS 监听分别承载 Triple wrapper 与原生 Protobuf gRPC。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("triple");
        ConnectorSettings endpoint = incoming.connector("http").bind("127.0.0.1", 0);
        this.serverTls(endpoint);
        endpoint.protocol("triple").addressScheme("https").contextPath("/triple");
        endpoint.protocol("grpc").addressScheme("https").contextPath("/grpc");
        MetConfiguration triple = new MetConfiguration().setDefaultProtocol("triple");
        ConnectorSettings tripleSender = triple.connector("http");
        this.clientTls(tripleSender);
        tripleSender.protocol("triple").addressScheme("https").contextPath("/triple");
        MetConfiguration grpc = new MetConfiguration().setDefaultProtocol("grpc");
        ConnectorSettings grpcSender = grpc.connector("http");
        this.clientTls(grpcSender);
        grpcSender.protocol("grpc").addressScheme("https").contextPath("/grpc");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/protocol/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext tripleClient = triple.buildContext(); //
             MetContext grpcClient = grpc.buildContext()) {
            // 两种服务各自声明协议，避免使用错误序列化器进行交叉发布。
            server.start();
            tripleClient.start();
            grpcClient.start();
            server.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .protocol("triple").serialize("Hessian2").register();
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("grpc", "meteor.triple.demo.TripleFlowService") //
                    .protocol("grpc").serialize("Protobuf").bindResource("grpc", resource).register();
            MetBindInfo<TripleDemoService> tripleService = tripleClient.publisher() //
                    .rsfService(TripleDemoService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> grpcService = grpcClient.publisher() //
                    .rsfService(TripleFlowService.class) //
                    .aliasName("grpc", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf").bindResource("grpc", resource).register();

            // 证书与端口共用，应用协议通过上下文独立分派。
            int port = server.getListenAddress("triple").getPort();
            assertEquals(port, server.getListenAddress("grpc").getPort());
            TripleDemoService tripleRemote = tripleClient.getRsfClient(server.getListenAddress("triple")) //
                    .getRemote(tripleService);
            TripleFlowService grpcRemote = grpcClient.getRsfClient(server.getListenAddress("grpc")) //
                    .getRemote(grpcService);
            assertEquals("TripleTriple", tripleRemote.greet("Triple", 2));
            TripleMessage result = grpcRemote.echo(TripleMessage.of("gRPC TLS", 17));
            assertEquals("gRPC TLS", result.getText());
            assertEquals(18, result.getNumber());
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void explicitlyMountedServiceIsNotVisibleAtDefaultContext() throws Exception {
        // 协议有默认上下文与可选上下文，服务只选择后者。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").bind("127.0.0.1", 0).protocol("triple") //
                .contextPath("/default").addContextPath("/isolated").option("httpMode", "json");
        TripleDemoServiceImpl implementation = new TripleDemoServiceImpl();
        try (MetContext server = configuration.buildContext()) {
            server.start();
            server.publisher().rsfService(TripleDemoService.class, implementation) //
                    .aliasName("triple", "meteor.triple.demo.TripleDemoService") //
                    .contextPathByPrefix("triple", "/isolated").serialize("Json").register();

            // 独立客户端访问同一端口的两条公开路径，只有显式挂载可以找到服务。
            int port = server.getListenAddress("triple").getPort();
            String base = "http://127.0.0.1:" + port;
            String method = "/meteor.triple.demo.TripleDemoService/echo";
            HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
            URI isolated = URI.create(base + "/isolated" + method);
            HttpResponse<byte[]> selected = client.send(this.unaryRequest(isolated), //
                    HttpResponse.BodyHandlers.ofByteArray());
            URI defaultContext = URI.create(base + "/default" + method);
            HttpResponse<byte[]> hidden = client.send(this.unaryRequest(defaultContext), //
                    HttpResponse.BodyHandlers.ofByteArray());

            // 默认路径不会触发业务对象，实际成功调用次数仍为一。
            assertEquals(200, selected.statusCode());
            JsonNode message = JSON.readTree(selected.body());
            assertEquals("isolated", message.path("text").asText());
            assertEquals(404, hidden.statusCode());
            assertEquals(1, implementation.getCalls());
        }
    }

    private HttpRequest unaryRequest(URI uri) {
        String json = "[{\"text\":\"isolated\",\"number\":7}]";
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)) //
                .header("Content-Type", "application/json") //
                .header("tri-protocol-version", "1") //
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
    }

    private void serverTls(ConnectorSettings connector) {
        connector.option("httpVersion", 2).option("streamBody", true) //
                .option("tlsEnabled", true).option("httpScheme", "https") //
                .option("tlsKeyStore", "classpath:/certs/acceptance/server.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test") //
                .option("tlsClientAuth", "REQUIRE").option("tlsAlpn", "h2");
    }

    private void clientTls(ConnectorSettings connector) {
        connector.option("httpVersion", 2).option("streamBody", true) //
                .option("tlsEnabled", true).option("httpScheme", "https") //
                .option("tlsKeyStore", "classpath:/certs/acceptance/client.p12") //
                .option("tlsKeyStorePassword", "meteor-test") //
                .option("tlsTrustStore", "classpath:/certs/acceptance/trust.p12") //
                .option("tlsTrustStorePassword", "meteor-test").option("tlsAlpn", "h2");
    }
}
