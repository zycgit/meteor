/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.MetadataService;
import net.hasor.example.protocol.grpc.MetadataServiceImpl;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static net.hasor.example.protocol.grpc.MetadataServiceImpl.details;
import static org.junit.Assert.*;

public class GrpcStatusInteropTest extends GrpcMetadataPeer {
    @Test(timeout = 15000)
    public void filterRefusalBeforeStreamOutputReachesTheOfficialClient() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 通过用户可配置的过滤器拒绝调用，业务流尚未产生响应头或消息。
            context.publisher().bindFilter("deny", (request, response, chain) -> {
                response.sendStatus(ProtocolStatus.Forbidden, "denied by filter");
            });

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 首条响应产生前拒绝流调用，官方客户端应收到 PERMISSION_DENIED。
                Result failed = call(peer, "list", MethodDescriptor.MethodType.SERVER_STREAMING, 1);
                assertEquals(7, failed.status.getCode().value());
                assertEquals("(403) - denied by filter", failed.status.getDescription());
                assertTrue(failed.values.isEmpty());
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesCancelledWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 1，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 1);
                assertEquals(failed.status.toString(), 1, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(1), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesUnknownWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 2，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 2);
                assertEquals(failed.status.toString(), 2, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(2), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesInvalidArgumentWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 3，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 3);
                assertEquals(failed.status.toString(), 3, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(3), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesDeadlineExceededWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 4，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 4);
                assertEquals(failed.status.toString(), 4, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(4), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesNotFoundWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 5，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 5);
                assertEquals(failed.status.toString(), 5, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(5), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesAlreadyExistsWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 6，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 6);
                assertEquals(failed.status.toString(), 6, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(6), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesPermissionDeniedWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 7，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 7);
                assertEquals(failed.status.toString(), 7, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(7), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesResourceExhaustedWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 8，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 8);
                assertEquals(failed.status.toString(), 8, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(8), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesFailedPreconditionWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 9，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 9);
                assertEquals(failed.status.toString(), 9, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(9), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesAbortedWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 10，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 10);
                assertEquals(failed.status.toString(), 10, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(10), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesOutOfRangeWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 11，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 11);
                assertEquals(failed.status.toString(), 11, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(11), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesUnimplementedWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 12，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 12);
                assertEquals(failed.status.toString(), 12, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(12), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesInternalWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 13，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 13);
                assertEquals(failed.status.toString(), 13, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(13), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesUnavailableWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 14，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 14);
                assertEquals(failed.status.toString(), 14, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(14), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesDataLossWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 15，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 15);
                assertEquals(failed.status.toString(), 15, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(15), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesUnauthenticatedWithDetails() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 触发错误码 16，检查状态、错误详情及初始和末尾元数据。
                Result failed = call(peer, "fail", MethodDescriptor.MethodType.UNARY, 16);
                assertEquals(failed.status.toString(), 16, failed.status.getCode().value());
                assertEquals("failure 中文 + %", failed.status.getDescription());
                assertArrayEquals(details(16), failed.trailers.get(DETAILS));
                Metadata.Key<String> key = Metadata.Key.of("x-error", Metadata.ASCII_STRING_MARSHALLER);
                assertEquals("preserved", failed.trailers.get(key));
                assertEquals(List.of("head-1", "head-2"), values(failed.headers, TEXT));
                assertEquals(List.of("tail-1", "tail-2"), values(failed.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void officialClientReceivesTrailersAfterPartialStreamFailure() throws Exception {
        // 启动支持响应流的 Meteor 服务，供官方客户端读取状态与元数据。
        MetConfiguration configuration = configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 校验客户端传入的重复与二进制头部，并回送初始和末尾元数据。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata incoming = GrpcMetadata.from(request);
                assertEquals(List.of("one", "two"), incoming.getHeader("X-Values"));
                List<byte[]> binaryHeaders = incoming.getBinaryHeader("x-values-bin");
                assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
                assertArrayEquals(new byte[0], binaryHeaders.get(1));

                // 响应的 headers 与 trailers 均保留多值次序。
                GrpcMetadata outgoing = GrpcMetadata.from(response);
                outgoing.addHeader("x-values", "head-1");
                outgoing.addHeader("x-values", "head-2");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 1, -1 });
                outgoing.addTrailer("x-values", "tail-1");
                outgoing.addTrailer("x-values", "tail-2");
                outgoing.addBinaryTrailer("x-values-bin", new byte[] { 2, -1 });
                chain.doFilter(request, response);
            };
            context.publisher().bindFilter("metadata", metadataFilter);

            // 以用户发布 API 注册用于返回指定状态的服务。
            context.publisher().rsfService(MetadataService.class, new MetadataServiceImpl()) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();

            // 官方客户端连接实际绑定端口，所有断言均经过网络链路。
            int port = context.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 第一条消息交付后再失败，状态和详情必须出现在 trailers 中，不能只重置网络流。
                Result late = call(peer, "failStream", MethodDescriptor.MethodType.SERVER_STREAMING, 2);
                assertEquals(1, late.values.size());
                assertEquals(9, late.status.getCode().value());
                assertArrayEquals(details(9), late.trailers.get(DETAILS));
                assertEquals(List.of("tail-1", "tail-2"), values(late.trailers, TEXT));
            } finally {
                // 关闭官方客户端通道，等待底层资源释放。
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesCancelledWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 1，并等待错误由客户端向上传递。
            Message argument = Message.of("", 1);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(1, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(1), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesUnknownWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 2，并等待错误由客户端向上传递。
            Message argument = Message.of("", 2);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(2, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(2), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesInvalidArgumentWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 3，并等待错误由客户端向上传递。
            Message argument = Message.of("", 3);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(3, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(3), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesDeadlineExceededWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 4，并等待错误由客户端向上传递。
            Message argument = Message.of("", 4);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(4, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(4), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesNotFoundWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 5，并等待错误由客户端向上传递。
            Message argument = Message.of("", 5);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(5, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(5), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesAlreadyExistsWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 6，并等待错误由客户端向上传递。
            Message argument = Message.of("", 6);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(6, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(6), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesPermissionDeniedWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 7，并等待错误由客户端向上传递。
            Message argument = Message.of("", 7);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(7, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(7), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesResourceExhaustedWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 8，并等待错误由客户端向上传递。
            Message argument = Message.of("", 8);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(8, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(8), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesFailedPreconditionWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 9，并等待错误由客户端向上传递。
            Message argument = Message.of("", 9);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(9, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(9), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesAbortedWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 10，并等待错误由客户端向上传递。
            Message argument = Message.of("", 10);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(10, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(10), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesOutOfRangeWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 11，并等待错误由客户端向上传递。
            Message argument = Message.of("", 11);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(11, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(11), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesUnimplementedWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 12，并等待错误由客户端向上传递。
            Message argument = Message.of("", 12);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(12, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(12), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesInternalWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 13，并等待错误由客户端向上传递。
            Message argument = Message.of("", 13);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(13, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(13), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesUnavailableWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 14，并等待错误由客户端向上传递。
            Message argument = Message.of("", 14);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(14, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(14), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesDataLossWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 15，并等待错误由客户端向上传递。
            Message argument = Message.of("", 15);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(15, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(15), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesUnauthenticatedWithDetails() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 通过异步调用请求错误码 16，并等待错误由客户端向上传递。
            Message argument = Message.of("", 16);
            var future = caller.asyncInvoke(info, "fail", new Class<?>[] { Message.class }, new Object[] { argument });
            Exception failure = assertThrows(Exception.class, () -> future.get(5, TimeUnit.SECONDS));

            // 校验状态、可读消息、结构化详情和服务端末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(16, grpc.getCode());
            assertEquals("remote 中文 + %", grpc.getDescription());
            assertArrayEquals(details(16), grpc.getDetails());
            List<String> errorTrailers = grpc.getMetadata().getTrailer("x-values");
            assertTrue(errorTrailers.contains("error-2"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void meteorClientReceivesTrailersAfterPartialStreamFailure() throws Exception {
        // 启动会按请求返回指定错误码的官方服务端，再创建 Meteor 客户端。
        Server peer = startMetadataPeer();
        try (MetContext client = configuration(false).buildContext()) {
            client.start();

            // 在出站过滤器中附加重复头部与二进制头部。
            MetFilter metadataFilter = (request, response, chain) -> {
                GrpcMetadata outgoing = GrpcMetadata.from(request);
                outgoing.addHeader("x-values", "one");
                outgoing.addHeader("x-values", "two");
                outgoing.addBinaryHeader("x-values-bin", new byte[] { 0, -1 });
                outgoing.addBinaryHeader("x-values-bin", new byte[0]);
                chain.doFilter(request, response);
            };
            client.publisher().bindFilter("metadata", metadataFilter);

            // 订阅服务并指定官方对端地址，避免走本地调用。
            MetBindInfo<MetadataService> info = client.publisher().rsfService(MetadataService.class) //
                    .aliasName("grpc", "example.Metadata") //
                    .serialize("Json") //
                    .register();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", peer.getPort(), "default");
            var caller = client.getRsfClient(address);

            // 先取得响应流，业务将在交付一条消息后发送错误 trailers。
            Class<?>[] parameterTypes = { Message.class };
            Object[] arguments = { Message.of("first", 1) };
            var responseFuture = caller.asyncInvoke(info, "failStream", parameterTypes, arguments);
            MetResponse response = responseFuture.get(5, TimeUnit.SECONDS);

            // 消费到终止事件，确认已经交付的消息不会被后续失败抹去。
            AtomicInteger count = new AtomicInteger();
            Flow.Publisher<?> stream = (Flow.Publisher<?>) response.getData();
            var consumed = MetCaller.consume(stream, value -> count.incrementAndGet());
            Exception failure = assertThrows(Exception.class, () -> consumed.get(5, TimeUnit.SECONDS));

            // 终止状态与错误详情来自 trailers，原响应仍可读取末尾元数据。
            GrpcStatusException grpc = GrpcStatusException.find(failure);
            assertNotNull(grpc);
            assertEquals(9, grpc.getCode());
            assertEquals(1, count.get());
            assertArrayEquals(details(9), grpc.getDetails());
            assertEquals(List.of("late"), GrpcMetadata.from(response).getTrailer("x-values"));
        } finally {
            // 停止官方服务端，避免残留监听影响后续场景。
            peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }
}
