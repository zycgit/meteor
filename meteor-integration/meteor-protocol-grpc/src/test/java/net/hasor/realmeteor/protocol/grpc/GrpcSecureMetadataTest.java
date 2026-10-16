/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.hasor.example.protocol.grpc.*;
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

/** 安全传输中的压缩消息保留重复、二进制头部和调用结束 trailers。 */
public class GrpcSecureMetadataTest {

    @Test(timeout = 20000)
    public void jsonTlsGzipUnaryPreservesMetadata() throws Exception {
        // 双方启用 TLS、h2 ALPN 和 gRPC gzip，并准备重复与二进制元数据。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 从公开过滤器验证入站元数据，并分别设置响应头部与结束 trailers。
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata received = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), received.getHeader("x-values"));
                assertArrayEquals(new byte[] { 0, -1 }, received.getBinaryHeader("x-values-bin").get(0));
                GrpcMetadata sent = GrpcMetadata.from(response);
                sent.addHeader("x-values", "head-1");
                sent.addHeader("x-values", "head-2");
                sent.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                sent.addTrailer("x-values", "tail-1");
                sent.addTrailer("x-values", "tail-2");
                sent.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            });
            client.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata sent = GrpcMetadata.from(request);
                sent.addHeader("x-values", "one");
                sent.addHeader("x-values", "two");
                sent.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                chain.doFilter(request, response);
            });

            // 发布和订阅同一编码的业务服务，保留真实客户端入口。
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
            var caller = client.getRsfClient(server.bindAddress("grpc"));

            // 发送可压缩的大消息，等待调用完成后检查正文及最终 trailers。
            String payload = "gzip安全消息".repeat(4096);
            Class<?>[] parameterTypes = { Message.class };
            Object[] arguments = { Message.of(payload, 7) };
            var pending = caller.asyncInvoke(info, "echo", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            Message result = (Message) response.getData();
            assertEquals(payload, result.getValue());
            assertEquals(8, result.getCount());
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            assertArrayEquals(new byte[] { 1, -1 }, metadata.getBinaryHeader("x-values-bin").get(0));
            assertArrayEquals(new byte[] { 2, -1 }, metadata.getBinaryTrailer("x-values-bin").get(0));
        }
    }

    @Test(timeout = 20000)
    public void jsonTlsGzipBidirectionalStreamPreservesMetadata() throws Exception {
        // 双方启用 TLS、h2 ALPN 和 gRPC gzip，并准备重复与二进制元数据。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 从公开过滤器验证入站元数据，并分别设置响应头部与结束 trailers。
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata received = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), received.getHeader("x-values"));
                assertArrayEquals(new byte[] { 0, -1 }, received.getBinaryHeader("x-values-bin").get(0));
                GrpcMetadata sent = GrpcMetadata.from(response);
                sent.addHeader("x-values", "head-1");
                sent.addHeader("x-values", "head-2");
                sent.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                sent.addTrailer("x-values", "tail-1");
                sent.addTrailer("x-values", "tail-2");
                sent.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            });
            client.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata sent = GrpcMetadata.from(request);
                sent.addHeader("x-values", "one");
                sent.addHeader("x-values", "two");
                sent.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                chain.doFilter(request, response);
            });

            // 发布和订阅同一编码的业务服务，保留真实客户端入口。
            MessageService implementation = new MessageService();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Json") //
                    .register();
            var caller = client.getRsfClient(server.bindAddress("grpc"));

            // 发送可压缩的大消息，等待调用完成后检查正文及最终 trailers。
            String payload = "gzip安全消息".repeat(4096);
            Message first = Message.of(payload, 7);
            Message second = Message.of(payload, 8);
            List<Message> requests = List.of(first, second);
            Class<?>[] parameterTypes = { Flow.Publisher.class };
            Object[] arguments = { MetCaller.fromIterable(requests) };
            var pending = caller.asyncInvoke(info, "exchange", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            Flow.Publisher<?> stream = (Flow.Publisher<?>) response.getData();
            List<Message> results = new ArrayList<>();
            MetCaller.consume(stream, value -> results.add((Message) value)).get(5, TimeUnit.SECONDS);
            assertEquals(2, results.size());
            assertEquals(payload, results.get(0).getValue());
            assertEquals(7, results.get(0).getCount());
            assertEquals(payload, results.get(1).getValue());
            assertEquals(8, results.get(1).getCount());
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            assertArrayEquals(new byte[] { 1, -1 }, metadata.getBinaryHeader("x-values-bin").get(0));
            assertArrayEquals(new byte[] { 2, -1 }, metadata.getBinaryTrailer("x-values-bin").get(0));
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsGzipUnaryPreservesMetadata() throws Exception {
        // 双方启用 TLS、h2 ALPN 和 gRPC gzip，并准备重复与二进制元数据。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 从公开过滤器验证入站元数据，并分别设置响应头部与结束 trailers。
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata received = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), received.getHeader("x-values"));
                assertArrayEquals(new byte[] { 0, -1 }, received.getBinaryHeader("x-values-bin").get(0));
                GrpcMetadata sent = GrpcMetadata.from(response);
                sent.addHeader("x-values", "head-1");
                sent.addHeader("x-values", "head-2");
                sent.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                sent.addTrailer("x-values", "tail-1");
                sent.addTrailer("x-values", "tail-2");
                sent.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            });
            client.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata sent = GrpcMetadata.from(request);
                sent.addHeader("x-values", "one");
                sent.addHeader("x-values", "two");
                sent.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                chain.doFilter(request, response);
            });

            // 发布和订阅同一编码的业务服务，保留真实客户端入口。
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
            var caller = client.getRsfClient(server.bindAddress("grpc"));

            // 发送可压缩的大消息，等待调用完成后检查正文及最终 trailers。
            String payload = "gzip安全消息".repeat(4096);
            Class<?>[] parameterTypes = { Message.class };
            Object[] arguments = { Message.of(payload, 7) };
            var pending = caller.asyncInvoke(info, "echo", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            Message result = (Message) response.getData();
            assertEquals(payload, result.getValue());
            assertEquals(8, result.getCount());
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            assertArrayEquals(new byte[] { 1, -1 }, metadata.getBinaryHeader("x-values-bin").get(0));
            assertArrayEquals(new byte[] { 2, -1 }, metadata.getBinaryTrailer("x-values-bin").get(0));
        }
    }

    @Test(timeout = 20000)
    public void protobufTlsGzipBidirectionalStreamPreservesMetadata() throws Exception {
        // 双方启用 TLS、h2 ALPN 和 gRPC gzip，并准备重复与二进制元数据。
        MetConfiguration incoming = this.configuration(true);
        MetConfiguration outgoing = this.configuration(false);
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
        try (MetContext server = incoming.buildContext(); //
             MetContext client = outgoing.buildContext()) {
            server.start();
            client.start();

            // 从公开过滤器验证入站元数据，并分别设置响应头部与结束 trailers。
            server.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata received = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), received.getHeader("x-values"));
                assertArrayEquals(new byte[] { 0, -1 }, received.getBinaryHeader("x-values-bin").get(0));
                GrpcMetadata sent = GrpcMetadata.from(response);
                sent.addHeader("x-values", "head-1");
                sent.addHeader("x-values", "head-2");
                sent.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                sent.addTrailer("x-values", "tail-1");
                sent.addTrailer("x-values", "tail-2");
                sent.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            });
            client.publisher().bindFilter("metadata", (request, response, chain) -> {
                GrpcMetadata sent = GrpcMetadata.from(request);
                sent.addHeader("x-values", "one");
                sent.addHeader("x-values", "two");
                sent.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                chain.doFilter(request, response);
            });

            // 发布和订阅同一编码的业务服务，保留真实客户端入口。
            MessageService implementation = new MessageService();
            server.publisher() //
                    .rsfService(Messages.class, implementation) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            MetBindInfo<Messages> info = client.publisher() //
                    .rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            var caller = client.getRsfClient(server.bindAddress("grpc"));

            // 发送可压缩的大消息，等待调用完成后检查正文及最终 trailers。
            String payload = "gzip安全消息".repeat(4096);
            Message first = Message.of(payload, 7);
            Message second = Message.of(payload, 8);
            List<Message> requests = List.of(first, second);
            Class<?>[] parameterTypes = { Flow.Publisher.class };
            Object[] arguments = { MetCaller.fromIterable(requests) };
            var pending = caller.asyncInvoke(info, "exchange", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            Flow.Publisher<?> stream = (Flow.Publisher<?>) response.getData();
            List<Message> results = new ArrayList<>();
            MetCaller.consume(stream, value -> results.add((Message) value)).get(5, TimeUnit.SECONDS);
            assertEquals(2, results.size());
            assertEquals(payload, results.get(0).getValue());
            assertEquals(7, results.get(0).getCount());
            assertEquals(payload, results.get(1).getValue());
            assertEquals(8, results.get(1).getCount());
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            assertArrayEquals(new byte[] { 1, -1 }, metadata.getBinaryHeader("x-values-bin").get(0));
            assertArrayEquals(new byte[] { 2, -1 }, metadata.getBinaryTrailer("x-values-bin").get(0));
        }
    }

    private MetConfiguration configuration(boolean server) {
        // 只准备无资源配置，服务端身份、CA 校验和压缩都走正式配置入口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        http.option("tlsEnabled", true);
        http.option("httpScheme", "https");
        http.option("tlsAlpn", "h2");
        http.option("tlsTrustStore", "classpath:/tls/acceptance/trust.p12");
        http.option("tlsTrustStorePassword", "meteor-test");
        if (server) {
            http.bind("127.0.0.1", 0);
            http.option("tlsKeyStore", "classpath:/tls/acceptance/server.p12");
            http.option("tlsKeyStorePassword", "meteor-test");
        }
        http.protocol("grpc").addressScheme("https") //
                .option("streamBody", true).option("compression", "gzip");
        return configuration;
    }
}
