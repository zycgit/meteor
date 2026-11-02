/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;
import com.caucho.hessian.io.Hessian2Output;
import com.google.protobuf.CodedOutputStream;
import net.hasor.example.protocol.triple.TripleDemoService;
import net.hasor.example.protocol.triple.TripleDemoServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** HTTP 请求、Java 方法和 Triple 编码之间的边界映射。 */
public class TripleHttpCodecTest {
    @Test
    public void jsonPositionalArrayPreservesMultipleParameterTypes() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 按接口位置恢复字符串和 primitive 参数。
            HttpRequest input = this.input("greet", "application/json", "[\"hello\",3]", 1, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals(List.of(String.class.getName(), "int"), request.getParameterTypes());
            assertEquals(List.of("hello", 3), request.getParameterValues());
        }
    }

    @Test
    public void jsonObjectCanRepresentAnUnambiguousSingleArgument() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 单个 DTO 可直接使用对象表示，不需要人为包裹参数数组。
            HttpRequest input = this.input("echo", "application/json", "{\"text\":\"你好\",\"number\":5}", 1, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            TripleMessage argument = (TripleMessage) request.getParameterValues().get(0);
            assertEquals("你好", argument.getText());
            assertEquals(5, argument.getNumber());
        }
    }

    @Test
    public void jsonArrayCanCarryAnExplicitNullReference() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 引用参数的 null 是业务值，不当作缺少参数。
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals(1, request.getParameterValues().size());
            assertNull(request.getParameterValues().get(0));
        }
    }

    @Test
    public void jsonWrongArgumentCountIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 参数数目不符时不能截断或补齐。
            HttpRequest input = this.input("greet", "application/json", "[\"hello\"]", 1, Map.of());
            RequestPayload request = codec.open(input);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void jsonTrailingDocumentIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 两个 JSON 文档不是一次合法调用的正文。
            HttpRequest input = this.input("echo", "application/json", "[{\"text\":\"first\"}] {}", 1, Map.of());
            RequestPayload request = codec.open(input);
            assertThrows(IOException.class, () -> codec.readRequest(request, input));
        }
    }

    @Test
    public void jsonDuplicateObjectKeyIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 重复字段不能依赖不同 JSON 库的覆盖顺序。
            String body = "[{\"text\":\"first\",\"text\":\"second\"}]";
            HttpRequest input = this.input("echo", "application/json", body, 1, Map.of());
            RequestPayload request = codec.open(input);
            assertThrows(IOException.class, () -> codec.readRequest(request, input));
        }
    }

    @Test
    public void jsonAmbiguousOverloadIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // JSON 数字和文本转换都可能命中时，要求调用方使用带类型的 wrapper。
            HttpRequest input = this.input("overloaded", "application/json", "[42]", 1, Map.of());
            RequestPayload request = codec.open(input);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void wrapperSelectsThePrimitiveOverloadByDeclaredSignature() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 带类型的 wrapper 能精确选择 primitive 重载。
            TripleValueCodec values = new TripleValueCodec(4096, this.getClass().getClassLoader());
            byte[] body = values.encodeArguments(new Object[] { 42 }, new Class<?>[] { int.class });
            HttpRequest input = this.framed("overloaded", codec.frame(body, "identity"), Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals(List.of("int"), request.getParameterTypes());
            assertEquals(int.class, codec.target(request).getParameterTypes()[0]);
        }
    }

    @Test
    public void wrapperSelectsTheStringOverloadByDeclaredSignature() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 文本内容即使看起来像数字，也按声明的 String 签名路由。
            TripleValueCodec values = new TripleValueCodec(4096, this.getClass().getClassLoader());
            byte[] body = values.encodeArguments(new Object[] { "42" }, new Class<?>[] { String.class });
            HttpRequest input = this.framed("overloaded", codec.frame(body, "identity"), Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals(List.of(String.class.getName()), request.getParameterTypes());
            assertEquals(String.class, codec.target(request).getParameterTypes()[0]);
        }
    }

    @Test
    public void wrapperAcceptsThePublishedTripleWrapperMediaType() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 显式 wrapper 媒体类型与标准 Dubbo gRPC+proto 包装具有同一请求结构。
            TripleValueCodec values = new TripleValueCodec(4096, this.getClass().getClassLoader());
            Object[] arguments = { "hello", 2 };
            Class<?>[] types = { String.class, int.class };
            byte[] body = values.encodeArguments(arguments, types);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/triple+wrapper"));
            byte[] frame = codec.frame(body, "identity");
            HttpRequest input = HttpRequest.newBuffered("POST", "/rpc/example.Pojo/greet", headers, frame, 2, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals(List.of("hello", 2), request.getParameterValues());
        }
    }

    @Test
    public void bareHttpPostUsesMillisecondsTimeout() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 普通 HTTP 的超时单位固定为毫秒。
            Map<String, List<String>> headers = Map.of("tri-service-timeout", List.of("1234"));
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            assertEquals(1234, codec.open(input).getClientTimeout());
        }
    }

    @Test
    public void grpcTimeoutRoundsSubMillisecondsUp() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // gRPC 的微秒截止期限向上取整，避免提前截断。
            HttpRequest input = this.framed("echo", new byte[0], Map.of("grpc-timeout", List.of("1001u")));
            assertEquals(2, codec.open(input).getClientTimeout());
        }
    }

    @Test
    public void invalidBareHttpTimeoutIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 非整数超时不作为默认配置处理。
            Map<String, List<String>> headers = Map.of("tri-service-timeout", List.of("1.5"));
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void invalidGrpcTimeoutUnitIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 不识别的 gRPC 超时单位必须明确报错。
            HttpRequest input = this.framed("echo", new byte[0], Map.of("grpc-timeout", List.of("20d")));
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void duplicateTextMetadataReachesTheRequestView() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 同名应用元数据的顺序和全部值都要保留。
            Map<String, List<String>> headers = Map.of("x-label", List.of("first", "second"));
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            RequestPayload request = codec.open(input);
            assertEquals(List.of("first", "second"), GrpcMetadata.from(request).getHeader("x-label"));
        }
    }

    @Test
    public void binaryMetadataReachesTheDecodedRequestView() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 二进制元数据以 Base64 在线上传递，在应用视图中恢复原字节。
            HttpRequest input = this.framed("echo", new byte[0], Map.of("trace-bin", List.of("AQID", "BAU=")));
            RequestPayload request = codec.open(input);
            List<byte[]> binary = GrpcMetadata.from(request).getBinaryHeader("trace-bin");
            assertEquals(2, binary.size());
            assertArrayEquals(new byte[] { 1, 2, 3 }, binary.get(0));
            assertArrayEquals(new byte[] { 4, 5 }, binary.get(1));
        }
    }

    @Test
    public void wrongServiceGroupDoesNotFallBackToAnotherProvider() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 指定分组不存在时不跨组调用默认提供者。
            Map<String, List<String>> headers = Map.of("tri-service-group", List.of("missing-group"));
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void wrongServiceVersionDoesNotFallBackToAnotherProvider() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 指定版本不存在时不跨版本寻找同名接口。
            Map<String, List<String>> headers = Map.of("tri-service-version", List.of("9.9.9"));
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void grpcFramingCannotBeSentOnHttp1() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // gRPC 包装仍要求 HTTP/2，普通 HTTP/1 应使用裸消息模式。
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc+proto"));
            String path = "/rpc/example.Pojo/echo";
            HttpRequest input = HttpRequest.newBuffered("POST", path, headers, new byte[0], 1, Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void getDoesNotBecomeAnRpcInvocation() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 固定的 RPC 入口只接受 POST。
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            String path = "/rpc/example.Pojo/echo";
            HttpRequest input = HttpRequest.newBuffered("GET", path, headers, new byte[0], 1, Map.of());
            TripleHttpStatusException failure = assertThrows(TripleHttpStatusException.class, //
                    () -> codec.open(input));
            assertEquals(405, failure.getHttpStatus());
        }
    }

    @Test
    public void queryStringCannotChangeTheRpcTarget() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // RPC 路径不得混入查询参数。
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            String path = "/rpc/example.Pojo/echo?method=fail";
            HttpRequest input = HttpRequest.newBuffered("POST", path, headers, new byte[0], 1, Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void pathOutsideTheMountIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 同一端口的其他协议上下文不能被 Triple 解析。
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            String path = "/other/example.Pojo/echo";
            HttpRequest input = HttpRequest.newBuffered("POST", path, headers, new byte[0], 1, Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void unknownMethodIsRejectedBeforeBodyDecode() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 错误的方法名不等待正文解码才报错。
            HttpRequest input = this.input("missing", "application/json", "[null]", 1, Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void protobufBodyNeedsAProtobufResource() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 普通 Java wrapper 服务不能把裸 Protobuf 猜成 Hessian 数据。
            HttpRequest input = this.input("echo", "application/proto", "", 1, Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void grpcDataRequestDoesNotGuessPojoWrapperEncoding() throws Exception {
        // 无 proto 的 Java 服务使用 Hessian wrapper，不能猜测通用 data 正文的契约。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.input("echo", "application/grpc+data", "", 2, Map.of());

            // 缺少原生 Protobuf 定义时，别名不能绕过 wrapper 的媒体检查。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void grpcDataResponseDoesNotReplacePojoWrapperEncoding() throws Exception {
        // POJO 请求需要原来的 Hessian wrapper，不继承原生 Protobuf 的别名兼容。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc+data"));
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = HttpResponse.newBuffered(200, headers, new byte[0], trailers);

            // 在解析正文之前拒绝响应编码变化。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void unknownContentTypeIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 不把任意二进制媒体类型当成 JSON。
            HttpRequest input = this.input("echo", "application/octet-stream", "", 1, Map.of());
            TripleHttpStatusException failure = assertThrows(TripleHttpStatusException.class, //
                    () -> codec.open(input));
            assertEquals(415, failure.getHttpStatus());
        }
    }

    @Test
    public void unknownContentCompressionIsRejected() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 未实现的压缩算法不能按 identity 解码。
            Map<String, List<String>> headers = Map.of("content-encoding", List.of("brotli"));
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void gzipBareHttpRequestIsDecodedBeforeJson() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // HTTP 的 gzip 压缩覆盖整个裸 JSON 正文。
            byte[] json = "[\"hello\",3]".getBytes(StandardCharsets.UTF_8);
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/json"), //
                    "content-encoding", List.of("gzip"));
            byte[] compressed = this.gzip(json);
            String path = "/rpc/example.Pojo/greet";
            HttpRequest input = HttpRequest.newBuffered("POST", path, headers, compressed, 1, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals(List.of("hello", 3), request.getParameterValues());
        }
    }

    @Test
    public void gzipBareHttpLimitAppliesToExpandedBytes() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of("maxMessageSize", "128"));

            // 小型压缩正文展开后仍必须受消息上限限制。
            String text = "x".repeat(1024);
            byte[] json = ("[\"" + text + "\",3]").getBytes(StandardCharsets.UTF_8);
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/json"), //
                    "content-encoding", List.of("gzip"));
            byte[] compressed = this.gzip(json);
            String path = "/rpc/example.Pojo/greet";
            HttpRequest input = HttpRequest.newBuffered("POST", path, headers, compressed, 1, Map.of());
            RequestPayload request = codec.open(input);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(8, failure.getCode());
        }
    }

    @Test
    public void outboundPlainJsonUsesAnOrderedParameterArray() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of("httpMode", "json", "httpVersion", "1"));

            // 公开请求模型保留参数位置，HTTP 不添加 gRPC 五字节帧头。
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 3);
            HttpRequest encoded = codec.encode(request);
            assertEquals("/rpc/example.Pojo/greet", encoded.uri());
            assertEquals("application/json", encoded.header("content-type"));
            assertEquals("[\"hello\",3]", new String(encoded.body(), StandardCharsets.UTF_8));
            assertEquals(1, encoded.version());
        }
    }

    @Test
    public void outboundGroupAndVersionAreExplicitHeaders() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 分组与版本属于调用坐标，不拼进服务路径。
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 3);
            HttpRequest encoded = codec.encode(request);
            assertEquals(service.getBindGroup(), encoded.header("tri-service-group"));
            assertEquals(service.getBindVersion(), encoded.header("tri-service-version"));
            assertEquals("/rpc/example.Pojo/greet", encoded.uri());
        }
    }

    @Test
    public void outboundGrpcPreservesDuplicateAndBinaryMetadata() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // gRPC 元数据专用视图承载多值，不被普通 options 覆盖。
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 3);
            GrpcMetadata metadata = GrpcMetadata.from(request);
            metadata.addHeader("x-label", "first");
            metadata.addHeader("x-label", "second");
            metadata.addBinaryHeader("trace-bin", new byte[] { 1, 2, 3 });
            HttpRequest encoded = codec.encode(request);
            assertEquals(List.of("first", "second"), encoded.headers().get("x-label"));
            assertEquals(List.of("AQID"), encoded.headers().get("trace-bin"));
        }
    }

    @Test
    public void bareHttpResponseIsAnUnwrappedBusinessValue() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 返回值不使用参数数组，也不暴露 TripleResponseWrapper。
            HttpRequest input = this.input("greet", "application/json", "[\"hello\",3]", 1, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            ResponsePayload response = this.success("hellohellohello");
            HttpResponse encoded = codec.encode(request, response);
            assertEquals("\"hellohellohello\"", new String(encoded.body(), StandardCharsets.UTF_8));
            assertEquals("application/json", encoded.header("content-type"));
            assertTrue(encoded.trailers().isEmpty());
        }
    }

    @Test
    public void bareHttpVoidResponseIsJsonNull() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // void 方法仍完成普通 RPC，以 JSON null 表示没有返回数据。
            HttpRequest input = this.input("accept", "application/json", "[null]", 1, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            HttpResponse encoded = codec.encode(request, this.success(null));
            assertEquals("null", new String(encoded.body(), StandardCharsets.UTF_8));
            assertNull(codec.decode(request, encoded).getReturnData());
        }
    }

    @Test
    public void wrapperResponseRetainsTheDeclaredResultClass() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 响应中携带声明返回类型并恢复业务值。
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 3);
            HttpResponse encoded = codec.encode(request, this.success("hellohellohello"));
            assertEquals(List.of("0"), encoded.trailers().get("grpc-status"));
            ResponsePayload decoded = codec.decode(request, encoded);
            assertEquals("hellohellohello", decoded.getReturnData());
            assertEquals(String.class.getName(), decoded.getReturnType());
        }
    }

    @Test
    public void successfulResponsePreservesHeadersAndTrailers() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 应用元数据分别保留头部和尾部，不把它们合为一个选项值。
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            ResponsePayload response = this.success("hello");
            GrpcMetadata metadata = GrpcMetadata.from(response);
            metadata.addHeader("x-label", "first");
            metadata.addHeader("x-label", "second");
            metadata.addBinaryTrailer("trace-bin", new byte[] { 7, 8 });
            HttpResponse encoded = codec.encode(request, response);
            ResponsePayload decoded = codec.decode(request, encoded);
            assertEquals(List.of("first", "second"), GrpcMetadata.from(decoded).getHeader("x-label"));
            assertArrayEquals(new byte[] { 7, 8 }, GrpcMetadata.from(decoded).getBinaryTrailer("trace-bin").get(0));
        }
    }

    @Test
    public void responseContentTypeCannotSilentlyChangeEncoding() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 请求和响应必须遵守同一协商的消息编码。
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = HttpResponse.newBuffered(200, headers, new byte[0], trailers);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void plainHttpFailureUsesJsonStatusAndMessage() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // 普通 HTTP 错误使用 HTTP 状态及可独立读取的 JSON 正文。
            HttpRequest input = this.input("greet", "application/json", "[]", 1, Map.of());
            HttpResponse encoded = codec.error(input, new IllegalArgumentException("invalid parameter"));
            assertEquals(400, encoded.status());
            assertEquals("application/json", encoded.header("content-type"));
            String body = new String(encoded.body(), StandardCharsets.UTF_8);
            assertTrue(body.contains("invalid parameter"));
            assertTrue(body.contains("\"status\":3"));
        }
    }

    @Test
    public void grpcFailureUsesTrailersAndPercentEncodedMessage() throws Exception {
        // 准备普通 Java 提供者与独立的协议编解码器。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());

            // gRPC 错误保留 200 状态，并在尾部使用标准状态码。
            HttpRequest input = this.framed("echo", new byte[0], Map.of());
            HttpResponse encoded = codec.error(input, new IllegalArgumentException("invalid parameter"));
            assertEquals(200, encoded.status());
            assertEquals(List.of("3"), encoded.trailers().get("grpc-status"));
            assertEquals(List.of("invalid%20parameter"), encoded.trailers().get("grpc-message"));
        }
    }

    @Test
    public void unauthenticatedHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 401 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(401, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(16, failure.getCode());
        }
    }

    @Test
    public void permissionDeniedHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 403 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(403, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(7, failure.getCode());
        }
    }

    @Test
    public void notFoundHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 404 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(404, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(12, failure.getCode());
        }
    }

    @Test
    public void timeoutHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 408 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(408, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(4, failure.getCode());
        }
    }

    @Test
    public void abortedHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 409 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(409, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(10, failure.getCode());
        }
    }

    @Test
    public void failedPreconditionHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 412 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(412, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(9, failure.getCode());
        }
    }

    @Test
    public void resourceExhaustedHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 413 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(413, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(8, failure.getCode());
        }
    }

    @Test
    public void tooManyRequestsHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 429 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(429, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void headersTooLargeHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 431 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(431, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(8, failure.getCode());
        }
    }

    @Test
    public void badGatewayHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 502 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(502, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void unavailableHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 503 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(503, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void gatewayTimeoutHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 504 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(504, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(14, failure.getCode());
        }
    }

    @Test
    public void unknownServerErrorHttpStatusMapsToTripleStatus() throws Exception {
        // 只验证 HTTP 500 对应的一个协议状态映射。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(500, headers, new byte[0], Map.of());
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(2, failure.getCode());
        }
    }

    @Test
    public void methodRejectionIncludesTheAllowedHttpMethod() throws Exception {
        // 方法校验失败保留 HTTP 405，并明确声明 RPC 入口允许 POST。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            String path = "/rpc/example.Pojo/echo";
            HttpRequest input = HttpRequest.newBuffered("GET", path, headers, new byte[0], 1, Map.of());

            // 将发布入口拒绝转换成可被普通 HTTP 客户端理解的响应。
            TripleHttpStatusException failure = assertThrows(TripleHttpStatusException.class, //
                    () -> codec.open(input));
            HttpResponse response = codec.error(input, failure);
            assertEquals(405, response.status());
            assertEquals("POST", response.header("allow"));
        }
    }

    @Test
    public void unsupportedMediaUsesHttp415() throws Exception {
        // 未知媒体类型属于 HTTP 协商失败。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.input("echo", "application/octet-stream", "", 1, Map.of());

            // 不把协商错误伪装成未知业务方法。
            TripleHttpStatusException failure = assertThrows(TripleHttpStatusException.class, //
                    () -> codec.open(input));
            assertEquals(415, codec.error(input, failure).status());
        }
    }

    @Test
    public void nullJsonCannotSilentlyBecomePrimitiveZero() throws Exception {
        // primitive 参数没有 null 语义，不能依赖 Jackson 的默认零值补齐。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.input("greet", "application/json", "[\"hello\",null]", 1, Map.of());
            RequestPayload request = codec.open(input);

            // 调用者收到参数错误，业务方法不会看到被改写的零值。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void wrapperBusinessExceptionUsesDubboExceptionHeader() throws Exception {
        // Dubbo 普通业务异常使用响应 wrapper，区别于网络终止状态。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            ResponsePayload response = new ResponsePayload();
            response.setStatus(ProtocolStatus.InvokeError);
            response.setFailure(new IllegalStateException("business failure"));

            // 异常头与正常 gRPC 终止状态配合，客户端恢复原业务异常。
            HttpResponse encoded = codec.encode(request, response);
            assertEquals("3", encoded.header("tri-exception-code"));
            assertEquals(List.of("0"), encoded.trailers().get("grpc-status"));
            IllegalStateException failure = assertThrows(IllegalStateException.class, //
                    () -> codec.decode(request, encoded));
            assertEquals("business failure", failure.getMessage());
        }
    }

    @Test
    public void zeroExceptionHeaderKeepsAnOrdinaryWrapperResult() throws Exception {
        // Dubbo 使用 0 表示没有业务异常，不能把普通返回值当成 Exception 解码。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            HttpResponse result = codec.encode(request, this.success("hello"));
            Map<String, List<String>> headers = new LinkedHashMap<>(result.headers());
            headers.put("tri-exception-code", List.of("0"));
            HttpResponse input = HttpResponse.newBuffered(200, headers, result.body(), result.trailers());

            // 响应正文继续按声明的返回类型恢复。
            assertEquals("hello", codec.decode(request, input).getReturnData());
        }
    }

    @Test
    public void plainHttpErrorBodyPreservesTheRemoteDescription() throws Exception {
        // 合法错误正文比仅有 HTTP 状态提供更精确的协议错误。
        try (MetContext context = this.configuration().buildContext()) {
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            byte[] body = "{\"status\":7,\"message\":\"policy rejected\"}".getBytes(StandardCharsets.UTF_8);
            HttpResponse input = HttpResponse.newBuffered(500, headers, body, Map.of());

            // 不使用泛化的 HTTP 500 覆盖远端权限错误。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.validateStatus(input, input.trailers()));
            assertEquals(7, failure.getCode());
            assertEquals("policy rejected", failure.getDescription());
        }
    }

    @Test
    public void explicitGroupSelectsOnlyTheMatchingProvider() throws Exception {
        // 同一协议别名可以指向不同分组，存储的完整服务 ID 决定真实目标。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            context.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .group("blue").aliasName("triple", "example.Pojo").serialize("Hessian2").register();
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("tri-service-group", List.of("blue"));

            // 不通过单值别名索引丢失第二个分组。
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            assertEquals("blue", codec.open(input).getServiceGroup());
        }
    }

    @Test
    public void explicitVersionSelectsOnlyTheMatchingProvider() throws Exception {
        // 同名服务的两个版本使用同一路径，通过版本头明确选择。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            context.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .version("2.0.0").aliasName("triple", "example.Pojo").serialize("Hessian2").register();
            TripleHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("tri-service-version", List.of("2.0.0"));

            // 不把显式版本降级为先注册的版本。
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, headers);
            assertEquals("2.0.0", codec.open(input).getServiceVersion());
        }
    }

    @Test
    public void missingDimensionsCannotSelectAnArbitraryProvider() throws Exception {
        // 别名存在多个分组时，缺少坐标不能依赖 Map 的遍历顺序。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            context.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                    .group("blue").aliasName("triple", "example.Pojo").serialize("Hessian2").register();
            TripleHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.input("echo", "application/json", "[null]", 1, Map.of());

            // 明确返回歧义，促使调用方补齐服务坐标。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.open(input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void protobufStyleCapitalizedMethodUsesTheDeclaredJavaMethod() throws Exception {
        // Protobuf 方法名的首字母大写可映射到正常 Java 小写方法。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.input("Echo", "application/json", "[null]", 1, Map.of());
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals("echo", request.getTargetMethod());
        }
    }

    @Test
    public void unknownOutgoingModeIsRejected() throws Exception {
        // 配置阶段拒绝无法组装的传输或消息选项。
        try (MetContext context = this.configuration().buildContext()) {
            Map<String, String> options = Map.of("httpMode", "missing");
            assertThrows(IllegalArgumentException.class, () -> this.codec(context, options));
        }
    }

    @Test
    public void unknownOutgoingCompressionIsRejected() throws Exception {
        // 配置阶段拒绝无法组装的传输或消息选项。
        try (MetContext context = this.configuration().buildContext()) {
            Map<String, String> options = Map.of("compression", "brotli");
            assertThrows(IllegalArgumentException.class, () -> this.codec(context, options));
        }
    }

    @Test
    public void http3CannotBeAccidentallySelected() throws Exception {
        // 配置阶段拒绝无法组装的传输或消息选项。
        try (MetContext context = this.configuration().buildContext()) {
            Map<String, String> options = Map.of("httpVersion", "3");
            assertThrows(IllegalArgumentException.class, () -> this.codec(context, options));
        }
    }

    @Test
    public void grpcOutgoingModeRequiresHttp2() throws Exception {
        // 配置阶段拒绝无法组装的传输或消息选项。
        try (MetContext context = this.configuration().buildContext()) {
            Map<String, String> options = Map.of("httpVersion", "1");
            assertThrows(IllegalArgumentException.class, () -> this.codec(context, options));
        }
    }

    @Test
    public void messageCapacityMustBePositive() throws Exception {
        // 配置阶段拒绝无法组装的传输或消息选项。
        try (MetContext context = this.configuration().buildContext()) {
            Map<String, String> options = Map.of("maxMessageSize", "0");
            assertThrows(IllegalArgumentException.class, () -> this.codec(context, options));
        }
    }

    @Test
    public void emptyJsonDocumentDoesNotBecomeAnImplicitNullArgument() throws Exception {
        // HTTP 正文完全为空，没有提供任何 JSON token。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.input("echo", "application/json", "", 1, Map.of());
            RequestPayload request = codec.open(input);

            // 缺失文档必须拒绝，不能替业务方法创建一个 null 参数。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void explicitJsonNullRemainsACompleteReferenceArgument() throws Exception {
        // 明确发送的 null 本身是完整 JSON 文档，不等同于没有正文。
        try (MetContext context = this.configuration().buildContext()) {
            this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.input("echo", "application/json", "null", 1, Map.of());
            RequestPayload request = codec.open(input);

            // 单个引用参数保留调用者显式选择的空值。
            codec.readRequest(request, input);
            assertEquals(1, request.getParameterValues().size());
            assertNull(request.getParameterValues().get(0));
        }
    }

    @Test
    public void wrapperDeadlineStatusRetainsItsEncodedCauseAndMetadata() throws Exception {
        // 官方 Dubbo 使用 tri-exception-code 承载协议状态，异常对象仍放在 wrapper 正文。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            TripleValueCodec values = new TripleValueCodec(65536, this.getClass().getClassLoader());
            byte[] wrapper = values.encodeResult(new IllegalStateException("deadline exceeded"), String.class);
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/grpc+proto"), //
                    "tri-exception-code", List.of("4"), //
                    "x-label", List.of("first", "second"));
            Map<String, List<String>> trailers = Map.of( //
                    "grpc-status", List.of("0"), //
                    "trace-bin", List.of("AQID"));
            byte[] frame = codec.frame(wrapper, "identity");
            HttpResponse input = HttpResponse.newBuffered(200, headers, frame, trailers);

            // 返回明确的协议状态，同时保留对端异常与全部应用元数据。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(4, failure.getCode());
            assertEquals("deadline exceeded", failure.getDescription());
            assertEquals(IllegalStateException.class, failure.getCause().getClass());
            assertEquals("deadline exceeded", failure.getCause().getMessage());
            assertEquals(List.of("first", "second"), failure.getMetadata().getHeader("x-label"));
            assertArrayEquals(new byte[] { 1, 2, 3 }, failure.getMetadata().getBinaryTrailer("trace-bin").get(0));
        }
    }

    @Test
    public void wrapperPermissionStatusRetainsItsEncodedCauseAndMetadata() throws Exception {
        // 官方 Dubbo 使用 tri-exception-code 承载协议状态，异常对象仍放在 wrapper 正文。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            TripleValueCodec values = new TripleValueCodec(65536, this.getClass().getClassLoader());
            byte[] wrapper = values.encodeResult(new IllegalStateException("permission denied"), String.class);
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/grpc+proto"), //
                    "tri-exception-code", List.of("7"), //
                    "x-label", List.of("first", "second"));
            Map<String, List<String>> trailers = Map.of( //
                    "grpc-status", List.of("0"), //
                    "trace-bin", List.of("AQID"));
            byte[] frame = codec.frame(wrapper, "identity");
            HttpResponse input = HttpResponse.newBuffered(200, headers, frame, trailers);

            // 返回明确的协议状态，同时保留对端异常与全部应用元数据。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(7, failure.getCode());
            assertEquals("permission denied", failure.getDescription());
            assertEquals(IllegalStateException.class, failure.getCause().getClass());
            assertEquals("permission denied", failure.getCause().getMessage());
            assertEquals(List.of("first", "second"), failure.getMetadata().getHeader("x-label"));
            assertArrayEquals(new byte[] { 1, 2, 3 }, failure.getMetadata().getBinaryTrailer("trace-bin").get(0));
        }
    }

    @Test
    public void officialHttpRequestTimeoutRetainsDeadlineWithoutDubboDependency() throws Exception {
        // 显式屏蔽 SDK，保留未安装 Dubbo 时识别协议期限异常的条件。
        ClassLoader withoutDubbo = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("org.apache.dubbo.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        String type = "org.apache.dubbo.remoting.http12.exception.HttpRequestTimeout";
        assertThrows(ClassNotFoundException.class, () -> withoutDubbo.loadClass(type));

        // 用最小的正式 Hessian 类定义表达官方异常，不携带对端实现或长堆栈样本。
        try (MetContext context = this.configuration().buildContext(withoutDubbo)) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            ByteArrayOutputStream valueBytes = new ByteArrayOutputStream();
            Hessian2Output hessian = new Hessian2Output(valueBytes);
            hessian.writeObjectBegin(type);
            hessian.writeClassFieldLength(2);
            hessian.writeString("detailMessage");
            hessian.writeString("statusCode");
            hessian.writeObjectBegin(type);
            hessian.writeString("remote deadline");
            hessian.writeInt(504);
            hessian.writeObjectEnd();
            hessian.flush();
            byte[] wrapper = this.exceptionWrapper(valueBytes.toByteArray());
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/grpc+proto"), //
                    "tri-exception-code", List.of("3"), //
                    "x-label", List.of("first", "second"));
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = HttpResponse.newBuffered(200, headers, codec.frame(wrapper, "identity"), trailers);

            // 类型别名恢复期限语义，元数据和解码后的 cause 均保留。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(4, failure.getCode());
            assertEquals("remote deadline", failure.getDescription());
            assertEquals(TimeoutException.class, failure.getCause().getClass());
            assertEquals(List.of("first", "second"), failure.getMetadata().getHeader("x-label"));
        }
    }

    @Test
    public void businessExceptionTimeoutTextDoesNotBecomeProtocolDeadline() throws Exception {
        // 普通业务异常的消息文本不能参与协议错误分类。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<TripleDemoService> service = this.publishPojo(context, "Hessian2");
            TripleHttpCodec codec = this.codec(context, Map.of());
            RequestPayload request = this.payload(service, "greet");
            request.addParameter(String.class.getName(), "hello");
            request.addParameter("int", 1);
            TripleValueCodec values = new TripleValueCodec(65536, this.getClass().getClassLoader());
            IllegalArgumentException business = new IllegalArgumentException("timeout in business data");
            byte[] wrapper = values.encodeResult(business, String.class);
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/grpc+proto"), //
                    "tri-exception-code", List.of("3"));
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = HttpResponse.newBuffered(200, headers, codec.frame(wrapper, "identity"), trailers);

            // 业务异常仍按原始 Java 类型交付。
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                    () -> codec.decode(request, input));
            assertEquals("timeout in business data", failure.getMessage());
        }
    }

    private byte[] exceptionWrapper(byte[] value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        output.writeString(1, "hessian4");
        output.writeByteArray(2, value);
        output.writeString(3, String.class.getName());
        output.flush();
        return bytes.toByteArray();
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http").option("httpVersion", 2).protocol("triple").contextPath("/rpc");
        return configuration;
    }

    private MetBindInfo<TripleDemoService> publishPojo(MetContext context, String coding) {
        return context.publisher().rsfService(TripleDemoService.class, new TripleDemoServiceImpl()) //
                .aliasName("triple", "example.Pojo").serialize(coding).register();
    }

    private TripleHttpCodec codec(MetContext context, Map<String, String> custom) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("contextPath", "/rpc");
        options.putAll(custom);
        ProtocolConfig config = ProtocolConfig.newConfig("triple", "triple", options);
        String endpointName = context.getSettings().getConnectorConfigs().iterator().next().name();
        return new TripleHttpCodec(config, new RouteMountIndex(endpointName, config), context);
    }

    private HttpRequest input(String method, String contentType, String body, //
            int version, Map<String, List<String>> custom) {
        Map<String, List<String>> headers = new LinkedHashMap<>(custom);
        headers.put("content-type", List.of(contentType));
        String path = "/rpc/example.Pojo/" + method;
        byte[] json = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuffered("POST", path, headers, json, version, Map.of());
    }

    private HttpRequest framed(String method, byte[] body, Map<String, List<String>> custom) {
        Map<String, List<String>> headers = new LinkedHashMap<>(custom);
        headers.put("content-type", List.of("application/grpc+proto"));
        return HttpRequest.newBuffered("POST", "/rpc/example.Pojo/" + method, headers, body, 2, Map.of());
    }

    private RequestPayload payload(MetBindInfo<?> service, String method) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method);
        request.setSerializeType(service.getSerializeType());
        request.setClientTimeout(5000);
        return request;
    }

    private ResponsePayload success(Object value) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    private byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(body);
        }
        return bytes.toByteArray();
    }
}
