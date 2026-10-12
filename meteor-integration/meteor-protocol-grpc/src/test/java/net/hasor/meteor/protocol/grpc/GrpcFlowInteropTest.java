/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
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
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.protocol.grpc.GrpcFlowTest.Messages;
import net.hasor.meteor.protocol.grpc.GrpcFlowTest.Service;
import net.hasor.meteor.protocol.grpc.GrpcInteropTest.Message;
import net.hasor.meteor.rpc.MetCaller;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcFlowInteropTest {
    @Test(timeout = 20000)
    public void officialClientUsesNativeProtobufForAllThreeStreamingShapes() throws Exception {
        MetConfiguration configuration = this.configuration(true);
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Messages.class, new Service()).aliasName("grpc", "example.Messages").serialize("Protobuf").bindResource(this.resource()).register();
            context.start();
            ManagedChannel peer = NettyChannelBuilder.forAddress("127.0.0.1", context.bindAddress("grpc").getPort()).usePlaintext().build();
            try {
                // 标准服务端流：官方客户端仅使用 Protobuf marshaller，没有 Meteor 私有信封。
                var serverMethod = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
                Iterator<Message> messages = ClientCalls.blockingServerStreamingCall(peer, serverMethod, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), new Message("native", 3));
                List<Message> values = new ArrayList<>();
                messages.forEachRemaining(values::add);
                assertEquals(3, values.size());
                assertEquals("native", values.get(2).getValue());
                assertEquals(2, values.get(2).getCount());

                // 标准客户端流：请求半关闭后，服务端返回单个汇总结果。
                var clientMethod = this.method("collect", MethodDescriptor.MethodType.CLIENT_STREAMING);
                Reply sum = new Reply();
                var clientCall = peer.newCall(clientMethod, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS));
                StreamObserver<Message> requests = ClientCalls.asyncClientStreamingCall(clientCall, sum);
                requests.onNext(new Message("a", 4));
                requests.onNext(new Message("b", 7));
                requests.onCompleted();
                sum.done.get(5, TimeUnit.SECONDS);
                assertEquals(11, sum.values.get(0).getCount());

                var bidiMethod = this.method("exchange", MethodDescriptor.MethodType.BIDI_STREAMING);
                Reply replies = new Reply();
                var bidiCall = peer.newCall(bidiMethod, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS));
                StreamObserver<Message> exchange = ClientCalls.asyncBidiStreamingCall(bidiCall, replies);
                exchange.onNext(new Message("one", 1));
                exchange.onNext(new Message("two", 2));
                exchange.onCompleted();
                replies.done.get(5, TimeUnit.SECONDS);
                assertEquals(2, replies.values.size());
                assertEquals("two", replies.values.get(1).getValue());

                // 业务消息源异常使用标准 grpc-status 交付。
                var broken = this.method("broken", MethodDescriptor.MethodType.SERVER_STREAMING);
                Iterator<Message> failed = ClientCalls.blockingServerStreamingCall(peer, broken, CallOptions.DEFAULT.withDeadlineAfter(5, TimeUnit.SECONDS), new Message());
                StatusRuntimeException error = assertThrows(StatusRuntimeException.class, failed::hasNext);
                assertEquals(Status.Code.INTERNAL, error.getStatus().getCode());
                assertTrue(error.getStatus().getDescription().contains("source failure"));
            } finally {
                peer.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test(timeout = 20000)
    public void meteorFlowProxyCallsOfficialServerForAllThreeStreamingShapes() throws Exception {
        var list = this.method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
        var collect = this.method("collect", MethodDescriptor.MethodType.CLIENT_STREAMING);
        var exchange = this.method("exchange", MethodDescriptor.MethodType.BIDI_STREAMING);
        ServerServiceDefinition definition = ServerServiceDefinition.builder("example.Messages").addMethod(list, ServerCalls.asyncServerStreamingCall((request, reply) -> {
            reply.onNext(new Message(request.getValue(), 2));
            reply.onNext(new Message(request.getValue(), 5));
            reply.onCompleted();
        })).addMethod(collect, ServerCalls.asyncClientStreamingCall(reply -> this.collect(reply))).addMethod(exchange, ServerCalls.asyncBidiStreamingCall(reply -> reply)).build();
        Server server = NettyServerBuilder.forPort(0).addService(definition).build().start();
        try (MetContext client = this.configuration(false).buildContext()) {
            MetBindInfo<Messages> info = client.publisher().rsfService(Messages.class).aliasName("grpc", "example.Messages").serialize("Protobuf").bindResource(this.resource()).register();
            client.start();
            InterAddress address = new InterAddress("grpc", "127.0.0.1", server.getPort(), "default");
            Messages remote = client.getRsfClient(address).getRemote(info);
            List<Message> values = new ArrayList<>();
            MetCaller.consume(remote.list(new Message("official", 0)), values::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, values.size());
            assertEquals(5, values.get(1).getCount());
            Message sum = remote.collect(MetCaller.fromIterable(values));
            assertEquals(7, sum.getCount());

            List<Message> replies = new ArrayList<>();
            MetCaller.consume(remote.exchange(MetCaller.fromIterable(values)), replies::add).get(5, TimeUnit.SECONDS);
            assertEquals(2, replies.size());
            assertEquals("official", replies.get(1).getValue());
        } finally {
            server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private StreamObserver<Message> collect(StreamObserver<Message> reply) {
        return new StreamObserver<>() {
            private int sum;

            public void onNext(Message value) {
                this.sum += value.getCount();
            }

            public void onError(Throwable failure) {
                reply.onError(failure);
            }

            public void onCompleted() {
                reply.onNext(new Message("sum", this.sum));
                reply.onCompleted();
            }
        };
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/flow.proto"));
    }

    private MetConfiguration configuration(boolean server) {
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
        return MethodDescriptor.<Message, Message>newBuilder().setType(type).setFullMethodName("example.Messages/" + name).setRequestMarshaller(marshaller).setResponseMarshaller(marshaller).build();
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
