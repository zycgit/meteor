/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.MetadataService;
import net.hasor.example.protocol.grpc.MetadataServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcMetadataInteropTest extends GrpcMetadataPeer {
    @Test(timeout = 15000)
    public void http2RejectsNonGrpcContentTypeBeforeServiceInvocation() throws Exception {
        // 启动真实 gRPC 监听，随后使用 HTTP/2 对端提交错误的 Content-Type。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 从正式发布入口注册服务及该场景所需的元数据过滤器。
            context.publisher() //
                    .rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            // 仅构造普通 JSON 正文，协议应在调用服务之前返回 415。
            int port = context.bindAddress("grpc").getPort();
            URI uri = URI.create("http://127.0.0.1:" + port + "/example.Metadata/echo");
            HttpClient peer = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
            HttpRequest request = HttpRequest.newBuilder(uri) //
                    .header("content-type", "application/json") //
                    .POST(HttpRequest.BodyPublishers.ofString("{}")) //
                    .build();
            // 按经过时间限制等待，避免 JDK HTTP 请求计时受系统时钟调整影响。
            var pending = peer.sendAsync(request, BodyHandlers.ofByteArray());
            try {
                var response = pending.get(5, TimeUnit.SECONDS);
                assertEquals(HttpClient.Version.HTTP_2, response.version());
                assertEquals(415, response.statusCode());
            } finally {
                pending.cancel(true);
            }
        }
    }

    @Test(timeout = 30000)
    public void trailersAddedAfterResponseConversionReachTheOfficialClient() throws Exception {
        // 在返回流被订阅时才写入 trailers，观察官方客户端最终收到的元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 从正式发布入口注册服务及该场景所需的元数据过滤器。
            context.publisher() //
                    .bindFilter("late-metadata", (request, response, chain) -> {
                        chain.doFilter(request, response);
                        GrpcMetadata metadata = GrpcMetadata.from(response);
                        @SuppressWarnings("unchecked") // 响应声明固定为 Message 流。
                        Flow.Publisher<Message> values = (Flow.Publisher<Message>) response.getData();

                        // 协议接到响应 Payload 并订阅返回流后才设置 trailers。
                        Flow.Publisher<Message> stream = subscriber -> {
                            metadata.addTrailer("x-values", "after-response");
                            values.subscribe(subscriber);
                        };
                        response.sendData(stream);
                    });
            context.publisher() //
                    .rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            // 官方客户端连接实际监听地址，验证线上报文的字段顺序和值。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                Result result = call(peer, "list", MethodDescriptor.MethodType.SERVER_STREAMING, 2);
                assertTrue(result.status.toString(), result.status.isOk());
                assertEquals(2, result.values.size());
                assertEquals(List.of("after-response"), values(result.trailers, TEXT));

                // JSON 选项是插件内部表示，不能作为额外的 HTTP 元数据发送。
                var headerNames = result.headers.keys();
                var trailerNames = result.trailers.keys();
                assertFalse(headerNames.contains("grpc-metadata-headers"));
                assertFalse(trailerNames.contains("grpc-metadata-trailers"));
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialUnaryPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 官方客户端发起普通调用，验证重复和二进制元数据的双向传递。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 从正式发布入口注册服务及该场景所需的元数据过滤器。
            context.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        // 校验请求中的重复 ASCII 头和二进制头。
                        GrpcMetadata incoming = GrpcMetadata.from(request);
                        assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                        List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                        assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                        assertArrayEquals(new byte[0], binaryHeaders.get(1));

                        // 响应头与 trailers 分别携带独立的重复值。
                        GrpcMetadata outgoing = GrpcMetadata.from(response);
                        outgoing.addHeader("x-values", "head-1");
                        outgoing.addHeader("x-values", "head-2");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                        outgoing.addTrailer("x-values", "tail-1");
                        outgoing.addTrailer("x-values", "tail-2");
                        outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                        chain.doFilter(request, response);
                    });
            context.publisher() //
                    .rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            // 官方客户端连接实际监听地址，验证线上报文的字段顺序和值。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                Result result = call(peer, "echo", MethodDescriptor.MethodType.UNARY, 2);
                assertTrue(result.status.isOk());
                assertFalse(result.values.isEmpty());
                assertEquals(List.of("head-1", "head-2"), values(result.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(result.trailers, TEXT));
                assertArrayEquals(new byte[] { 1, -1 }, result.headers.get(BINARY));
                assertArrayEquals(new byte[] { 2, -1 }, result.trailers.get(BINARY));
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialServerStreamPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 官方客户端读取响应流，验证头部与流结束 trailers 均保留重复值。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 从正式发布入口注册服务及该场景所需的元数据过滤器。
            context.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        // 校验请求中的重复 ASCII 头和二进制头。
                        GrpcMetadata incoming = GrpcMetadata.from(request);
                        assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                        List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                        assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                        assertArrayEquals(new byte[0], binaryHeaders.get(1));

                        // 响应头与 trailers 分别携带独立的重复值。
                        GrpcMetadata outgoing = GrpcMetadata.from(response);
                        outgoing.addHeader("x-values", "head-1");
                        outgoing.addHeader("x-values", "head-2");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                        outgoing.addTrailer("x-values", "tail-1");
                        outgoing.addTrailer("x-values", "tail-2");
                        outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                        chain.doFilter(request, response);
                    });
            context.publisher() //
                    .rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            // 官方客户端连接实际监听地址，验证线上报文的字段顺序和值。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                Result result = call(peer, "list", MethodDescriptor.MethodType.SERVER_STREAMING, 2);
                assertTrue(result.status.isOk());
                assertFalse(result.values.isEmpty());
                assertEquals(List.of("head-1", "head-2"), values(result.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(result.trailers, TEXT));
                assertArrayEquals(new byte[] { 1, -1 }, result.headers.get(BINARY));
                assertArrayEquals(new byte[] { 2, -1 }, result.trailers.get(BINARY));
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientStreamPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 官方客户端发送请求流，验证 Meteor 收到并回传完整元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 从正式发布入口注册服务及该场景所需的元数据过滤器。
            context.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        // 校验请求中的重复 ASCII 头和二进制头。
                        GrpcMetadata incoming = GrpcMetadata.from(request);
                        assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                        List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                        assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                        assertArrayEquals(new byte[0], binaryHeaders.get(1));

                        // 响应头与 trailers 分别携带独立的重复值。
                        GrpcMetadata outgoing = GrpcMetadata.from(response);
                        outgoing.addHeader("x-values", "head-1");
                        outgoing.addHeader("x-values", "head-2");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                        outgoing.addTrailer("x-values", "tail-1");
                        outgoing.addTrailer("x-values", "tail-2");
                        outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                        chain.doFilter(request, response);
                    });
            context.publisher() //
                    .rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            // 官方客户端连接实际监听地址，验证线上报文的字段顺序和值。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                Result result = call(peer, "collect", MethodDescriptor.MethodType.CLIENT_STREAMING, 2);
                assertTrue(result.status.isOk());
                assertFalse(result.values.isEmpty());
                assertEquals(List.of("head-1", "head-2"), values(result.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(result.trailers, TEXT));
                assertArrayEquals(new byte[] { 1, -1 }, result.headers.get(BINARY));
                assertArrayEquals(new byte[] { 2, -1 }, result.trailers.get(BINARY));
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialBidirectionalStreamPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 官方客户端进行双向流调用，验证元数据不受流式正文影响。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 从正式发布入口注册服务及该场景所需的元数据过滤器。
            context.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        // 校验请求中的重复 ASCII 头和二进制头。
                        GrpcMetadata incoming = GrpcMetadata.from(request);
                        assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                        List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                        assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                        assertArrayEquals(new byte[0], binaryHeaders.get(1));

                        // 响应头与 trailers 分别携带独立的重复值。
                        GrpcMetadata outgoing = GrpcMetadata.from(response);
                        outgoing.addHeader("x-values", "head-1");
                        outgoing.addHeader("x-values", "head-2");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                        outgoing.addTrailer("x-values", "tail-1");
                        outgoing.addTrailer("x-values", "tail-2");
                        outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                        chain.doFilter(request, response);
                    });
            context.publisher() //
                    .rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            // 官方客户端连接实际监听地址，验证线上报文的字段顺序和值。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                Result result = call(peer, "exchange", MethodDescriptor.MethodType.BIDI_STREAMING, 2);
                assertTrue(result.status.isOk());
                assertFalse(result.values.isEmpty());
                assertEquals(List.of("head-1", "head-2"), values(result.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(result.trailers, TEXT));
                assertArrayEquals(new byte[] { 1, -1 }, result.headers.get(BINARY));
                assertArrayEquals(new byte[] { 2, -1 }, result.trailers.get(BINARY));
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorUnaryPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 启动官方元数据对端，由 Meteor 普通调用读取其头部和 trailers。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 请求过滤器添加重复头部，再通过发布 API 订阅业务接口。
            client.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        GrpcMetadata outgoing = GrpcMetadata.from(request);
                        outgoing.addHeader("x-values", "one");
                        outgoing.addHeader("x-values", "two");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                        outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                        chain.doFilter(request, response);
                    });
            MetBindInfo<MetadataService> info = client.publisher() //
                    .rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);
            Object argument = Message.of("z".repeat(10000), 2);
            Class<?> parameter = Message.class;

            // 发起普通请求，并从公开响应对象读取元数据。
            Class<?>[] parameterTypes = { parameter };
            Object[] arguments = { argument };
            var pending = caller.asyncInvoke(info, "echo", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            if (response.getData() instanceof Flow.Publisher<?> stream) {
                // 流句柄先返回；读到结束后同一个元数据对象可读取 trailers。
                var completion = MetCaller.consume(stream, value -> assertNotNull(value));
                completion.get(5, TimeUnit.SECONDS);
            }
            // 调用或流结束后，响应元数据仍保留二进制值和 trailers 的顺序。
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            byte[] binaryHeader = metadata.getBinaryHeader("x-values-bin").get(0);
            byte[] binaryTrailer = metadata.getBinaryTrailer("x-values-bin").get(0);
            assertArrayEquals(new byte[] { 1, -1 }, binaryHeader);
            assertArrayEquals(new byte[] { 2, -1 }, binaryTrailer);
        } finally {
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorServerStreamPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 启动官方元数据对端，由 Meteor 读取响应流及结束元数据。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 请求过滤器添加重复头部，再通过发布 API 订阅业务接口。
            client.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        GrpcMetadata outgoing = GrpcMetadata.from(request);
                        outgoing.addHeader("x-values", "one");
                        outgoing.addHeader("x-values", "two");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                        outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                        chain.doFilter(request, response);
                    });
            MetBindInfo<MetadataService> info = client.publisher() //
                    .rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);
            Object argument = Message.of("z".repeat(10000), 2);
            Class<?> parameter = Message.class;

            // 发起返回流的请求，响应头先于流结束到达。
            Class<?>[] parameterTypes = { parameter };
            Object[] arguments = { argument };
            var pending = caller.asyncInvoke(info, "list", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            if (response.getData() instanceof Flow.Publisher<?> stream) {
                // 流句柄先返回；读到结束后同一个元数据对象可读取 trailers。
                var completion = MetCaller.consume(stream, value -> assertNotNull(value));
                completion.get(5, TimeUnit.SECONDS);
            }
            // 调用或流结束后，响应元数据仍保留二进制值和 trailers 的顺序。
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            byte[] binaryHeader = metadata.getBinaryHeader("x-values-bin").get(0);
            byte[] binaryTrailer = metadata.getBinaryTrailer("x-values-bin").get(0);
            assertArrayEquals(new byte[] { 1, -1 }, binaryHeader);
            assertArrayEquals(new byte[] { 2, -1 }, binaryTrailer);
        } finally {
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientStreamPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 启动官方元数据对端，由 Meteor 请求流上传数据并读取元数据。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 请求过滤器添加重复头部，再通过发布 API 订阅业务接口。
            client.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        GrpcMetadata outgoing = GrpcMetadata.from(request);
                        outgoing.addHeader("x-values", "one");
                        outgoing.addHeader("x-values", "two");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                        outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                        chain.doFilter(request, response);
                    });
            MetBindInfo<MetadataService> info = client.publisher() //
                    .rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);
            Object argument = Message.of("z".repeat(10000), 2);
            Class<?> parameter = Message.class;

            argument = MetCaller.fromIterable(List.of(argument, argument));
            parameter = Flow.Publisher.class;

            // 上传完整请求流，再检查单个响应中的元数据。
            Class<?>[] parameterTypes = { parameter };
            Object[] arguments = { argument };
            var pending = caller.asyncInvoke(info, "collect", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            if (response.getData() instanceof Flow.Publisher<?> stream) {
                // 流句柄先返回；读到结束后同一个元数据对象可读取 trailers。
                var completion = MetCaller.consume(stream, value -> assertNotNull(value));
                completion.get(5, TimeUnit.SECONDS);
            }
            // 调用或流结束后，响应元数据仍保留二进制值和 trailers 的顺序。
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            byte[] binaryHeader = metadata.getBinaryHeader("x-values-bin").get(0);
            byte[] binaryTrailer = metadata.getBinaryTrailer("x-values-bin").get(0);
            assertArrayEquals(new byte[] { 1, -1 }, binaryHeader);
            assertArrayEquals(new byte[] { 2, -1 }, binaryTrailer);
        } finally {
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorBidirectionalStreamPreservesRepeatedAndBinaryMetadata() throws Exception {
        // 启动官方元数据对端，由 Meteor 双向流完成数据与元数据交换。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 请求过滤器添加重复头部，再通过发布 API 订阅业务接口。
            client.publisher() //
                    .bindFilter("metadata", (request, response, chain) -> {
                        GrpcMetadata outgoing = GrpcMetadata.from(request);
                        outgoing.addHeader("x-values", "one");
                        outgoing.addHeader("x-values", "two");
                        outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                        outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                        chain.doFilter(request, response);
                    });
            MetBindInfo<MetadataService> info = client.publisher() //
                    .rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);
            Object argument = Message.of("z".repeat(10000), 2);
            Class<?> parameter = Message.class;

            argument = MetCaller.fromIterable(List.of(argument, argument));
            parameter = Flow.Publisher.class;

            // 交换请求与响应流，读取头部后继续消费正文。
            Class<?>[] parameterTypes = { parameter };
            Object[] arguments = { argument };
            var pending = caller.asyncInvoke(info, "exchange", parameterTypes, arguments);
            MetResponse response = pending.get(5, TimeUnit.SECONDS);
            GrpcMetadata metadata = GrpcMetadata.from(response);
            assertEquals(List.of("head-1", "head-2"), metadata.getHeader("x-values"));
            if (response.getData() instanceof Flow.Publisher<?> stream) {
                // 流句柄先返回；读到结束后同一个元数据对象可读取 trailers。
                var completion = MetCaller.consume(stream, value -> assertNotNull(value));
                completion.get(5, TimeUnit.SECONDS);
            }
            // 调用或流结束后，响应元数据仍保留二进制值和 trailers 的顺序。
            assertEquals(List.of("tail-1", "tail-2"), metadata.getTrailer("x-values"));
            byte[] binaryHeader = metadata.getBinaryHeader("x-values-bin").get(0);
            byte[] binaryTrailer = metadata.getBinaryTrailer("x-values-bin").get(0);
            assertArrayEquals(new byte[] { 1, -1 }, binaryHeader);
            assertArrayEquals(new byte[] { 2, -1 }, binaryTrailer);
        } finally {
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
