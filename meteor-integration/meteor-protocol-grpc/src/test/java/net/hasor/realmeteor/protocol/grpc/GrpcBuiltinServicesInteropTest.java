/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import io.grpc.*;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.reflection.v1.ExtensionRequest;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import net.hasor.example.protocol.grpc.DescribedService;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.builtin.GrpcBuiltinServicesFactory;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcBuiltinServicesInteropTest {

    @Test
    public void standardServicesAreNotInstalledByDefault() throws Exception {
        // 只挂载协议，不安装标准服务扩展。
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").bind("127.0.0.1", 0) // 共用 HTTP 端点，挂载 gRPC。
                .protocol("grpc").option("streamBody", true);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 协议启动不会隐式发布 Health 或 Reflection 服务。
            assertTrue(context.getServiceIDs().isEmpty());
            assertTrue(context.getServiceIDs("grpc").isEmpty());
        }
    }

    @Test(timeout = 20000)
    public void reflectionResolvesNestedProtoSymbols() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布包含嵌套符号和 proto2 扩展的服务，供标准反射接口查询。
            URL proto = this.getClass().getResource("/proto/reflection-fixture.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var service = context.publisher().rsfService(DescribedService.class, value -> value);
            service.aliasName("grpc", "fixture.ReflectionFixture") // 协议名称与 proto 服务描述保持一致。
                    .bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 使用官方客户端建立双向反射流，逐项接收查询结果。
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod();
                var call = channel.newCall(method, CallOptions.DEFAULT);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 同一组描述覆盖方法、嵌套消息、字段、oneof、枚举和扩展符号。
                List<String> symbols = List.of( // 按符号种类逐项验证文件定位结果。
                        "fixture.ReflectionFixture.inspect", // 方法
                        "fixture.Shape.Nested", // 嵌套消息
                        "fixture.Shape.Nested.value", // 字段
                        "fixture.Shape.choice", // oneof
                        "fixture.Shape.Kind", // 枚举
                        "fixture.note"); // 扩展
                for (String symbol : symbols) {
                    ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 定位符号所在的 proto。
                            .setFileContainingSymbol(symbol).build();
                    requests.onNext(query);
                    ServerReflectionResponse result = replies.next();
                    assertTrue(symbol + ": " + result, result.hasFileDescriptorResponse());
                }

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionListsProto2ExtensionNumbers() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布包含嵌套符号和 proto2 扩展的服务，供标准反射接口查询。
            URL proto = this.getClass().getResource("/proto/reflection-fixture.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var service = context.publisher().rsfService(DescribedService.class, value -> value);
            service.aliasName("grpc", "fixture.ReflectionFixture") // 协议名称与 proto 服务描述保持一致。
                    .bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 使用官方客户端建立双向反射流，逐项接收查询结果。
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod();
                var call = channel.newCall(method, CallOptions.DEFAULT);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 查询已声明扩展的容器，返回全部扩展字段号。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 按被扩展的消息类型查询。
                        .setAllExtensionNumbersOfType("fixture.Shape").build();
                requests.onNext(query);
                var response = replies.next().getAllExtensionNumbersResponse();
                assertEquals(List.of(100, 101), response.getExtensionNumberList());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionFindsTheFileContainingAProto2Extension() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布包含嵌套符号和 proto2 扩展的服务，供标准反射接口查询。
            URL proto = this.getClass().getResource("/proto/reflection-fixture.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var service = context.publisher().rsfService(DescribedService.class, value -> value);
            service.aliasName("grpc", "fixture.ReflectionFixture") // 协议名称与 proto 服务描述保持一致。
                    .bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 使用官方客户端建立双向反射流，逐项接收查询结果。
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod();
                var call = channel.newCall(method, CallOptions.DEFAULT);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 按容器类型和扩展字段号定位声明该扩展的 proto 文件。
                ExtensionRequest extension = ExtensionRequest.newBuilder() // 指定 proto2 扩展坐标。
                        .setContainingType("fixture.Shape").setExtensionNumber(101).build();
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 外层反射请求携带扩展坐标。
                        .setFileContainingExtension(extension).build();
                requests.onNext(query);
                assertTrue(replies.next().hasFileDescriptorResponse());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionRejectsUnknownExtensionContainer() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布包含嵌套符号和 proto2 扩展的服务，供标准反射接口查询。
            URL proto = this.getClass().getResource("/proto/reflection-fixture.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var service = context.publisher().rsfService(DescribedService.class, value -> value);
            service.aliasName("grpc", "fixture.ReflectionFixture") // 协议名称与 proto 服务描述保持一致。
                    .bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 使用官方客户端建立双向反射流，逐项接收查询结果。
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod();
                var call = channel.newCall(method, CallOptions.DEFAULT);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 扩展容器不存在时返回 NOT_FOUND，不能当作没有扩展的已知类型。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 构造当前查询条件。
                        .setAllExtensionNumbersOfType("missing.Type").build();
                requests.onNext(query);
                assertEquals(5, replies.next().getErrorResponse().getErrorCode());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthUsesItsExactRootPathAlongsideBusinessPrefixes() throws Exception {
        // 只启用 Health，并让两个协议共用端口且各自使用独立路径。
        GrpcBuiltinServicesFactory services = new GrpcBuiltinServicesFactory();
        services.setReflection(false);
        MetConfiguration configuration = new MetConfiguration();
        var http = configuration.connector("http").bind("127.0.0.1", 0);
        http.protocol("grpc").contextPath("/rpc").option("streamBody", true);
        http.protocol("hprose").contextPath("/hprose");
        configuration.addExtension(services);
        try (MetContext context = configuration.buildContext()) {
            context.start();

            // 端口保持共享，标准服务仅发布已启用的 Health。
            int grpcPort = context.getListenAddress("grpc").getPort();
            int hprosePort = context.getListenAddress("hprose").getPort();
            assertEquals(grpcPort, hprosePort);
            assertEquals(1, context.getServiceIDs().size());
            ManagedChannel channel = this.channel(context);
            try {
                // 业务使用 /rpc，官方客户端仍按固定的标准方法地址检查实例状态。
                var method = HealthGrpc.getCheckMethod();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                HealthCheckRequest request = HealthCheckRequest.getDefaultInstance();
                var response = ClientCalls.blockingUnaryCall(channel, method, options, request);
                assertEquals(ServingStatus.SERVING, response.getStatus());
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthCheckReportsTheServingInstance() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();
            ManagedChannel channel = this.channel(context);
            try {
                var health = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);

                // 空服务名查询整个实例，启动后的实例应处于可服务状态。
                HealthCheckResponse response = health.check(HealthCheckRequest.getDefaultInstance());
                assertEquals(ServingStatus.SERVING, response.getStatus());
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthCheckRejectsAnUnpublishedService() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();
            ManagedChannel channel = this.channel(context);
            try {
                var health = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                HealthCheckRequest unknown = HealthCheckRequest.newBuilder().setService("example.Echo").build();

                // 普通 Check 对未发布服务返回 NOT_FOUND，不返回实例的整体状态。
                var missing = assertThrows(StatusRuntimeException.class, () -> health.check(unknown));
                assertEquals(Status.Code.NOT_FOUND, missing.getStatus().getCode());
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthListIncludesPublishedStandardServices() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();
            ManagedChannel channel = this.channel(context);
            try {
                // List 同时返回实例整体状态和三个独立发布的标准服务。
                Map<String, Integer> statuses = this.healthList(channel);
                Set<String> expected = Set.of("", "grpc.health.v1.Health", // 实例整体状态及 Health 服务。
                        "grpc.reflection.v1.ServerReflection", "grpc.reflection.v1alpha.ServerReflection");
                assertEquals(expected, statuses.keySet());
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthWatchObservesServicePublication() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();
            ManagedChannel channel = this.channel(context);
            try {
                HealthCheckRequest unknown = HealthCheckRequest.newBuilder().setService("example.Echo").build();

                // 未发布服务的 Watch 先返回 SERVICE_UNKNOWN，发布后仍在原 stream 上更新。
                Reply<HealthCheckResponse> replies = new Reply<>();
                var call = channel.newCall(HealthGrpc.getWatchMethod(), CallOptions.DEFAULT);
                ClientCalls.asyncServerStreamingCall(call, unknown, replies);
                assertEquals(ServingStatus.SERVICE_UNKNOWN, replies.next().getStatus());

                // 在同一条 Watch 流建立后发布服务，观察状态变化。
                URL proto = this.getClass().getResource("/proto/echo.proto");
                GrpcProtoResource resource = new GrpcProtoResource(proto);
                var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
                publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
                assertEquals(ServingStatus.SERVING, replies.next().getStatus());

                // 本场景已收到期望状态，主动取消持续订阅以释放流。
                call.cancel("watch complete", null);
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthWatchTerminatesWhenContextGoesOffline() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();
            ManagedChannel channel = this.channel(context);
            try {
                HealthCheckRequest unknown = HealthCheckRequest.newBuilder().setService("example.Echo").build();

                // 未发布服务的 Watch 先返回 SERVICE_UNKNOWN，发布后仍在原 stream 上更新。
                Reply<HealthCheckResponse> replies = new Reply<>();
                var call = channel.newCall(HealthGrpc.getWatchMethod(), CallOptions.DEFAULT);
                ClientCalls.asyncServerStreamingCall(call, unknown, replies);
                assertEquals(ServingStatus.SERVICE_UNKNOWN, replies.next().getStatus());

                // 在同一条 Watch 流建立后发布服务，观察状态变化。
                URL proto = this.getClass().getResource("/proto/echo.proto");
                GrpcProtoResource resource = new GrpcProtoResource(proto);
                var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
                publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
                assertEquals(ServingStatus.SERVING, replies.next().getStatus());

                // 下线关闭监听和既有入站连接，原 Watch 流随连接一起结束。
                context.offline();
                assertNull(context.getListenAddress("grpc"));
                replies.terminated();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthCheckIsAvailableAfterContextReturnsOnline() throws Exception {
        // 从 bootstrap 启动 gRPC，并发布一个可由标准 Health 查询的服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();

            // 完成一次离线和上线后，使用当前监听地址建立新的官方客户端连接。
            context.offline();
            assertNull(context.getListenAddress("grpc"));
            context.online();
            ManagedChannel channel = this.channel(context);
            try {
                var health = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                HealthCheckRequest request = HealthCheckRequest.newBuilder().setService("example.Echo").build();
                assertEquals(ServingStatus.SERVING, health.check(request).getStatus());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void healthWatchCancellationLeavesUnaryChecksAvailable() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();
            ManagedChannel channel = this.channel(context);
            try {
                var health = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                HealthCheckRequest unknown = HealthCheckRequest.newBuilder().setService("example.Echo").build();

                // 未发布服务的 Watch 先返回 SERVICE_UNKNOWN，发布后仍在原 stream 上更新。
                Reply<HealthCheckResponse> replies = new Reply<>();
                var call = channel.newCall(HealthGrpc.getWatchMethod(), CallOptions.DEFAULT);
                ClientCalls.asyncServerStreamingCall(call, unknown, replies);
                assertEquals(ServingStatus.SERVICE_UNKNOWN, replies.next().getStatus());

                // 在同一条 Watch 流建立后发布服务，观察状态变化。
                URL proto = this.getClass().getResource("/proto/echo.proto");
                GrpcProtoResource resource = new GrpcProtoResource(proto);
                var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
                publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
                assertEquals(ServingStatus.SERVING, replies.next().getStatus());

                // 取消 Watch 只结束该流，同一连接上的普通 Check 仍然可用。
                call.cancel("watch complete", null);
                Status cancellation = Status.fromThrowable(replies.failure());
                assertEquals(Status.Code.CANCELLED, cancellation.getCode());
                assertEquals(ServingStatus.SERVING, health.check(unknown).getStatus());
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1ListsServices() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 列表只包含本上下文已发布的协议服务，包含两个标准反射版本。
                ServerReflectionRequest listQuery = ServerReflectionRequest.newBuilder().setListServices("").build();
                requests.onNext(listQuery);
                List<String> names = replies.next().getListServicesResponse().getServiceList() // 查询完整服务列表。
                        .stream().map(value -> value.getName()).sorted().toList();
                List<String> expected = List.of("example.Echo", "grpc.health.v1.Health", // 业务服务及 Health 服务。
                        "grpc.reflection.v1.ServerReflection", "grpc.reflection.v1alpha.ServerReflection");
                assertEquals(expected, names);

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1IncludesImportedDescriptors() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 查询服务声明，同时要求返回它引用的消息描述文件。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 按 proto 服务符号查询。
                        .setFileContainingSymbol("example.Echo").build();
                requests.onNext(query);
                ServerReflectionResponse descriptor = replies.next();
                assertEquals(query, descriptor.getOriginalRequest());

                // 将返回的描述按文件名索引，分别检查主文件与导入文件。
                Map<String, FileDescriptorProto> files = new HashMap<>();
                var descriptors = descriptor.getFileDescriptorResponse().getFileDescriptorProtoList();
                for (var bytes : descriptors) {
                    FileDescriptorProto file = FileDescriptorProto.parseFrom(bytes);
                    files.put(file.getName(), file);
                }
                assertTrue(files.containsKey("echo.proto"));
                assertTrue(files.containsKey("message.proto"));

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1FindsFileByName() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 按导入文件名直接查询其描述，不依赖服务符号定位。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 构造当前查询条件。
                        .setFileByFilename("message.proto").build();
                requests.onNext(query);
                assertTrue(replies.next().hasFileDescriptorResponse());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1ReportsEmptyExtensionNumbers() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 已知消息没有扩展时仍返回扩展查询响应。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 构造当前查询条件。
                        .setAllExtensionNumbersOfType("example.Message").build();
                requests.onNext(query);
                assertTrue(replies.next().hasAllExtensionNumbersResponse());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1ContinuesAfterUnknownSymbol() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 先查询不存在的符号，确认单次请求返回 NOT_FOUND。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 构造当前查询条件。
                        .setFileContainingSymbol("missing.Type").build();
                requests.onNext(query);
                assertEquals(5, replies.next().getErrorResponse().getErrorCode());

                // 单次查询错误保留 stream，可继续查询并正常半关闭。
                ServerReflectionRequest listQuery = ServerReflectionRequest.newBuilder().setListServices("").build();
                requests.onNext(listQuery);
                assertEquals(4, replies.next().getListServicesResponse().getServiceCount());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1AlphaListsServices() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 列表只包含本上下文已发布的协议服务，包含两个标准反射版本。
                ServerReflectionRequest listQuery = ServerReflectionRequest.newBuilder().setListServices("").build();
                requests.onNext(listQuery);
                List<String> names = replies.next().getListServicesResponse().getServiceList() // 查询完整服务列表。
                        .stream().map(value -> value.getName()).sorted().toList();
                List<String> expected = List.of("example.Echo", "grpc.health.v1.Health", // 业务服务及 Health 服务。
                        "grpc.reflection.v1.ServerReflection", "grpc.reflection.v1alpha.ServerReflection");
                assertEquals(expected, names);

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1AlphaIncludesImportedDescriptors() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 查询服务声明，同时要求返回它引用的消息描述文件。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 按 proto 服务符号查询。
                        .setFileContainingSymbol("example.Echo").build();
                requests.onNext(query);
                ServerReflectionResponse descriptor = replies.next();
                assertEquals(query, descriptor.getOriginalRequest());

                // 将返回的描述按文件名索引，分别检查主文件与导入文件。
                Map<String, FileDescriptorProto> files = new HashMap<>();
                var descriptors = descriptor.getFileDescriptorResponse().getFileDescriptorProtoList();
                for (var bytes : descriptors) {
                    FileDescriptorProto file = FileDescriptorProto.parseFrom(bytes);
                    files.put(file.getName(), file);
                }
                assertTrue(files.containsKey("echo.proto"));
                assertTrue(files.containsKey("message.proto"));

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1AlphaFindsFileByName() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 按导入文件名直接查询其描述，不依赖服务符号定位。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 构造当前查询条件。
                        .setFileByFilename("message.proto").build();
                requests.onNext(query);
                assertTrue(replies.next().hasFileDescriptorResponse());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1AlphaReportsEmptyExtensionNumbers() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 已知消息没有扩展时仍返回扩展查询响应。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 构造当前查询条件。
                        .setAllExtensionNumbersOfType("example.Message").build();
                requests.onNext(query);
                assertTrue(replies.next().hasAllExtensionNumbersResponse());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void reflectionV1AlphaContinuesAfterUnknownSymbol() throws Exception {
        // 从 bootstrap 启动 gRPC，并显式安装 Health 和 Reflection 标准服务。
        try (MetContext context = this.configuration().buildContext()) {
            context.start();

            // 发布带有跨文件消息引用的服务，供反射查询服务和描述文件。
            URL proto = this.getClass().getResource("/proto/echo.proto");
            GrpcProtoResource resource = new GrpcProtoResource(proto);
            var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
            publication.aliasName("grpc", "example.Echo").bindResource(resource).register();
            ManagedChannel channel = this.channel(context);
            try {
                // 按指定反射版本建立官方双向流，限定本次调用的等待时间。
                String methodName = "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留官方消息编解码。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var call = channel.newCall(method, options);
                Reply<ServerReflectionResponse> replies = new Reply<>();
                var requests = ClientCalls.asyncBidiStreamingCall(call, replies);

                // 先查询不存在的符号，确认单次请求返回 NOT_FOUND。
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 构造当前查询条件。
                        .setFileContainingSymbol("missing.Type").build();
                requests.onNext(query);
                assertEquals(5, replies.next().getErrorResponse().getErrorCode());

                // 单次查询错误保留 stream，可继续查询并正常半关闭。
                ServerReflectionRequest listQuery = ServerReflectionRequest.newBuilder().setListServices("").build();
                requests.onNext(listQuery);
                assertEquals(4, replies.next().getListServicesResponse().getServiceCount());

                // 结束请求方向，确认反射流正常完成。
                requests.onCompleted();
                replies.completed();
            } finally {
                // 在关闭 Meteor 上下文前释放官方客户端连接。
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void standardServicesRejectConfigurationWithoutGrpcMount() {
        // 安装标准服务扩展，但不配置它依赖的 gRPC 挂载点。
        MetConfiguration configuration = new MetConfiguration();
        configuration.addExtension(new GrpcBuiltinServicesFactory());

        // 错误应在构建阶段暴露，调用方能看到缺少挂载点的具体原因。
        IllegalStateException failure = assertThrows(IllegalStateException.class, configuration::buildContext);
        assertTrue(failure.getCause().getMessage().contains("requires a gRPC protocol mount"));
    }

    private Map<String, Integer> healthList(ManagedChannel channel) throws Exception {
        // 当前官方 Java stub 尚无新版 List；独立按标准字段号解析其 map 报文。
        MethodDescriptor.Marshaller<byte[]> bytes = new MethodDescriptor.Marshaller<>() {
            public InputStream stream(byte[] value) {
                return new ByteArrayInputStream(value);
            }

            public byte[] parse(InputStream input) {
                try {
                    return input.readAllBytes();
                } catch (IOException failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };

        // 构造标准 List 方法，使用原始字节与官方服务互通。
        var method = MethodDescriptor.<byte[], byte[]>newBuilder();
        method.setType(MethodDescriptor.MethodType.UNARY);
        method.setFullMethodName("grpc.health.v1.Health/List");
        method.setRequestMarshaller(bytes).setResponseMarshaller(bytes);
        CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
        byte[] result = ClientCalls.blockingUnaryCall(channel, method.build(), options, new byte[0]);

        // 按 map entry 的字段号读取服务名称与状态，跳过未识别字段。
        CodedInputStream input = CodedInputStream.newInstance(result);
        Map<String, Integer> statuses = new HashMap<>();
        while (!input.isAtEnd()) {
            assertEquals(10, input.readTag());
            CodedInputStream entry = CodedInputStream.newInstance(input.readByteArray());
            String name = "";
            int status = 0;
            while (!entry.isAtEnd()) {
                int tag = entry.readTag();
                if (tag == 10) {
                    name = entry.readString();
                } else if (tag == 18) {
                    status = HealthCheckResponse.parseFrom(entry.readByteArray()).getStatusValue();
                } else {
                    entry.skipField(tag);
                }
            }
            statuses.put(name, status);
        }
        return statuses;
    }

    private MetConfiguration configuration() {
        // 公共准备只提供配置，实际上下文的启动和关闭保留在各场景中。
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0) // 标准服务与业务服务共用根挂载点。
                .protocol("grpc").contextPath("/").option("streamBody", true);
        configuration.addExtension(new GrpcBuiltinServicesFactory());
        return configuration;
    }

    private ManagedChannel channel(MetContext context) {
        // 使用实际绑定的临时端口创建官方明文 HTTP/2 客户端。
        int port = context.getListenAddress("grpc").getPort();
        return NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
    }

    private static final class Reply<T> implements StreamObserver<T> {
        private final BlockingQueue<Object> values = new LinkedBlockingQueue<>();
        private final Object                end    = new Object();

        public void onNext(T value) {
            this.values.add(value);
        }

        public void onError(Throwable failure) {
            this.values.add(failure);
        }

        public void onCompleted() {
            this.values.add(this.end);
        }

        @SuppressWarnings("unchecked")
        T next() throws Exception {
            // 有界等待下一条消息，将异步错误和提前结束转换成当前用例失败。
            Object value = this.values.poll(5, TimeUnit.SECONDS);
            assertNotNull("Missing message", value);
            if (value instanceof Throwable failure) {
                throw new AssertionError(failure);
            }
            assertNotSame(this.end, value);
            return (T) value;
        }

        Throwable failure() throws Exception {
            // 故障场景必须观察到流失败，不能把无响应当作期望结果。
            Object value = this.values.poll(5, TimeUnit.SECONDS);
            assertTrue("Expected stream failure: " + value, value instanceof Throwable);
            return (Throwable) value;
        }

        void completed() throws Exception {
            // 半关闭后必须收到终止通知，不能残留未消费消息。
            assertSame(this.end, this.values.poll(5, TimeUnit.SECONDS));
        }

        void terminated() throws Exception {
            // 离线时 Health 可以先递送 NOT_SERVING，随后由入站连接排空完成流终止。
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Object value = null;
            while (System.nanoTime() < deadline) {
                long remaining = deadline - System.nanoTime();
                value = this.values.poll(remaining, TimeUnit.NANOSECONDS);
                if (value == this.end || value instanceof Throwable) {
                    return;
                }
            }
            fail("Expected stream termination: " + value);
        }
    }
}
