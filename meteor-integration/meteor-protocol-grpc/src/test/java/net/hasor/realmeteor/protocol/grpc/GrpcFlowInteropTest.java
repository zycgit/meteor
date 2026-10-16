/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.example.protocol.grpc.MessageService;
import net.hasor.example.protocol.grpc.Messages;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcFlowInteropTest {
    @Test(timeout = 20000)
    public void officialClientReceivesServerStream() throws Exception {
        // 配置 Meteor 的 Protobuf 服务端流，官方客户端只使用标准消息格式。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，并绑定描述流式方法的 proto。
            context.start();

            var publication = context.publisher().rsfService(Messages.class, new MessageService());
            publication //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 官方客户端连接 Meteor 实际绑定的端口。
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 标准服务端流：官方客户端仅使用 Protobuf marshaller，没有 Meteor 私有信封。
                var serverMethod = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                Message request = Message.of("native", 3);
                var messages = ClientCalls.blockingServerStreamingCall(peer, serverMethod, options, request);
                List<Message> values = new ArrayList<>();
                messages.forEachRemaining(values::add);
                assertEquals(3, values.size());
                assertEquals("native", values.get(2).getValue());
                assertEquals(2, values.get(2).getCount());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void officialClientSendsClientStream() throws Exception {
        // 配置 Meteor 的 Protobuf 客户端流，准备由官方客户端发送消息。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，并绑定描述流式方法的 proto。
            context.start();

            var publication = context.publisher().rsfService(Messages.class, new MessageService());
            publication //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 官方客户端连接 Meteor 实际绑定的端口。
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 标准客户端流：请求半关闭后，服务端返回单个汇总结果。
                var clientMethod = this.method("collect", MethodDescriptor.MethodType.CLIENT_STREAMING);
                Reply sum = new Reply();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var clientCall = peer.newCall(clientMethod, options);
                StreamObserver<Message> requests = ClientCalls.asyncClientStreamingCall(clientCall, sum);
                requests.onNext(Message.of("a", 4));
                requests.onNext(Message.of("b", 7));
                requests.onCompleted();
                sum.done.get(5, TimeUnit.SECONDS);
                assertEquals(11, sum.values.get(0).getCount());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void officialClientUsesBidirectionalStream() throws Exception {
        // 配置 Meteor 的 Protobuf 双向流，准备官方客户端互通。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，并绑定描述流式方法的 proto。
            context.start();

            var publication = context.publisher().rsfService(Messages.class, new MessageService());
            publication //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 官方客户端连接 Meteor 实际绑定的端口。
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 请求端半关闭后，等待官方观察器收齐双向流的回传消息。
                var bidiMethod = this.method("exchange", MethodDescriptor.MethodType.BIDI_STREAMING);
                Reply replies = new Reply();
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var bidiCall = peer.newCall(bidiMethod, options);
                StreamObserver<Message> exchange = ClientCalls.asyncBidiStreamingCall(bidiCall, replies);
                exchange.onNext(Message.of("one", 1));
                exchange.onNext(Message.of("two", 2));
                exchange.onCompleted();
                replies.done.get(5, TimeUnit.SECONDS);
                assertEquals(2, replies.values.size());
                assertEquals("two", replies.values.get(1).getValue());
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void officialClientReceivesSourceFailure() throws Exception {
        // 配置会主动失败的消息源，验证官方客户端收到标准错误状态。
        MetConfiguration configuration = this.configuration(true);
        try (MetContext context = configuration.buildContext()) {
            // 容器启动后发布普通 Java 接口，并绑定描述流式方法的 proto。
            context.start();

            var publication = context.publisher().rsfService(Messages.class, new MessageService());
            publication //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 官方客户端连接 Meteor 实际绑定的端口。
            int port = context.getListenAddress("grpc").getPort();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", port) //
                    .usePlaintext() //
                    .build();
            try {
                // 业务消息源异常使用标准 grpc-status 交付。
                var broken = this.method("broken", MethodDescriptor.MethodType.SERVER_STREAMING);
                CallOptions options = CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS);
                var failed = ClientCalls.blockingServerStreamingCall(peer, broken, options, new Message());
                StatusRuntimeException error = assertThrows(StatusRuntimeException.class, failed::hasNext);
                assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
                assertTrue(error.getStatus().getDescription().contains("source failure"));
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void meteorReceivesOfficialServerStream() throws Exception {
        // 独立搭建官方 gRPC 对端，服务端流固定返回两条消息。
        var list = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
        var collect = this.method("collect", MethodDescriptor.MethodType.CLIENT_STREAMING);
        var exchange = this.method("exchange", MethodDescriptor.MethodType.BIDI_STREAMING);
        var listHandler = ServerCalls.<Message, Message>asyncServerStreamingCall((request, reply) -> {
            reply.onNext(Message.of(request.getValue(), 2));
            reply.onNext(Message.of(request.getValue(), 5));
            reply.onCompleted();
        });
        var collectHandler = ServerCalls.asyncClientStreamingCall(this::collect);
        var exchangeHandler = ServerCalls.<Message, Message>asyncBidiStreamingCall(reply -> reply);
        ServerServiceDefinition definition = ServerServiceDefinition.builder("example.Messages") //
                .addMethod(list, listHandler) //
                .addMethod(collect, collectHandler) //
                .addMethod(exchange, exchangeHandler) //
                .build();
        Server server = NettyServerBuilder.forPort(0).addService(definition).build().start();
        try (MetContext client = this.configuration(false).buildContext()) {
            // 启动 Meteor 客户端，通过公开 API 订阅官方对端的服务。
            client.start();

            var publication = client.publisher().rsfService(Messages.class);
            MetBindInfo<Messages> info = publication //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 使用对端真实监听地址创建代理，并检查流的内容和结束信号。
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Messages remote = client.getRsfClient(address).getRemote(info);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(Message.of("official", 0)), values::add) //
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, values.size());
            assertEquals(5, values.get(1).getCount());
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 20000)
    public void meteorSendsOfficialClientStream() throws Exception {
        // 独立搭建官方 gRPC 对端，客户端流按收到的消息累加计数。
        var list = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
        var collect = this.method("collect", MethodDescriptor.MethodType.CLIENT_STREAMING);
        var exchange = this.method("exchange", MethodDescriptor.MethodType.BIDI_STREAMING);
        var listHandler = ServerCalls.<Message, Message>asyncServerStreamingCall((request, reply) -> {
            reply.onNext(Message.of(request.getValue(), 2));
            reply.onNext(Message.of(request.getValue(), 5));
            reply.onCompleted();
        });
        var collectHandler = ServerCalls.asyncClientStreamingCall(this::collect);
        var exchangeHandler = ServerCalls.<Message, Message>asyncBidiStreamingCall(reply -> reply);
        ServerServiceDefinition definition = ServerServiceDefinition.builder("example.Messages") //
                .addMethod(list, listHandler) //
                .addMethod(collect, collectHandler) //
                .addMethod(exchange, exchangeHandler) //
                .build();
        Server server = NettyServerBuilder.forPort(0).addService(definition).build().start();
        try (MetContext client = this.configuration(false).buildContext()) {
            // 启动 Meteor 客户端，通过公开 API 订阅官方对端的服务。
            client.start();

            var publication = client.publisher().rsfService(Messages.class);
            MetBindInfo<Messages> info = publication //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 使用对端真实监听地址创建代理，并检查流的内容和结束信号。
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Messages remote = client.getRsfClient(address).getRemote(info);
            List<Message> values = List.of(Message.of("official", 2), Message.of("official", 5));
            Message sum = remote.collect(MetCaller.fromIterable(values));
            assertEquals(7, sum.getCount());
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 20000)
    public void meteorUsesOfficialBidirectionalStream() throws Exception {
        // 独立搭建官方 gRPC 对端，双向流将收到的消息逐条回传。
        var list = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
        var collect = this.method("collect", MethodDescriptor.MethodType.CLIENT_STREAMING);
        var exchange = this.method("exchange", MethodDescriptor.MethodType.BIDI_STREAMING);
        var listHandler = ServerCalls.<Message, Message>asyncServerStreamingCall((request, reply) -> {
            reply.onNext(Message.of(request.getValue(), 2));
            reply.onNext(Message.of(request.getValue(), 5));
            reply.onCompleted();
        });
        var collectHandler = ServerCalls.asyncClientStreamingCall(this::collect);
        var exchangeHandler = ServerCalls.<Message, Message>asyncBidiStreamingCall(reply -> reply);
        ServerServiceDefinition definition = ServerServiceDefinition.builder("example.Messages") //
                .addMethod(list, listHandler) //
                .addMethod(collect, collectHandler) //
                .addMethod(exchange, exchangeHandler) //
                .build();
        Server server = NettyServerBuilder.forPort(0).addService(definition).build().start();
        try (MetContext client = this.configuration(false).buildContext()) {
            // 启动 Meteor 客户端，通过公开 API 订阅官方对端的服务。
            client.start();

            var publication = client.publisher().rsfService(Messages.class);
            MetBindInfo<Messages> info = publication //
                    .aliasName("grpc", "example.Messages") //
                    .serialize("Protobuf") //
                    .bindResource(this.resource()) //
                    .register();

            // 使用对端真实监听地址创建代理，并检查流的内容和结束信号。
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Messages remote = client.getRsfClient(address).getRemote(info);
            List<Message> values = List.of(Message.of("official", 2), Message.of("official", 5));
            List<Message> replies = new ArrayList<>();
            var requests = MetCaller.fromIterable(values);
            var responses = remote.exchange(requests);
            MetCaller.consume(responses, replies::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, replies.size());
            assertEquals("official", replies.get(1).getValue());
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private StreamObserver<Message> collect(StreamObserver<Message> reply) {
        // 官方服务按消息累加，只有请求端半关闭后才发送单个汇总结果。
        return new StreamObserver<>() {
            private int sum;

            public void onNext(Message value) {
                this.sum += value.getCount();
            }

            public void onError(Throwable failure) {
                reply.onError(failure);
            }

            public void onCompleted() {
                reply.onNext(Message.of("sum", this.sum));
                reply.onCompleted();
            }
        };
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
    }

    private MetConfiguration configuration(boolean server) {
        // 统一启用 HTTP/2 流式正文，仅服务端开启监听。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(5000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (server) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("grpc").option("streamBody", true);
        return configuration;
    }

    private MethodDescriptor<Message, Message> method(String name, MethodDescriptor.MethodType type) {
        // 独立按 proto 字段号编解码，确保不是两端共享错误的 Meteor 编码实现。
        MethodDescriptor.Marshaller<Message> marshaller = new MethodDescriptor.Marshaller<>() {
            public InputStream stream(Message message) {
                try {
                    // 直接写入约定字段号，不复用 Meteor 的消息编码。
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    CodedOutputStream output = CodedOutputStream.newInstance(bytes);
                    if (message.getValue() != null) {
                        output.writeString(7, message.getValue());
                    }
                    output.writeInt32(2, message.getCount());
                    output.flush();
                    return new ByteArrayInputStream(bytes.toByteArray());
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }

            public Message parse(InputStream input) {
                try {
                    // 逐字段解码，未知字段交由 Protobuf 标准跳过逻辑处理。
                    CodedInputStream message = CodedInputStream.newInstance(input);
                    Message value = new Message();
                    int tag;
                    while ((tag = message.readTag()) != 0) {
                        if (tag == 58) {
                            value.setValue(message.readStringRequireUtf8());
                        } else if (tag == 16) {
                            value.setCount(message.readInt32());
                        } else {
                            message.skipField(tag);
                        }
                    }
                    return value;
                } catch (Exception failure) {
                    throw new IllegalArgumentException(failure);
                }
            }
        };
        return MethodDescriptor.<Message, Message>newBuilder() //
                .setType(type) //
                .setFullMethodName("example.Messages/" + name) //
                .setRequestMarshaller(marshaller) //
                .setResponseMarshaller(marshaller) //
                .build();
    }

    private static final class Reply implements StreamObserver<Message> {
        private final List<Message>     values = new ArrayList<>();
        private final BasicFuture<Void> done   = new BasicFuture<>();

        public void onNext(Message value) {
            this.values.add(value);
        }

        public void onError(Throwable failure) {
            this.done.failed(failure);
        }

        public void onCompleted() {
            this.done.completed(null);
        }
    }
}
