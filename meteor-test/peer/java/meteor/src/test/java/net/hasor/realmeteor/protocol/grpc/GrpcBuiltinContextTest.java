/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import net.hasor.meteor.test.nxn.NxnRpc;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.grpc.ReflectionQueryService;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.builtin.GrpcBuiltinServicesFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Timeout;

/** 标准服务使用固定的精确方法地址，不继承业务服务的默认目录。 */
@NxnRpc(name = "grpc")
public class GrpcBuiltinContextTest {
    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void reflectionListsOnlyTheCurrentEndpointsProtoBindings() throws Exception {
        // 一个服务同时接入 JSON 和 Protobuf 两个端点，只有后者具备 proto 描述。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") // 独立物理端点。
                .contextPath("/rpc").option("streamBody", true).option("serializeType", "Json");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(ReflectionQueryService.class, query -> query) //
                    .aliasName("grpc", "example.ReflectionQuery").register();
            int protoPort = context.getListenAddress("http-0", "grpc", "grpc").getPort();
            int jsonPort = context.getListenAddress("http-1", "grpc", "grpc").getPort();
            ManagedChannel proto = NettyChannelBuilder.forAddress("127.0.0.1", protoPort).usePlaintext().build();
            ManagedChannel json = NettyChannelBuilder.forAddress("127.0.0.1", jsonPort).usePlaintext().build();
            try {
                // 分别从真实端点建立标准反射流；每个流固定自己的接入范围。
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                ReflectionReply protoReply = new ReflectionReply();
                ReflectionReply jsonReply = new ReflectionReply();
                var protoRequests = ClientCalls.asyncBidiStreamingCall(proto.newCall(method, options), protoReply);
                var jsonRequests = ClientCalls.asyncBidiStreamingCall(json.newCall(method, options), jsonReply);
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder().setListServices("").build();
                protoRequests.onNext(query);
                jsonRequests.onNext(query);
                var protoServices = protoReply.response.get(5, TimeUnit.SECONDS).getListServicesResponse();
                var jsonServices = jsonReply.response.get(5, TimeUnit.SECONDS).getListServicesResponse();

                // JSON 端点不能借用另一个端点的定义向客户端宣称支持该 Protobuf 服务。
                assertTrue(protoServices.getServiceList().stream() // 核对当前端点的实际定义。
                        .anyMatch(service -> StringUtils.equals(service.getName(), "example.ReflectionQuery")));
                assertFalse(jsonServices.getServiceList().stream() // 独立端点的描述不可交叉。
                        .anyMatch(service -> StringUtils.equals(service.getName(), "example.ReflectionQuery")));
                protoRequests.onCompleted();
                jsonRequests.onCompleted();
                protoReply.completion.get(5, TimeUnit.SECONDS);
                jsonReply.completion.get(5, TimeUnit.SECONDS);
            } finally {
                json.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                proto.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void reflectionDescriptorLookupDoesNotUseAnotherEndpointsDefinition() throws Exception {
        // 首个端点使用 Protobuf，后一个端点只给该业务接口提供 JSON 编码。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") // 保留标准内建服务。
                .contextPath("/rpc").option("streamBody", true).option("serializeType", "Json");
        try (MetContext context = configuration.buildContext()) {
            context.start();
            context.publisher().rsfService(ReflectionQueryService.class, query -> query) //
                    .aliasName("grpc", "example.ReflectionQuery").register();
            int port = context.getListenAddress("http-1", "grpc", "grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 通过 JSON 端点的反射服务查询另一端点才具备的 Protobuf 符号。
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                ReflectionReply replies = new ReflectionReply();
                var requests = ClientCalls.asyncBidiStreamingCall(channel.newCall(method, options), replies);
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder() // 保留标准反射查询。
                        .setFileContainingSymbol("example.ReflectionQuery").build();
                requests.onNext(query);

                // 当前绑定没有该定义，标准查询返回 NOT_FOUND。
                var response = replies.response.get(5, TimeUnit.SECONDS);
                assertEquals(5, response.getErrorResponse().getErrorCode());
                requests.onCompleted();
                replies.completion.get(5, TimeUnit.SECONDS);
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void healthUsesItsStandardExactPath() throws Exception {
        // 业务默认目录为 /rpc，扩展独立声明标准 Health 方法地址。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 未配置根目录，官方客户端仍通过标准精确地址查询实例状态。
                var health = HealthGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS);
                HealthCheckRequest request = HealthCheckRequest.getDefaultInstance();
                var response = health.check(request);
                assertEquals(ServingStatus.SERVING, response.getStatus());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void healthIsNotExposedUnderTheBusinessContext() throws Exception {
        // /rpc 是业务默认目录，不能额外暴露标准 Health 服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 在标准方法前增加业务前缀，调用方必须收到 UNIMPLEMENTED。
                var method = HealthGrpc.getCheckMethod().toBuilder() // 保留标准请求和响应编解码。
                        .setFullMethodName("rpc/grpc.health.v1.Health/Check").build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                HealthCheckRequest request = HealthCheckRequest.getDefaultInstance();
                var failure = assertThrows(StatusRuntimeException.class, // 保留真实调用的失败断言。
                        () -> ClientCalls.blockingUnaryCall(channel, method, options, request));
                assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void reflectionV1UsesItsStandardExactPath() throws Exception {
        // 业务默认目录为 /rpc，Reflection v1 使用标准精确方法地址。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 使用官方 Reflection v1 描述在标准地址建立双向流。
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                ReflectionReply replies = new ReflectionReply();
                var requests = ClientCalls.asyncBidiStreamingCall(channel.newCall(method, options), replies);
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder().setListServices("").build();
                requests.onNext(query);

                // 精确入口返回三个内建服务的完整描述，并正常完成响应流。
                var response = replies.response.get(5, TimeUnit.SECONDS);
                List<String> names = response.getListServicesResponse().getServiceList() // 核对扩展发布内容。
                        .stream().map(service -> service.getName()).sorted().toList();
                assertEquals(this.builtinServiceNames(), names);
                requests.onCompleted();
                replies.completion.get(5, TimeUnit.SECONDS);
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void reflectionV1IsNotExposedUnderTheBusinessContext() throws Exception {
        // Reflection v1 不继承业务默认目录 /rpc。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 增加业务前缀后不能获取反射数据，断言实际协议错误。
                String methodName = "rpc/grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 保留标准消息类型。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                ReflectionReply replies = new ReflectionReply();
                var requests = ClientCalls.asyncBidiStreamingCall(channel.newCall(method, options), replies);
                requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
                ExecutionException failure = assertThrows(ExecutionException.class, // 等待实际协议错误。
                        () -> replies.response.get(5, TimeUnit.SECONDS));
                assertEquals(Status.Code.UNIMPLEMENTED, Status.fromThrowable(failure.getCause()).getCode());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void reflectionV1AlphaUsesItsStandardExactPath() throws Exception {
        // v1alpha 与 v1 分别发布，各自声明标准精确方法地址。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // v1alpha 使用兼容的标准反射消息，服务路径必须选择其自身别名。
                String methodName = "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 消息 wire 格式兼容。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                ReflectionReply replies = new ReflectionReply();
                var requests = ClientCalls.asyncBidiStreamingCall(channel.newCall(method, options), replies);
                ServerReflectionRequest query = ServerReflectionRequest.newBuilder().setListServices("").build();
                requests.onNext(query);

                // v1alpha 精确入口返回完整的内建服务列表，并正常完成响应流。
                var response = replies.response.get(5, TimeUnit.SECONDS);
                List<String> names = response.getListServicesResponse().getServiceList() // 核对扩展发布内容。
                        .stream().map(service -> service.getName()).sorted().toList();
                assertEquals(this.builtinServiceNames(), names);
                requests.onCompleted();
                replies.completion.get(5, TimeUnit.SECONDS);
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void reflectionV1AlphaIsNotExposedUnderTheBusinessContext() throws Exception {
        // v1alpha 同样不继承业务默认目录 /rpc。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            context.start();
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                // 增加业务前缀访问 v1alpha，断言调用方收到 UNIMPLEMENTED。
                String methodName = "rpc/grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo";
                var method = ServerReflectionGrpc.getServerReflectionInfoMethod().toBuilder() // 消息 wire 格式兼容。
                        .setFullMethodName(methodName).build();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                ReflectionReply replies = new ReflectionReply();
                var requests = ClientCalls.asyncBidiStreamingCall(channel.newCall(method, options), replies);
                requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build());
                ExecutionException failure = assertThrows(ExecutionException.class, // 等待实际协议错误。
                        () -> replies.response.get(5, TimeUnit.SECONDS));
                assertEquals(Status.Code.UNIMPLEMENTED, Status.fromThrowable(failure.getCause()).getCode());
            } finally {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultTimeout(10000);
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") // 只声明业务默认目录。
                .contextPath("/rpc").option("streamBody", true);
        configuration.addExtension(new GrpcBuiltinServicesFactory());
        return configuration;
    }

    private List<String> builtinServiceNames() {
        return List.of("grpc.health.v1.Health", "grpc.reflection.v1.ServerReflection", // 标准服务协议名称。
                "grpc.reflection.v1alpha.ServerReflection");
    }

    private static final class ReflectionReply implements StreamObserver<ServerReflectionResponse> {
        private final BasicFuture<ServerReflectionResponse> response   = new BasicFuture<>();
        private final BasicFuture<Void>                     completion = new BasicFuture<>();

        @Override
        public void onNext(ServerReflectionResponse value) {
            this.response.completed(value);
        }

        @Override
        public void onError(Throwable failure) {
            this.response.failed(failure);
            this.completion.failed(failure);
        }

        @Override
        public void onCompleted() {
            this.completion.completed(null);
        }
    }
}
