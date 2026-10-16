/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Any;
import com.google.protobuf.DynamicMessage;
import net.hasor.example.protocol.grpc.Echo;
import net.hasor.example.protocol.grpc.EchoImpl;
import net.hasor.example.protocol.grpc.Message;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcCompletenessTest {
    public interface Types {
        JsonNode roundTrip(JsonNode request);

        JsonNode empty(JsonNode request);
    }

    @Test
    public void binaryMetadataDecodesCombinedAndPaddedValues() throws Exception {
        // 准备逗号合并值、空值和不同填充形式的二进制头部。
        List<String> binaryValues = List.of("AP8=, , YQ", "Yg==");
        Map<String, List<String>> headers = Map.of("x-data-bin", binaryValues, "x-text", List.of("one", "two"));
        ProtocolConfig config = ProtocolConfig.newConfig("grpc", "grpc", Map.of());
        GrpcHttpCodec codec = new GrpcHttpCodec(config, null);
        OptionInfo options = new OptionInfo();
        codec.readHeaders(headers, options);
        GrpcMetadata metadata = GrpcMetadata.from(options);

        // 解码时按原有顺序保留每个二进制值。
        List<byte[]> data = metadata.getBinaryHeader("x-data-bin");
        assertArrayEquals(new byte[] { 0, -1 }, data.get(0));
        assertArrayEquals(new byte[0], data.get(1));
        assertArrayEquals(new byte[] { 97 }, data.get(2));
        assertArrayEquals(new byte[] { 98 }, data.get(3));
    }

    @Test
    public void metadataSnapshotsAreImmutableAndIndependent() throws Exception {
        // 写入两个同名头部并取得不可变快照。
        GrpcMetadata metadata = GrpcMetadata.create();
        metadata.addHeader("x-text", "one");
        metadata.addHeader("x-text", "two");
        Map<String, List<String>> snapshot = metadata.getHeaders();

        // 后续追加不修改旧快照，快照本身也不能被修改。
        metadata.addHeader("X-Text", "three");
        assertEquals(List.of("one", "two"), snapshot.get("x-text"));
        assertEquals(List.of("one", "two", "three"), metadata.getHeader("x-text"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get("x-text").add("four"));
    }

    @Test
    public void reservedMetadataKeyIsRejected() throws Exception {
        // 创建供用户填写的元数据对象。
        GrpcMetadata metadata = GrpcMetadata.create();

        // 用户不能覆盖协议保留的 grpc-status 头部。
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("grpc-status", "0"));
    }

    @Test
    public void metadataLineBreakIsRejected() throws Exception {
        // 创建供用户填写的元数据对象。
        GrpcMetadata metadata = GrpcMetadata.create();

        // 文本头部拒绝包含换行符的值。
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("x-text", "bad\r\nvalue"));
    }

    @Test
    public void binaryMetadataRequiresBinaryApi() throws Exception {
        // 创建供用户填写的元数据对象。
        GrpcMetadata metadata = GrpcMetadata.create();

        // 二进制头部只能通过二进制 API 写入。
        assertThrows(IllegalArgumentException.class, () -> metadata.addHeader("x-data-bin", "value"));
    }

    @Test
    public void http400FallbackMapsToGrpc13() throws Exception {
        // 准备 HTTP 400 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(400, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 13。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void http401FallbackMapsToGrpc16() throws Exception {
        // 准备 HTTP 401 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(401, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 16。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(16, failure.getCode());
        }
    }

    @Test
    public void http403FallbackMapsToGrpc7() throws Exception {
        // 准备 HTTP 403 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(403, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 7。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(7, failure.getCode());
        }
    }

    @Test
    public void http404FallbackMapsToGrpc12() throws Exception {
        // 准备 HTTP 404 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(404, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 12。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void http429FallbackMapsToGrpc14() throws Exception {
        // 准备 HTTP 429 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(429, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 14。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void http502FallbackMapsToGrpc14() throws Exception {
        // 准备 HTTP 502 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(502, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 14。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void http503FallbackMapsToGrpc14() throws Exception {
        // 准备 HTTP 503 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(503, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 14。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void http504FallbackMapsToGrpc14() throws Exception {
        // 准备 HTTP 504 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(504, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 14。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void http200FallbackMapsToGrpc2() throws Exception {
        // 准备 HTTP 200 且未携带 gRPC 状态的响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of());

            // 回退映射为 gRPC 状态 2。
            var trailers = response.trailers();
            var failure = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(response, trailers));
            assertEquals(2, failure.getCode());
        }
    }

    @Test
    public void invalidPercentEncodingRemainsReadable() throws Exception {
        // 构造含非法百分号序列和字面加号的错误描述。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("3"), //
                    "grpc-message", List.of("broken%XX+message"));
            HttpResponse malformed = HttpResponse.newBuffered(200, Map.of(), new byte[0], trailers);

            // 非法转义保留原文，避免遮蔽远端原始错误。
            var status = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(malformed, trailers));
            assertEquals(3, status.getCode());
            assertEquals("broken%XX+message", status.getDescription());
        }
    }

    @Test
    public void conflictingStatusDetailsAreRejected() throws Exception {
        // 准备内部状态码为 7 的错误详情。
        byte[] details = { 8, 7 };

        // 外层状态码为 3 时拒绝矛盾的详情。
        assertThrows(IllegalArgumentException.class, () -> new GrpcStatusException(3, "bad", details, null));
    }

    @Test
    public void errorDetailsCannotOverrideOkStatus() throws Exception {
        // 构造外层成功但详情携带错误码的矛盾响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            GrpcHttpCodec codec = codec(context, Map.of());
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"), //
                    "grpc-status-details-bin", List.of("CAM"));
            HttpResponse contradiction = HttpResponse.newBuffered(200, Map.of(), new byte[0], trailers);

            // 不能用错误详情覆盖成功状态，应报告内部协议错误。
            var error = assertThrows(GrpcStatusException.class, () -> codec.validateStatus(contradiction, trailers));
            assertEquals(13, error.getCode());
        }
    }

    @Test
    public void gzipFrameRoundTripsIndependently() throws Exception {
        // 为可压缩正文开启 gzip 和消息大小上限。
        try (MetContext context = new MetConfiguration().buildContext()) {
            var service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo").serialize("Json").register();
            Map<String, String> options = Map.of("compression", "gzip", "maxMessageSize", "2048");
            GrpcHttpCodec codec = codec(context, options);
            byte[] plain = "x".repeat(1000).getBytes(StandardCharsets.UTF_8);

            // 检查压缩标志和体积，再解压验证原始消息。
            byte[] framed = codec.frame(plain, codec.requestCompression());
            assertEquals(1, framed[0]);
            assertTrue(framed.length < plain.length);
            assertArrayEquals(plain, codec.readMessage(new ByteArrayInputStream(framed), "gzip"));
        }
    }

    @Test
    public void responseUsesIdentityWithoutPeerGzipSupport() throws Exception {
        // 服务端支持 gzip，但请求未声明接受压缩。
        try (MetContext context = new MetConfiguration().buildContext()) {
            var service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo").serialize("Json").register();
            Map<String, String> options = Map.of("compression", "gzip", "maxMessageSize", "2048");
            GrpcHttpCodec codec = codec(context, options);
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("default");
            request.setServiceName(Echo.class.getName());
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            request.setSerializeType("Json");
            ResponsePayload response = new ResponsePayload();
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData(Message.of("x".repeat(1000), 1));

            // 响应保持 identity，消息帧也不设置压缩标志。
            HttpResponse identity = codec.encode(request, response);
            assertEquals("identity", identity.header("grpc-encoding"));
            assertEquals(0, identity.body()[0]);
        }
    }

    @Test
    public void responseUsesGzipWhenPeerAcceptsIt() throws Exception {
        // 服务端支持 gzip，并准备可压缩响应。
        try (MetContext context = new MetConfiguration().buildContext()) {
            var service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo").serialize("Json").register();
            Map<String, String> options = Map.of("compression", "gzip", "maxMessageSize", "2048");
            GrpcHttpCodec codec = codec(context, options);
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("default");
            request.setServiceName(Echo.class.getName());
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            request.setSerializeType("Json");
            ResponsePayload response = new ResponsePayload();
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData(Message.of("x".repeat(1000), 1));

            // 请求声明接受 gzip 后，响应与帧头均使用压缩。
            request.addOption("grpc-accept-encoding", "identity, gzip");
            HttpResponse compressed = codec.encode(request, response);
            assertEquals("gzip", compressed.header("grpc-encoding"));
            assertEquals(1, compressed.body()[0]);
        }
    }

    @Test
    public void gzipEncodingEnforcesUncompressedLimit() throws Exception {
        // 启用 gzip，并将未压缩消息上限设为 2048 字节。
        try (MetContext context = new MetConfiguration().buildContext()) {
            var service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo").serialize("Json").register();
            Map<String, String> options = Map.of("compression", "gzip", "maxMessageSize", "2048");
            GrpcHttpCodec codec = codec(context, options);

            // 2049 字节消息即使可压缩，也必须被上限拒绝。
            var limit = assertThrows(GrpcStatusException.class, () -> codec.frame(new byte[2049], "gzip"));
            assertEquals(8, limit.getCode());
        }
    }

    @Test
    public void unknownCompressedEncodingIsRejected() throws Exception {
        // 生成合法 gzip 消息帧。
        try (MetContext context = new MetConfiguration().buildContext()) {
            var service = context.publisher().rsfService(Echo.class, new EchoImpl());
            service.aliasName("grpc", "example.Echo").serialize("Json").register();
            Map<String, String> options = Map.of("compression", "gzip", "maxMessageSize", "2048");
            GrpcHttpCodec codec = codec(context, options);
            byte[] plain = "x".repeat(1000).getBytes(StandardCharsets.UTF_8);
            byte[] framed = codec.frame(plain, codec.requestCompression());

            // 以不支持的 snappy 解码时返回未实现状态。
            ByteArrayInputStream body = new ByteArrayInputStream(framed);
            var unsupported = assertThrows(GrpcStatusException.class, () -> codec.readMessage(body, "snappy"));
            assertEquals(12, unsupported.getCode());
        }
    }

    @Test
    public void protobufComplexTypesRoundTripWithIndependentDescriptors() throws Exception {
        // 加载含复杂类型的 proto，准备边界值和嵌套对象。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/complex.proto"));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode value = mapper.readTree("""
                {"signedValue":"-9223372036854775808","unsignedValue":"18446744073709551615",
                 "data":"AP8=","children":[{"name":"child"}],"entries":{"key":{"name":"entry"}},
                 "presentZero":0,"index":0,"kind":"FIRST",
                 "payload":{"@type":"type.googleapis.com/example.Nested","name":"inside"},
                 "instant":"2026-10-12T00:00:00Z","elapsed":"1.250s","wrapped":0,
                 "extra":{"nullValue":null,"list":[1.0,true,"text"]},"mask":"signedValue,children.name"}
                """);
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher() //
                    .rsfService(Types.class) //
                    .aliasName("grpc", "example.Types") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            GrpcHttpCodec codec = codec(context, Map.of());
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("default");
            request.setServiceName(Types.class.getName());
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("roundTrip");
            request.setSerializeType("Protobuf");
            request.addParameter(JsonNode.class.getName(), value);

            // 用独立动态消息检查线格式，再还原为原始 JSON 值。
            HttpRequest encoded = codec.encode(request);
            byte[] bytes = codec.readMessage(new ByteArrayInputStream(encoded.body()), null);
            var type = resource.method("example.Types", "roundTrip").getInputType();
            DynamicMessage wire = DynamicMessage.parseFrom(type, bytes);
            assertEquals(Long.MIN_VALUE, wire.getField(type.findFieldByNumber(1)));
            assertEquals(-1L, wire.getField(type.findFieldByNumber(2)));
            assertTrue(wire.hasField(type.findFieldByNumber(6)));
            assertTrue(wire.hasField(type.findFieldByNumber(8)));
            DynamicMessage nested = (DynamicMessage) wire.getField(type.findFieldByNumber(10));
            Any any = Any.parseFrom(nested.toByteArray());
            assertEquals("type.googleapis.com/example.Nested", any.getTypeUrl());
            byte[] expectedNested = { 10, 6, 105, 110, 115, 105, 100, 101 };
            assertArrayEquals(expectedNested, any.getValue().toByteArray());
            assertEquals(value, codec.decodeValue(request, JsonNode.class, bytes, true));
        }
    }

    @Test
    public void absentOptionalAndOneofFieldsRemainAbsent() throws Exception {
        // 加载描述并注册使用 Protobuf 的订阅声明。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/complex.proto"));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode value = mapper.readTree("""
                {"signedValue":"-9223372036854775808","unsignedValue":"18446744073709551615",
                 "data":"AP8=","children":[{"name":"child"}],"entries":{"key":{"name":"entry"}},
                 "presentZero":0,"index":0,"kind":"FIRST",
                 "payload":{"@type":"type.googleapis.com/example.Nested","name":"inside"},
                 "instant":"2026-10-12T00:00:00Z","elapsed":"1.250s","wrapped":0,
                 "extra":{"nullValue":null,"list":[1.0,true,"text"]},"mask":"signedValue,children.name"}
                """);
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher() //
                    .rsfService(Types.class) //
                    .aliasName("grpc", "example.Types") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            GrpcHttpCodec codec = codec(context, Map.of());
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("default");
            request.setServiceName(Types.class.getName());
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("roundTrip");
            request.setSerializeType("Protobuf");
            request.addParameter(JsonNode.class.getName(), value);
            // 未出现的 optional / oneof 保持未出现，空消息保留合法的零长度 Protobuf 正文。
            request.getParameterValues().set(0, mapper.createObjectNode());
            HttpRequest encoded = codec.encode(request);
            ByteArrayInputStream body = new ByteArrayInputStream(encoded.body());
            byte[] absent = codec.readMessage(body, null);
            Object decoded = codec.decodeValue(request, JsonNode.class, absent, true);
            assertEquals(mapper.createObjectNode(), decoded);
        }
    }

    @Test
    public void emptyProtobufMessageHasOnlyTheFrameHeader() throws Exception {
        // 加载描述并注册使用 Protobuf 的订阅声明。
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/complex.proto"));
        ObjectMapper mapper = new ObjectMapper();
        JsonNode value = mapper.readTree("""
                {"signedValue":"-9223372036854775808","unsignedValue":"18446744073709551615",
                 "data":"AP8=","children":[{"name":"child"}],"entries":{"key":{"name":"entry"}},
                 "presentZero":0,"index":0,"kind":"FIRST",
                 "payload":{"@type":"type.googleapis.com/example.Nested","name":"inside"},
                 "instant":"2026-10-12T00:00:00Z","elapsed":"1.250s","wrapped":0,
                 "extra":{"nullValue":null,"list":[1.0,true,"text"]},"mask":"signedValue,children.name"}
                """);
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher() //
                    .rsfService(Types.class) //
                    .aliasName("grpc", "example.Types") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            GrpcHttpCodec codec = codec(context, Map.of());
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("default");
            request.setServiceName(Types.class.getName());
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("roundTrip");
            request.setSerializeType("Protobuf");
            request.addParameter(JsonNode.class.getName(), value);

            // 切换为空消息方法，确认正文只包含五字节帧头。
            request.getParameterValues().set(0, mapper.createObjectNode());
            request.setTargetMethod("empty");
            byte[] empty = codec.encode(request).body();
            assertArrayEquals(new byte[5], empty);
        }
    }

    private static GrpcHttpCodec codec(MetContext context, Map<String, String> options) {
        Map<String, String> mounted = new LinkedHashMap<>(options);
        mounted.putIfAbsent("contextPath", "/");
        ProtocolConfig config = ProtocolConfig.newConfig("grpc", "grpc", mounted);
        return new GrpcHttpCodec(config, context);
    }
}
