/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.triple;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleFlowServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class TripleMetadataScenarioTest {
    @Test(timeout = 15000)
    public void compressedWrapperUnaryPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 双方启用 gzip，应用元数据与协议保留字段分别处理。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 公开过滤器读请求元数据，并独立追加响应头和结束尾部。
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata received = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), received.getHeader("x-values"));
                assertArrayEquals(new byte[] { 0, -1 }, received.getBinaryHeader("x-values-bin").get(0));
                GrpcMetadata sent = GrpcMetadata.from(response);
                sent.addHeader("x-values", "head-1");
                sent.addHeader("x-values", "head-2");
                sent.addTrailer("x-values", "tail-1");
                sent.addTrailer("x-values", "tail-2");
                sent.addBinaryTrailer("x-values-bin", new byte[] { 1, -1 });
                chain.doFilter(request, response);
            });
            client.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata sent = GrpcMetadata.from(request);
                sent.addHeader("x-values", "one");
                sent.addHeader("x-values", "two");
                sent.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                chain.doFilter(request, response);
            });

            // 过滤器与服务均从公开发布入口安装，不直接构造内部协议会话。
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Hessian2").register();
            var caller = client.getRsfClient(server.getListenAddress("triple"));

            // 压缩正文不改变重复头部、二进制值与 unary 尾部的用户语义。
            String text = "Triple 压缩消息".repeat(4096);
            Class<?>[] types = { TripleMessage.class };
            Object[] arguments = { TripleMessage.of(text, 4) };
            MetResponse response = caller.asyncInvoke(service, "echo", types, arguments).get(5, TimeUnit.SECONDS);
            TripleMessage result = (TripleMessage) response.getData();
            assertEquals(text, result.getText());
            assertEquals(5, result.getNumber());
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            assertArrayEquals(new byte[] { 1, -1 }, metadata.getBinaryTrailer("x-values-bin").get(0));
        }
    }

    @Test(timeout = 15000)
    public void compressedProtobufBidirectionalStreamPreservesFinalMetadata() throws Exception {
        // 双方启用 gzip，应用元数据与协议保留字段分别处理。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 公开过滤器读请求元数据，并独立追加响应头和结束尾部。
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata received = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), received.getHeader("x-values"));
                assertArrayEquals(new byte[] { 0, -1 }, received.getBinaryHeader("x-values-bin").get(0));
                GrpcMetadata sent = GrpcMetadata.from(response);
                sent.addHeader("x-values", "head-1");
                sent.addHeader("x-values", "head-2");
                sent.addTrailer("x-values", "tail-1");
                sent.addTrailer("x-values", "tail-2");
                sent.addBinaryTrailer("x-values-bin", new byte[] { 1, -1 });
                chain.doFilter(request, response);
            });
            client.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata sent = GrpcMetadata.from(request);
                sent.addHeader("x-values", "one");
                sent.addHeader("x-values", "two");
                sent.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                chain.doFilter(request, response);
            });

            // 过滤器与服务均从公开发布入口安装，不直接构造内部协议会话。
            server.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            MetBindInfo<TripleFlowService> service = client.publisher().rsfService(TripleFlowService.class) //
                    .aliasName("triple", "meteor.triple.demo.TripleFlowService") //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource).register();
            var caller = client.getRsfClient(server.getListenAddress("triple"));

            // 尾部要等流结束后才读取；正文的消息边界与顺序不能被压缩破坏。
            String text = "压缩双向流".repeat(4096);
            List<TripleMessage> messages = List.of(TripleMessage.of(text, 2), TripleMessage.of(text, 3));
            Class<?>[] types = { Flow.Publisher.class };
            Object[] arguments = { MetCaller.fromIterable(messages) };
            MetResponse response = caller.asyncInvoke(service, "chat", types, arguments).get(5, TimeUnit.SECONDS);
            Flow.Publisher<?> stream = (Flow.Publisher<?>) response.getData();
            List<TripleMessage> values = new ArrayList<>();
            MetCaller.consume(stream, message -> values.add((TripleMessage) message)).get(5, TimeUnit.SECONDS);
            assertEquals(2, values.size());
            assertEquals(text, values.get(0).getText());
            assertEquals(2, values.get(0).getNumber());
            assertEquals(3, values.get(1).getNumber());
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            assertArrayEquals(new byte[] { 1, -1 }, metadata.getBinaryTrailer("x-values-bin").get(0));
        }
    }

    private MetConfiguration configuration(boolean listener) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (listener) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("triple").contextPath("/rpc") //
                .option("streamBody", true).option("compression", "gzip");
        return configuration;
    }
}
