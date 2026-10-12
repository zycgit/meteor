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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.grpc.GrpcInteropTest.Echo;
import net.hasor.meteor.protocol.grpc.GrpcInteropTest.EchoImpl;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcCodecTest {
    @Test
    public void gzipInputIsDecodedBeforeDispatchAndUsesTheExistingTimeout() throws Exception {
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            this.publish(context);
            GrpcCodec codec = this.codec(context, 1024);
            byte[] json = "{\"value\":\"compressed\",\"count\":9}".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(json);
            }
            Map<String, String> headers = Map.of("content-type", "application/grpc+json", "grpc-encoding", "gzip", "grpc-timeout", "1001u", "authorization", "Bearer test");
            byte[] frame = this.frame(1, bytes.toByteArray());
            HttpRequest input = HttpRequest.newBuffered("POST", "/example.Echo/echo", headers, frame, 2);
            RequestPayload request = codec.receive(input);
            assertEquals(2, request.getClientTimeout());
            assertEquals("Bearer test", request.getOption("authorization"));
            GrpcInteropTest.Message value = (GrpcInteropTest.Message) request.getParameterValues().get(0);
            assertEquals("compressed", value.getValue());
            assertEquals(9, value.getCount());
        }
    }

    @Test
    public void malformedAndMultipleMessagesAreRejectedBeforeInvocation() throws Exception {
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            GrpcCodec codec = this.codec(context, 1024);
            byte[] message = "{}".getBytes(StandardCharsets.UTF_8);
            byte[] one = this.frame(0, message);
            byte[] two = ByteBuffer.allocate(one.length * 2).put(one).put(one).array();
            for (byte[] invalid : new byte[][] { new byte[4], this.frame(2, message), two }) {
                assertThrows(IOException.class, () -> codec.receive(this.request(invalid, "application/grpc+json")));
            }
            assertThrows(IllegalArgumentException.class, () -> codec.receive(this.request(one, "text/plain")));
            assertThrows(UnsupportedOperationException.class, () -> codec.receive(this.request(one, "application/grpc+missing")));
            // 对端不能把 JSON 服务切换到框架中另一个已注册的反序列化器。
            assertThrows(UnsupportedOperationException.class, () -> codec.receive(this.request(one, "application/grpc+java")));
        }
    }

    @Test
    public void compressedSizeLimitIsAppliedToExpandedBytes() throws Exception {
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            GrpcCodec codec = this.codec(context, 64);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(new byte[1000]);
            }
            byte[] frame = this.frame(1, bytes.toByteArray());
            Map<String, String> headers = Map.of("content-type", "application/grpc+json", "grpc-encoding", "gzip");
            HttpRequest request = HttpRequest.newBuffered("POST", "/example.Echo/echo", headers, frame, 2);
            IOException failure = assertThrows(IOException.class, () -> codec.receive(request));
            assertTrue(failure.getMessage().contains("Decompressed"));
        }
    }

    @Test
    public void protobufDoesNotFallBackWhenTheServiceHasNoResource() throws Exception {
        try (MetContext context = this.configuration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).aliasName("grpc", "example.Echo").serialize("Protobuf").register();
            GrpcCodec codec = this.codec(context, 1024);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> codec.receive(this.request(this.frame(0, new byte[0]), "application/grpc+proto")));
            assertTrue(failure.getMessage().contains("GrpcProtoResource"));
        }
    }

    @Test
    public void remoteStatusKeepsUnicodeAndLiteralPlusSigns() throws Exception {
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            GrpcCodec codec = this.codec(context, 1024);
            RequestPayload request = new RequestPayload();
            request.setSerializeType("Json");
            Map<String, String> trailers = Map.of("grpc-status", "7", "grpc-message", "%E4%B8%AD%E6%96%87+a%20b");
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), new byte[0], trailers);
            MetException failure = assertThrows(MetException.class, () -> codec.decode(request, response));
            assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
            assertTrue(failure.getMessage().contains("中文+a b"));
            HttpResponse missingStatus = HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of());
            assertThrows(IOException.class, () -> codec.decode(request, missingStatus));
        }
    }

    @Test
    public void importedSchemaRetainsFieldNumbersAndMissingImportsFailClearly() throws Exception {
        // import 的消息字段必须完整保留，不能只加载类型名。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));
        var method = resource.method("example.Echo", "echo");
        assertEquals(7, method.getInputType().findFieldByName("value").getNumber());
        assertEquals(2, method.getOutputType().findFieldByName("count").getNumber());

        Path file = Files.createTempFile("meteor-missing-import-", ".proto");
        try {
            Files.writeString(file, "syntax = \"proto3\"; import \"missing.proto\"; message Empty {}");
            assertThrows(IOException.class, () -> new GrpcProtoResource(file.toUri().toURL()));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void incrementalFramingSeparatesMessagesAndRejectsTruncationAndOversize() throws Exception {
        try (MetContext context = this.configuration().buildContext()) {
            GrpcCodec codec = this.codec(context, 1024);
            byte[] first = this.frame(0, "first".getBytes(StandardCharsets.UTF_8));
            byte[] second = this.frame(0, "second".getBytes(StandardCharsets.UTF_8));
            byte[] both = ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
            ByteArrayInputStream source = new ByteArrayInputStream(both);
            assertEquals("first", new String(codec.readMessage(source, null), StandardCharsets.UTF_8));
            assertEquals("second", new String(codec.readMessage(source, null), StandardCharsets.UTF_8));
            assertNull(codec.readMessage(source, null));

            byte[] tooLarge = ByteBuffer.allocate(5).put((byte) 0).putInt(Integer.MAX_VALUE).array();
            byte[] truncated = ByteBuffer.allocate(5).put((byte) 0).putInt(4).array();
            byte[] invalidFlag = this.frame(2, new byte[0]);
            for (byte[] invalid : new byte[][] { new byte[3], tooLarge, truncated, invalidFlag }) {
                ByteArrayInputStream input = new ByteArrayInputStream(invalid);
                assertThrows(IOException.class, () -> codec.readMessage(input, null));
            }
        }
    }

    @Test
    public void remoteDeadlineUsesTheSameExceptionTypeAsTheLocalCaller() throws Exception {
        try (MetContext context = this.configuration().buildContext()) {
            GrpcCodec codec = this.codec(context, 1024);
            RequestPayload request = new RequestPayload();
            Map<String, String> trailers = Map.of("grpc-status", "4", "grpc-message", "deadline%20expired");
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), new byte[0], trailers);
            MetTimeoutException failure = assertThrows(MetTimeoutException.class, () -> codec.decode(request, response));
            assertTrue(failure.getMessage().contains("deadline expired"));
        }
    }

    private void publish(MetContext context) {
        context.publisher().rsfService(Echo.class, new EchoImpl()).aliasName("grpc", "example.Echo").serialize("Json").register();
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.connector("http").protocol("grpc");
        return configuration;
    }

    private GrpcCodec codec(MetContext context, int limit) {
        ProtocolConfig protocol = new ProtocolConfig("grpc", "grpc", Map.of("maxMessageSize", String.valueOf(limit)));
        return new GrpcCodec(protocol, context);
    }

    private HttpRequest request(byte[] body, String type) {
        return HttpRequest.newBuffered("POST", "/example.Echo/echo", Map.of("content-type", type), body, 2);
    }

    private byte[] frame(int compressed, byte[] body) {
        return ByteBuffer.allocate(5 + body.length).put((byte) compressed).putInt(body.length).put(body).array();
    }
}
