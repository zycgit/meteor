/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.*;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.bootstrap.MetConfiguration;
import static net.hasor.example.protocol.grpc.MetadataServiceImpl.details;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

abstract class GrpcMetadataPeer {
    private static final   ObjectMapper         MAPPER  = new ObjectMapper().disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
    protected static final Metadata.Key<String> TEXT    = Metadata.Key.of("x-values", Metadata.ASCII_STRING_MARSHALLER);
    protected static final Metadata.Key<byte[]> BINARY  = //
            Metadata.Key.of("x-values-bin", Metadata.BINARY_BYTE_MARSHALLER);
    protected static final Metadata.Key<byte[]> DETAILS = //
            Metadata.Key.of("grpc-status-details-bin", Metadata.BINARY_BYTE_MARSHALLER);

    protected static Server startMetadataPeer() throws Exception {
        // 官方对端注册普通调用和三种流模式，各自返回同一份业务消息。
        ServerServiceDefinition.Builder definition = ServerServiceDefinition.builder("example.Metadata");
        var echo = method("echo", MethodDescriptor.MethodType.UNARY);
        definition.addMethod(echo, ServerCalls.asyncUnaryCall((value, reply) -> {
            reply.onNext(value);
            reply.onCompleted();
        }));
        var list = method("list", MethodDescriptor.MethodType.SERVER_STREAMING);
        definition.addMethod(list, ServerCalls.asyncServerStreamingCall((value, reply) -> {
            reply.onNext(value);
            reply.onNext(value);
            reply.onCompleted();
        }));
        var collect = method("collect", MethodDescriptor.MethodType.CLIENT_STREAMING);
        definition.addMethod(collect, ServerCalls.asyncClientStreamingCall(reply -> receiver(reply, false)));
        var exchange = method("exchange", MethodDescriptor.MethodType.BIDI_STREAMING);
        definition.addMethod(exchange, ServerCalls.asyncBidiStreamingCall(reply -> receiver(reply, true)));

        // 错误方法提供状态详情和重复 trailers，供失败互通场景观察。
        var fail = method("fail", MethodDescriptor.MethodType.UNARY);
        definition.addMethod(fail, ServerCalls.asyncUnaryCall((value, reply) -> {
            Metadata trailers = new Metadata();
            trailers.put(DETAILS, details(value.getCount()));
            trailers.put(TEXT, "error-1");
            trailers.put(TEXT, "error-2");
            Status status = Status.fromCodeValue(value.getCount()) //
                    .withDescription("remote 中文 + %");
            reply.onError(status.asRuntimeException(trailers));
        }));
        var failStream = method("failStream", MethodDescriptor.MethodType.SERVER_STREAMING);
        definition.addMethod(failStream, ServerCalls.asyncServerStreamingCall((value, reply) -> {
            reply.onNext(value);
            Metadata trailers = new Metadata();
            trailers.put(DETAILS, details(9));
            trailers.put(TEXT, "late");
            Status failure = Status.FAILED_PRECONDITION.withDescription("late failure");
            reply.onError(failure.asRuntimeException(trailers));
        }));
        // 在真实监听上统一校验请求元数据，并附加响应头部。
        ServerServiceDefinition service = ServerInterceptors.intercept(definition.build(), new MetadataInterceptor());
        Server server = NettyServerBuilder.forPort(0).addService(service).build();
        return server.start();
    }

    protected static final class MetadataInterceptor implements ServerInterceptor {
        public <Q, A> ServerCall.Listener<Q> interceptCall(ServerCall<Q, A> call, //
                Metadata headers, ServerCallHandler<Q, A> next) {
            // 每次实际调用先校验请求头，再配置压缩与响应元数据。
            assertEquals(List.of("one", "two"), values(headers, TEXT));
            List<byte[]> binaryHeaders = values(headers, BINARY);
            assertArrayEquals(new byte[] { 0, -1 }, binaryHeaders.get(0));
            call.setCompression("gzip");
            ServerCall<Q, A> forwarding = new SimpleForwardingServerCall<>(call) {
                public void sendHeaders(Metadata fields) {
                    // 响应正文开始之前发送重复头和二进制头。
                    fields.put(TEXT, "head-1");
                    fields.put(TEXT, "head-2");
                    fields.put(BINARY, new byte[] { 1, -1 });
                    super.sendHeaders(fields);
                }

                public void close(Status status, Metadata fields) {
                    // 成功调用追加固定 trailers，错误调用保留方法自身提供的字段。
                    if (status.isOk()) {
                        fields.put(TEXT, "tail-1");
                        fields.put(TEXT, "tail-2");
                        fields.put(BINARY, new byte[] { 2, -1 });
                    }
                    super.close(status, fields);
                }
            };
            return next.startCall(forwarding, headers);
        }
    }

