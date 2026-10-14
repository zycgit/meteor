/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.unit;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.grpc.GrpcUnitAccess.Codec;
import net.hasor.meteor.protocol.grpc.scenario.example.Echo;
import net.hasor.meteor.protocol.grpc.scenario.example.EchoImpl;
import net.hasor.meteor.protocol.grpc.scenario.example.Message;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcCodecTest {
    @Test
    public void gzipInputIsDecodedBeforeDispatchAndUsesTheExistingTimeout() throws Exception {
        // 准备 JSON 服务及携带压缩、超时和认证头的请求。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            byte[] json = "{\"value\":\"compressed\",\"count\":9}".getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(json);
            }
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/grpc+json"), //
                    "grpc-encoding", List.of("gzip"), //
                    "grpc-timeout", List.of("1001u"), //
                    "authorization", List.of("Bearer test", "second-value"));
            byte[] frame = this.frame(1, bytes.toByteArray());
            HttpRequest input = HttpRequest.newBuffered("POST", "/example.Echo/echo", headers, frame, 2, Map.of());

            // 解码后校验超时换算、元数据和解压后的业务参数。
            RequestPayload request = codec.receive(input);
            assertEquals(2, request.getClientTimeout());
            assertEquals("Bearer test", request.getOption("authorization"));
            Message value = (Message) request.getParameterValues().get(0);
            assertEquals("compressed", value.getValue());
            assertEquals(9, value.getCount());
        }
    }

    @Test
    public void rejectsTruncatedUnaryFrame() throws Exception {
        // 准备普通调用的编解码器及不足五字节的帧。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);

            // 接收时拒绝缺失帧头的请求。
            HttpRequest request = this.request(new byte[4], "application/grpc+json");
            assertThrows(IOException.class, () -> codec.receive(request));
        }
    }

    @Test
    public void rejectsInvalidCompressionFlag() throws Exception {
        // 准备压缩标志为 2 的非法请求帧。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            byte[] message = "{}".getBytes(StandardCharsets.UTF_8);

            // 压缩标志只能为 0 或 1。
            byte[] invalidFrame = this.frame(2, message);
            HttpRequest request = this.request(invalidFrame, "application/grpc+json");
            assertThrows(IOException.class, () -> codec.receive(request));
        }
    }

    @Test
    public void rejectsMultipleUnaryMessages() throws Exception {
        // 将两条合法消息拼入同一个普通调用。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            byte[] message = "{}".getBytes(StandardCharsets.UTF_8);
            byte[] one = this.frame(0, message);
            ByteBuffer combined = ByteBuffer.allocate(one.length * 2);
            byte[] two = combined.put(one).put(one).array();

            // 普通调用只允许一条消息。
            HttpRequest request = this.request(two, "application/grpc+json");
            assertThrows(IOException.class, () -> codec.receive(request));
        }
    }

    @Test
    public void rejectsNonGrpcContentType() throws Exception {
        // 准备正文合法但媒体类型不是 gRPC 的请求。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            byte[] message = "{}".getBytes(StandardCharsets.UTF_8);
            byte[] one = this.frame(0, message);

            // 在解码业务正文前拒绝错误的媒体类型。
            HttpRequest request = this.request(one, "text/plain");
            assertThrows(IllegalArgumentException.class, () -> codec.receive(request));
        }
    }

    @Test
    public void rejectsUnknownSerializer() throws Exception {
        // 准备指定不存在序列化器的请求。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            byte[] message = "{}".getBytes(StandardCharsets.UTF_8);
            byte[] one = this.frame(0, message);

            // 不将未知编码回退为服务的默认编码。
            HttpRequest request = this.request(one, "application/grpc+missing");
            assertThrows(UnsupportedOperationException.class, () -> codec.receive(request));
        }
    }

    @Test
    public void rejectsSwitchingThePublishedSerializer() throws Exception {
        // 服务发布 JSON 编码，但请求声明 Java 编码。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            byte[] message = "{}".getBytes(StandardCharsets.UTF_8);
            byte[] one = this.frame(0, message);

            // 调用方不能擅自切换服务发布的序列化器。
            HttpRequest request = this.request(one, "application/grpc+java");
            assertThrows(UnsupportedOperationException.class, () -> codec.receive(request));
        }
    }

    @Test
    public void compressedSizeLimitIsAppliedToExpandedBytes() throws Exception {
        // 压缩后的正文很小，但解压后超过消息大小限制。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 64);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(new byte[1000]);
            }
            byte[] frame = this.frame(1, bytes.toByteArray());
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/grpc+json"), //
                    "grpc-encoding", List.of("gzip"));
            HttpRequest request = HttpRequest.newBuffered("POST", "/example.Echo/echo", headers, frame, 2, Map.of());

            // 按解压后的大小拒绝请求，并返回资源耗尽状态。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.receive(request));
            assertEquals(8, failure.getCode());
            assertTrue(failure.getMessage().contains("Decompressed"));
        }
    }

    @Test
    public void protobufDoesNotFallBackWhenTheConsumerHasNoResource() throws Exception {
        // 注册没有绑定 proto 资源的 Protobuf 订阅声明。
        try (MetContext context = this.configuration().buildContext()) {
            var publication = context.publisher().rsfService(Echo.class);
            MetBindInfo<Echo> service = publication.aliasName("grpc", "example.Echo").serialize("Protobuf").register();
            RequestPayload request = new RequestPayload();
            request.setServiceGroup(service.getBindGroup());
            request.setServiceName(service.getBindName());
            request.setServiceVersion(service.getBindVersion());
            request.setTargetMethod("echo");
            request.setSerializeType("Protobuf");
            request.addParameter(Message.class.getName(), new Message("value", 1));

            // 订阅方的运行期编码仍明确拒绝缺失资源，不自动改成另一种编码。
            Codec codec = this.codec(context, 1024);
            var failure = assertThrows(IllegalArgumentException.class, () -> codec.encode(request));
            assertEquals("Protobuf service must bind proto file.", failure.getMessage());
        }
    }

    @Test
    public void remoteStatusKeepsUnicodeAndLiteralPlusSigns() throws Exception {
        // 准备带百分号编码中文、空格及字面加号的错误尾部。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            RequestPayload request = new RequestPayload();
            request.setSerializeType("Json");
            Map<String, List<String>> trailers = Map.of( //
                    "grpc-status", List.of("7"), //
                    "grpc-message", List.of("%E4%B8%AD%E6%96%87+a%20b"));
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), new byte[0], trailers);

            // 解码远端状态时保留消息原文及对应的框架错误码。
            MetException failure = assertThrows(MetException.class, () -> codec.decode(request, response));
            assertEquals(ProtocolStatus.Forbidden, failure.getStatus());
            assertTrue(failure.getMessage().contains("中文+a b"));
        }
    }

    @Test
    public void missingGrpcStatusIsReportedAsUnknown() throws Exception {
        // 准备缺少 grpc-status 的 HTTP 成功响应。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Codec codec = this.codec(context, 1024);
            RequestPayload request = new RequestPayload();
            request.setSerializeType("Json");
            HttpResponse missingStatus = HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of());

            // HTTP 成功不代表 RPC 成功，缺失状态应报告 UNKNOWN。
            var missing = assertThrows(GrpcStatusException.class, () -> codec.decode(request, missingStatus));
            assertEquals(2, missing.getCode());
        }
    }

    @Test
    public void importedSchemaRetainsFieldNumbers() throws Exception {
        // import 的消息字段必须完整保留，不能只加载类型名。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/echo.proto"));

        // 读取导入消息的描述，核对输入和输出字段编号。
        var method = resource.method("example.Echo", "echo");
        var inputField = method.getInputType().findFieldByName("value");
        var outputField = method.getOutputType().findFieldByName("count");
        assertEquals(7, inputField.getNumber());
        assertEquals(2, outputField.getNumber());
    }

    @Test
    public void missingSchemaImportFailsClearly() throws Exception {
        // 写入引用不存在依赖文件的临时 proto。
        Path file = Files.createTempFile("meteor-missing-import-", ".proto");
        try {
            String schema = "syntax = \"proto3\"; import \"missing.proto\"; message Empty {}";
            Files.writeString(file, schema);

            // 加载失败保留 IO 异常，并在退出时删除临时文件。
            assertThrows(IOException.class, () -> new GrpcProtoResource(file.toUri().toURL()));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    public void incrementalFramingSeparatesMessages() throws Exception {
        // 将两个独立消息帧连续写入同一个输入。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);
            byte[] first = this.frame(0, "first".getBytes(StandardCharsets.UTF_8));
            byte[] second = this.frame(0, "second".getBytes(StandardCharsets.UTF_8));
            ByteBuffer combined = ByteBuffer.allocate(first.length + second.length);
            byte[] both = combined.put(first).put(second).array();

            // 逐帧读取，确认消息边界和输入结束都可辨认。
            ByteArrayInputStream source = new ByteArrayInputStream(both);
            byte[] firstMessage = codec.readMessage(source, null);
            assertEquals("first", new String(firstMessage, StandardCharsets.UTF_8));
            byte[] secondMessage = codec.readMessage(source, null);
            assertEquals("second", new String(secondMessage, StandardCharsets.UTF_8));
            assertNull(codec.readMessage(source, null));
        }
    }

    @Test
    public void rejectsTruncatedStreamHeader() throws Exception {
        // 准备只有三字节的流式帧头。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);
            ByteArrayInputStream input = new ByteArrayInputStream(new byte[3]);

            // 读取流时拒绝不完整的帧头。
            assertThrows(IOException.class, () -> codec.readMessage(input, null));
        }
    }

    @Test
    public void rejectsTruncatedStreamBody() throws Exception {
        // 帧头声明四字节正文，但输入在帧头后立即结束。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);
            byte[] header = ByteBuffer.allocate(5).put((byte) 0).putInt(4).array();
            ByteArrayInputStream input = new ByteArrayInputStream(header);

            // 读取流时拒绝不完整的消息正文。
            assertThrows(IOException.class, () -> codec.readMessage(input, null));
        }
    }

    @Test
    public void rejectsInvalidStreamCompressionFlag() throws Exception {
        // 准备压缩标志非法的流式消息。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);
            ByteArrayInputStream input = new ByteArrayInputStream(this.frame(2, new byte[0]));

            // 读取流时拒绝非法压缩标志。
            assertThrows(IOException.class, () -> codec.readMessage(input, null));
        }
    }

    @Test
    public void rejectsOversizedStreamMessage() throws Exception {
        // 帧头声明超过消息上限的正文长度。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);
            byte[] header = ByteBuffer.allocate(5).put((byte) 0).putInt(Integer.MAX_VALUE).array();
            ByteArrayInputStream input = new ByteArrayInputStream(header);

            // 在分配正文前拒绝超限长度，并返回资源耗尽状态。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.readMessage(input, null));
            assertEquals(8, failure.getCode());
        }
    }

    @Test
    public void remoteDeadlineUsesTheSameExceptionTypeAsTheLocalCaller() throws Exception {
        // 准备服务端返回的 DEADLINE_EXCEEDED 错误尾部。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);
            RequestPayload request = new RequestPayload();
            Map<String, List<String>> trailers = Map.of( //
                    "grpc-status", List.of("4"), //
                    "grpc-message", List.of("deadline%20expired"));
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), new byte[0], trailers);

            // 远端截止期限映射到框架的统一超时异常。
            var failure = assertThrows(MetTimeoutException.class, () -> codec.decode(request, response));
            assertTrue(failure.getMessage().contains("deadline expired"));
        }
    }

    @Test
    public void invalidArgumentMapsToGrpcInvalidArgument() throws Exception {
        // 准备需要转换为协议状态的参数异常。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);

            // 错误通过 gRPC 尾部返回，HTTP 状态仍为成功。
            HttpResponse response = codec.error(new IllegalArgumentException("invalid argument"));
            assertEquals(200, response.status());
            assertEquals(List.of("3"), response.trailers().get("grpc-status"));
            assertEquals(List.of("invalid%20argument"), response.trailers().get("grpc-message"));
        }
    }

    @Test
    public void malformedInputMapsToGrpcInvalidArgument() throws Exception {
        // 准备需要转换为协议状态的输入 IO 异常。
        try (MetContext context = this.configuration().buildContext()) {
            Codec codec = this.codec(context, 1024);

            // 错误通过 gRPC 尾部返回，并对描述执行百分号编码。
            HttpResponse response = codec.error(new IOException("malformed input"));
            assertEquals(200, response.status());
            assertEquals(List.of("3"), response.trailers().get("grpc-status"));
            assertEquals(List.of("malformed%20input"), response.trailers().get("grpc-message"));
        }
    }

    private void publish(MetContext context) {
        var publication = context.publisher().rsfService(Echo.class, new EchoImpl());
        publication.aliasName("grpc", "example.Echo").serialize("Json").register();
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.connector("http").protocol("grpc");
        return configuration;
    }

    private Codec codec(MetContext context, int limit) {
        Map<String, String> options = Map.of("contextPath", "/", "maxMessageSize", String.valueOf(limit));
        ProtocolConfig protocol = new ProtocolConfig("grpc", "grpc", "grpc", options);
        return new Codec(protocol, context);
    }

    private HttpRequest request(byte[] body, String type) {
        Map<String, List<String>> headers = Map.of("content-type", List.of(type));
        return HttpRequest.newBuffered("POST", "/example.Echo/echo", headers, body, 2, Map.of());
    }

    private byte[] frame(int compressed, byte[] body) {
        // gRPC 帧依次写入压缩标志、正文长度和正文。
        ByteBuffer frame = ByteBuffer.allocate(5 + body.length);
        frame.put((byte) compressed);
        frame.putInt(body.length);
        frame.put(body);
        return frame.array();
    }
}
