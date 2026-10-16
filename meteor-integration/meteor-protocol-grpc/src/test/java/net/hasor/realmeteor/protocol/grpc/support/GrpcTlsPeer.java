/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.grpc.support;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.KeyStore;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;
import io.grpc.MethodDescriptor;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.ServerCalls;
import net.hasor.example.protocol.grpc.Message;

/** 官方对端使用独立 TLS 配置和 Protobuf 编码，只共享业务 DTO 与测试证书。 */
public final class GrpcTlsPeer {
    private GrpcTlsPeer() {
    }

    public static SslContext clientSsl(String identity) throws Exception {
        // 由官方 gRPC 配置 ALPN；客户端身份与服务器信任分别加载。
        SslContextBuilder builder = GrpcSslContexts.configure(SslContextBuilder.forClient(), SslProvider.JDK);
        builder.trustManager(trust());
        if (identity != null) {
            builder.keyManager(keys(identity));
        }
        return builder.build();
    }

    public static SslContext serverSsl(boolean requireClientCertificate) throws Exception {
        // mTLS 场景明确要求客户端证书，普通 TLS 场景只验证服务端身份。
        SslContextBuilder builder = SslContextBuilder.forServer(keys("server"));
        GrpcSslContexts.configure(builder, SslProvider.JDK);
        builder.trustManager(trust());
        builder.clientAuth(requireClientCertificate ? ClientAuth.REQUIRE : ClientAuth.NONE);
        return builder.build();
    }

    public static SSLContext jdkClientContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust().getTrustManagers(), null);
        return context;
    }

    public static MethodDescriptor<Message, Message> method() {
        MessageMarshaller marshaller = new MessageMarshaller();
        return MethodDescriptor.<Message, Message>newBuilder() //
                .setType(MethodDescriptor.MethodType.UNARY) //
                .setFullMethodName("example.Echo/echo") //
                .setRequestMarshaller(marshaller) //
                .setResponseMarshaller(marshaller) //
                .build();
    }

    public static ServerServiceDefinition service(AtomicInteger calls) {
        // 官方服务独立处理消息，让双向互通能区分远端业务结果。
        var handler = ServerCalls.<Message, Message>asyncUnaryCall((request, reply) -> {
            calls.incrementAndGet();
            ServerCallStreamObserver<Message> call = (ServerCallStreamObserver<Message>) reply;
            call.setCompression("gzip");
            reply.onNext(Message.of(request.getValue(), request.getCount() + 5));
            reply.onCompleted();
        });
        return ServerServiceDefinition.builder("example.Echo").addMethod(method(), handler).build();
    }

    public static byte[] grpcBody(Message value) throws IOException {
        byte[] protobuf = new MessageMarshaller().stream(value).readAllBytes();
        return ByteBuffer.allocate(protobuf.length + 5).put((byte) 0).putInt(protobuf.length).put(protobuf).array();
    }

    public static Message readGrpcBody(byte[] body) throws IOException {
        // 一元响应必须恰好包含一个未压缩 gRPC 消息，不能将 HTTP 错误页误认作业务响应。
        if (body.length < 5) {
            throw new IOException("gRPC response has no complete frame header");
        }
        ByteBuffer frame = ByteBuffer.wrap(body);
        if (frame.get() != 0 || frame.getInt() != frame.remaining()) {
            throw new IOException("gRPC response is not one uncompressed frame");
        }
        return new MessageMarshaller().parse(new ByteArrayInputStream(body, 5, body.length - 5));
    }

    private static KeyManagerFactory keys(String identity) throws Exception {
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store(identity), "meteor-test".toCharArray());
        return keys;
    }

    private static TrustManagerFactory trust() throws Exception {
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(store("trust"));
        return trust;
    }

    private static KeyStore store(String name) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        String resource = "/tls/acceptance/" + name + ".p12";
        try (InputStream input = GrpcTlsPeer.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing TLS acceptance resource: " + resource);
            }
            store.load(input, "meteor-test".toCharArray());
        }
        return store;
    }

    private static final class MessageMarshaller implements MethodDescriptor.Marshaller<Message> {
        public InputStream stream(Message value) {
            // 使用 proto 声明的字段号 7 和 2，独立于 Meteor 序列化器。
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                CodedOutputStream output = CodedOutputStream.newInstance(buffer);
                output.writeString(7, value.getValue());
                output.writeInt32(2, value.getCount());
                output.flush();
                return new ByteArrayInputStream(buffer.toByteArray());
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }

        public Message parse(InputStream stream) {
            // 按标准规则跳过未知字段，保留独立对端的 Protobuf 兼容行为。
            try {
                Message message = Message.of("", 0);
                CodedInputStream input = CodedInputStream.newInstance(stream);
                int tag;
                while ((tag = input.readTag()) != 0) {
                    switch (tag) {
                        case 58 -> message.setValue(input.readStringRequireUtf8());
                        case 16 -> message.setCount(input.readInt32());
                        default -> input.skipField(tag);
                    }
                }
                return message;
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