    protected static StreamObserver<Message> receiver(StreamObserver<Message> reply, boolean echo) {
        // 双向流逐条回显，请求流仅在输入结束后发送汇总结果。
        return new StreamObserver<>() {
            private int sum;

            public void onNext(Message value) {
                this.sum += value.getCount();
                if (echo) {
                    reply.onNext(value);
                }
            }

            public void onError(Throwable failure) {
                reply.onError(failure);
            }

            public void onCompleted() {
                if (!echo) {
                    reply.onNext(Message.of("sum", this.sum));
                }
                reply.onCompleted();
            }
        };
    }

    protected static MetConfiguration configuration(boolean server) {
        // 两端共用 HTTP/2、增量正文和 gzip，只有服务端需要监听。
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.setDefaultTimeout(10000);
        var http = configuration.connector("http").option("httpVersion", 2);
        if (server) {
            http.bind("127.0.0.1", 0);
        }
        http.protocol("grpc").option("streamBody", true).option("compression", "gzip");
        return configuration;
    }

    protected static Result call(ManagedChannel channel, String name, //
            MethodDescriptor.MethodType type, int count) throws Exception {
        // 每次官方调用单独准备结果容器、期限和请求元数据。
        BasicFuture<Result> completed = new BasicFuture<>();
        Result result = new Result();
        CallOptions options = CallOptions.DEFAULT.withCompression("gzip").withDeadlineAfter(5, TimeUnit.SECONDS);
        ClientCall<Message, Message> call = channel.newCall(method(name, type), options);
        Metadata headers = new Metadata();
        headers.put(TEXT, "one");
        headers.put(TEXT, "two");
        headers.put(BINARY, new byte[] { 0, -1 });
        headers.put(BINARY, new byte[0]);
        // 头部、消息和结束状态分别收集，完成后将完整结果交给测试。
        call.start(new ClientCall.Listener<>() {
            public void onHeaders(Metadata headers) {
                result.headers = headers;
            }

            public void onMessage(Message value) {
                result.values.add(value);
            }

            public void onClose(Status status, Metadata trailers) {
                result.status = status;
                result.trailers = trailers;
                completed.completed(result);
            }
        }, headers);

        // 请求流发送两条消息；其余模式只发送一个请求，然后关闭输入。
        call.request(10);
        call.sendMessage(Message.of("z".repeat(10000), count));
        boolean clientStream = type == MethodDescriptor.MethodType.CLIENT_STREAMING;
        boolean duplex = type == MethodDescriptor.MethodType.BIDI_STREAMING;
        if (clientStream || duplex) {
            call.sendMessage(Message.of("second", count));
        }
        call.halfClose();
        return completed.get(6, TimeUnit.SECONDS);
    }

    protected static MethodDescriptor<Message, Message> method(String name, MethodDescriptor.MethodType type) {
        // 官方对端按 JSON 编码业务消息，不使用 Meteor 内部协议实现。
        MethodDescriptor.Marshaller<Message> marshaller = new MethodDescriptor.Marshaller<>() {
            public InputStream stream(Message value) {
                try {
                    byte[] bytes = MAPPER.writeValueAsBytes(value);
                    return new ByteArrayInputStream(bytes);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }

            public Message parse(InputStream stream) {
                try {
                    return MAPPER.readValue(stream, Message.class);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        };

        // 请求和响应使用相同的数据类型，仅调用模式与方法名由场景指定。
        return MethodDescriptor.<Message, Message>newBuilder() //
                .setFullMethodName("example.Metadata/" + name) //
                .setType(type) //
                .setRequestMarshaller(marshaller) //
                .setResponseMarshaller(marshaller) //
                .build();
    }

    protected static <T> List<T> values(Metadata metadata, Metadata.Key<T> key) {
        // 保留官方 Metadata 中同名字段的原始顺序。
        List<T> values = new ArrayList<>();
        Iterable<T> entries = metadata.getAll(key);
        if (entries != null) {
            for (T value : entries) {
                values.add(value);
            }
        }
        return values;
    }

    protected static final class Result {
        final List<Message> values = new ArrayList<>();
        Metadata headers = new Metadata();
        Metadata trailers;
        Status   status;
    }
}
