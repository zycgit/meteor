/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.scenario.example.Echo;
import net.hasor.meteor.protocol.grpc.scenario.example.EchoImpl;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import net.hasor.meteor.protocol.grpc.scenario.example.Messages;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class GrpcContextPathTest {
    @Test(timeout = 20000)
    public void serviceWithoutPathSelectionIsVisibleOnDefaultContext() throws Exception {
        // 协议拥有默认路径和额外路径，服务不声明路径选择。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/admin");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            // 从正式入口启动并发布 JSON 服务。
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();

            // 官方客户端访问默认路径，服务应执行一次并返回业务结果。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                var method = this.method("rpc/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message result = ClientCalls.blockingUnaryCall(peer, method, options, Message.of("default", 3));
                assertEquals("default", result.getValue());
                assertEquals(4, result.getCount());
                assertEquals(1, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void serviceWithoutPathSelectionIsHiddenOnAdditionalContext() throws Exception {
        // 增加协议路径不会让未声明路径的服务自动暴露到该路径。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/admin");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            // 服务保持默认路径选择，通过公开发布入口注册。
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();

            // 官方客户端访问额外路径，必须收到不可调用状态且业务没有执行。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                var method = this.method("admin/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("hidden", 1);
                StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> {
                    ClientCalls.blockingUnaryCall(peer, method, options, request);
                });
                assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
                assertEquals("Service is not exposed on this protocol mount", failure.getStatus().getDescription());
                assertEquals(0, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void explicitServicePathIsVisibleOnSelectedContext() throws Exception {
        // 服务显式选择额外路径，协议默认路径仍为 /rpc。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/admin");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            // 发布时将该服务的可见范围限定为 /admin。
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc", "/admin").register();

            // 官方客户端访问服务选择的路径，验证业务调用成功。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                var method = this.method("admin/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message result = ClientCalls.blockingUnaryCall(peer, method, options, Message.of("selected", 6));
                assertEquals("selected", result.getValue());
                assertEquals(7, result.getCount());
                assertEquals(1, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void explicitServicePathReplacesDefaultContextVisibility() throws Exception {
        // 额外路径的显式选择替换默认可见范围，不能叠加默认路径。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/admin");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            // 服务只声明 /admin，保留协议自身的 /rpc 默认路径。
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc", "/admin").register();

            // 访问默认路径应被拒绝，不能执行隐藏服务。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                var method = this.method("rpc/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("hidden", 2);
                StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> {
                    ClientCalls.blockingUnaryCall(peer, method, options, request);
                });
                assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
                assertEquals("Service is not exposed on this protocol mount", failure.getStatus().getDescription());
                assertEquals(0, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void serviceCanSelectMultipleRouteMounts() throws Exception {
        // 同一个服务显式选择两个已配置路径。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/admin");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            // 一次发布声明完整可见范围，不重复发布服务。
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc", "/rpc", "/admin").register();

            // 在同一通道来回切换允许路径，每次请求都应独立选择并到达同一实例。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                for (String path : List.of("rpc", "admin", "rpc")) {
                    var method = this.method(path + "/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
                    CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                    Message result = ClientCalls.blockingUnaryCall(peer, method, options, Message.of(path, 4));
                    assertEquals(path, result.getValue());
                    assertEquals(5, result.getCount());
                }
                assertEquals(3, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void nestedRequestUsesLongestConfiguredContextPrefix() throws Exception {
        // 默认路径是额外路径的前缀，路由必须先匹配更长的路径。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/rpc/internal");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            // 服务仅在嵌套路径可见，短路径不能用于解析该请求的方法名。
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc", "/rpc/internal").register();

            // 请求 URI 同时拥有两个前缀，应正确去掉最长前缀并执行服务。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                var method = this.method("rpc/internal/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("nested", 8);
                Message result = ClientCalls.blockingUnaryCall(peer, method, options, request);
                assertEquals("nested", result.getValue());
                assertEquals(9, result.getCount());
                assertEquals(1, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void nestedContextDoesNotInheritParentServiceVisibility() throws Exception {
        // 嵌套路径拥有独立可见范围，父路径的服务不能自动继承到子路径。
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").bind("127.0.0.1", 0).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/rpc/internal");
        EchoImpl implementation = new EchoImpl();
        try (MetContext server = configuration.buildContext()) {
            // 未声明路径的服务只属于默认父路径。
            server.start();
            server.publisher().rsfService(Echo.class, implementation) //
                    .aliasName("grpc", "example.Echo").serialize("Json").register();

            // 最长路径匹配后仍必须检查服务可见范围，不能退回父路径执行。
            int port = server.bindAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build();
            try {
                var method = this.method("rpc/internal/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("hidden", 1);
                StatusRuntimeException failure = assertThrows(StatusRuntimeException.class, () -> {
                    ClientCalls.blockingUnaryCall(peer, method, options, request);
                });
                assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode());
                assertEquals("Service is not exposed on this protocol mount", failure.getStatus().getDescription());
                assertEquals(0, implementation.getCalls());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void outboundUnaryUsesServiceSelectedContextPath() throws Exception {
        // 官方服务端只注册 /admin 方法，客户端默认路径保持为 /rpc。
        var method = this.method("admin/example.Echo/echo", MethodDescriptor.MethodType.UNARY);
        var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, reply) -> {
            reply.onNext(Message.of(request.getValue(), request.getCount() + 5));
            reply.onCompleted();
        });
        ServerServiceDefinition service = ServerServiceDefinition.builder("admin/example.Echo") //
                .addMethod(method, handler).build();
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").option("httpVersion", 2).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/admin");
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        try (MetContext client = configuration.buildContext()) {
            // 消费者通过公开发布入口选择 /admin，路径不是由地址或协议默认值决定。
            client.start();
            MetBindInfo<Echo> info = client.publisher().rsfService(Echo.class) //
                    .aliasName("grpc", "example.Echo").serialize("Json") //
                    .contextPathByPrefix("grpc", "/admin").register();

            // 正式客户端实际发出 unary 请求，只有服务选择的 URI 可以返回结果。
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Echo remote = client.getRsfClient(address).getRemote(info);
            Message result = remote.echo(Message.of("outbound", 2));
            assertEquals("outbound", result.getValue());
            assertEquals(7, result.getCount());
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 20000)
    public void outboundServerStreamUsesServiceSelectedContextPath() throws Exception {
        // 官方服务端仅在额外路径注册响应流，避免默认路径掩盖出站选择错误。
        var method = this.method("admin/example.Messages/list", MethodDescriptor.MethodType.SERVER_STREAMING);
        var handler = ServerCalls.<Message, Message>asyncServerStreamingCall((request, reply) -> {
            // 按请求顺序发送有限条消息，使调用方能验证整个响应流完成。
            for (int index = 0; index < request.getCount(); index++) {
                reply.onNext(Message.of(request.getValue(), index));
            }
            reply.onCompleted();
        });
        ServerServiceDefinition service = ServerServiceDefinition.builder("admin/example.Messages") //
                .addMethod(method, handler).build();
        MetConfiguration configuration = this.configuration();
        configuration.connector("http").option("httpVersion", 2).protocol("grpc") //
                .contextPath("/rpc").addContextPath("/admin").option("streamBody", true);
        Server server = NettyServerBuilder.forPort(0).addService(service).build().start();
        try (MetContext client = configuration.buildContext()) {
            // 通过公开消费者声明将流式服务限定为 /admin。
            client.start();
            MetBindInfo<Messages> info = client.publisher().rsfService(Messages.class) //
                    .aliasName("grpc", "example.Messages").serialize("Json") //
                    .contextPathByPrefix("grpc", "/admin").register();

            // 请求经过真实 HTTP/2 连接，完整消费后验证消息内容和顺序。
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Messages remote = client.getRsfClient(address).getRemote(info);
            List<Message> replies = new ArrayList<>();
            MetCaller.consume(remote.list(Message.of("selected-stream", 3)), replies::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(3, replies.size());
            for (int index = 0; index < replies.size(); index++) {
                assertEquals("selected-stream", replies.get(index).getValue());
                assertEquals(index, replies.get(index).getCount());
            }
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private MetConfiguration configuration() {
        // 公共配置不创建资源，监听、路径和调用模式由各个场景声明。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(5000);
        configuration.setQueueMinPoolSize(2);
        return configuration;
    }

    private MethodDescriptor<Message, Message> method(String name, MethodDescriptor.MethodType type) {
        // 官方对端独立编解码 JSON，不使用 Meteor 内部协议实现。
        ObjectMapper mapper = new ObjectMapper();
        MethodDescriptor.Marshaller<Message> marshaller = new MethodDescriptor.Marshaller<>() {
            public InputStream stream(Message value) {
                try {
                    byte[] bytes = mapper.writeValueAsBytes(value);
                    return new ByteArrayInputStream(bytes);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }

            public Message parse(InputStream stream) {
                try {
                    return mapper.readValue(stream, Message.class);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };

        // 方法名包含完整 URI 路径，由官方客户端或服务端按原样使用。
        return MethodDescriptor.<Message, Message>newBuilder() //
                .setFullMethodName(name).setType(type) //
                .setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
    }
}
