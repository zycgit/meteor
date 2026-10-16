/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.scenario.example.DemoTlsPipeline;
import net.hasor.meteor.protocol.grpc.scenario.example.Echo;
import net.hasor.meteor.protocol.grpc.scenario.example.EchoImpl;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import org.junit.Test;
import static org.junit.Assert.*;

/** 通过公开 bootstrap 配置协议组合，业务接口与其他互通场景共用。 */
public class GrpcBootstrapTest {
    @Test(timeout = 20000)
    public void oneGrpcMountSupportsJsonAndProtobufServices() throws Exception {
        // 两端挂载同一个 gRPC 协议，服务声明分别决定其序列化方式。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        incoming.connector("http").bind("127.0.0.1", 0).protocol("grpc");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        outgoing.connector("http").option("httpVersion", 2).protocol("grpc");
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 完成容器启动后再发布、订阅业务服务。
            server.start();
            client.start();

            // 每个服务独立选择编码，只有 Protobuf 声明绑定资源。
            var protobuf = server.publisher().rsfService(Echo.class, new EchoImpl()).name("protobuf");
            protobuf.aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            var json = server.publisher().rsfService(Echo.class, new EchoImpl()).name("json");
            json.aliasName("grpc", "example.JsonEcho").serialize("Json").register();
            var protoSubscription = client.publisher().rsfService(Echo.class).name("protobuf");
            protoSubscription //
                    .aliasName("grpc", "example.Echo") //
                    .serialize("Protobuf") //
                    .bindResource(resource);
            MetBindInfo<Echo> protoInfo = protoSubscription.register();
            var jsonSubscription = client.publisher().rsfService(Echo.class).name("json");
            MetBindInfo<Echo> jsonInfo = jsonSubscription //
                    .aliasName("grpc", "example.JsonEcho") //
                    .serialize("Json") //
                    .register();

            // 两种编码共用一个协议挂载点，分别通过公开代理完成调用。
            var caller = client.getRsfClient(server.bindAddress("grpc"));
            Echo binaryRemote = caller.getRemote(protoInfo);
            Echo jsonRemote = caller.getRemote(jsonInfo);
            assertNotNull(protoInfo.getBindResource());
            assertNull(jsonInfo.getBindResource());
            Message protobufResponse = binaryRemote.echo(Message.of("protobuf", 1));
            assertEquals("protobuf", protobufResponse.getValue());
            Message jsonResponse = jsonRemote.echo(Message.of("json", 1));
            assertEquals("json", jsonResponse.getValue());
        }
    }

    @Test(timeout = 20000)
    public void tlsAlpnRoutesGrpcAndHproseOnOneListener() throws Exception {
        // 同一个 TLS 监听通过 ALPN 选择 HTTP 版本，再按路径选择协议。
        MetConfiguration incoming = new MetConfiguration().setDefaultProtocol("grpc");
        var listener = incoming.connector("http").bind("127.0.0.1", 0);
        listener.option("transportPipeline", DemoTlsPipeline.class.getName());
        listener.protocol("grpc").addressScheme("https").contextPath("/rpc");
        listener.protocol("hprose").addressScheme("https").contextPath("/legacy");
        MetConfiguration outgoing = new MetConfiguration().setDefaultProtocol("grpc");
        var grpc = outgoing.connector("http") //
                .option("transportPipeline", DemoTlsPipeline.class.getName());
        grpc.option("httpVersion", 2) //
                .option("tlsAlpn", "h2") //
                .protocol("grpc").addressScheme("https") //
                .contextPath("/rpc");
        var hprose = outgoing.connector("http") //
                .option("transportPipeline", DemoTlsPipeline.class.getName());
        hprose.option("tlsAlpn", "http/1.1") //
                .protocol("hprose").addressScheme("https") //
                .contextPath("/legacy");
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            // 完成容器启动后再发布、订阅业务服务。
            server.start();
            client.start();
            var publication = server.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.SecureEcho").aliasName("Hprose", "secureEcho");
            publication.serialize("Json").register();
            var json = client.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> grpcInfo = json.aliasName("grpc", "example.SecureEcho") //
                    .serialize("Json").protocol("grpc").register();
            var binary = client.publisher().rsfService(Echo.class).name("hprose-client");
            MetBindInfo<Echo> hproseInfo = binary.aliasName("Hprose", "secureEcho") //
                    .serialize("Hprose").protocol("hprose").register();

            // 比较实际监听端口，再分别验证 HTTP/2 和 HTTP/1.1 上的 RPC。
            InterAddress grpcAddress = server.bindAddress("grpc");
            InterAddress hproseAddress = server.bindAddress("hprose");
            assertEquals("https", grpcAddress.getSchema());
            assertEquals(grpcAddress, hproseAddress);
            assertEquals(grpcAddress.getPort(), hproseAddress.getPort());
            Echo overHttp2 = client.getRsfClient(grpcAddress).getRemote(grpcInfo);
            Echo overHttp1 = client.getRsfClient(hproseAddress).getRemote(hproseInfo);
            Message grpcResponse = overHttp2.echo(Message.of("secure HTTP/2", 1));
            assertEquals("secure HTTP/2", grpcResponse.getValue());
            Message hproseResponse = overHttp1.echo(Message.of("secure HTTP/1.1", 1));
            assertEquals("secure HTTP/1.1", hproseResponse.getValue());
        }
    }
}
