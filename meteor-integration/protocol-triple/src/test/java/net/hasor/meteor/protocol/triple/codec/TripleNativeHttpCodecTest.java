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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import net.hasor.cobble.StringUtils;
import net.hasor.example.protocol.triple.TripleFlowService;
import net.hasor.example.protocol.triple.TripleFlowServiceImpl;
import net.hasor.example.protocol.triple.TripleMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import net.hasor.meteor.protocol.grpc.GrpcProtoSchema;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 已绑定 proto 的服务使用原生 Protobuf 或标准 ProtoJSON，不能套入 POJO wrapper。 */
public class TripleNativeHttpCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String ALIAS = "meteor.triple.demo.TripleFlowService";

    @Test
    public void plainProtobufRequestContainsBareMessage() throws Exception {
        // HTTP unary 模式按已绑定的输入消息描述编码普通 DTO。
        try (MetContext context = this.configuration("proto").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "proto");
            RequestPayload request = this.request(service, TripleMessage.of("request", 7));

            // 独立 protobuf 解析器读取裸消息，正文不携带 gRPC 帧。
            HttpRequest encoded = codec.encode(request);
            DynamicMessage message = DynamicMessage.parseFrom(this.method(resource).getInputType(), encoded.body());
            assertEquals("application/proto", encoded.header("content-type"));
            assertEquals(1, encoded.version());
            this.assertMessage(message, "request", 7);
        }
    }

    @Test
    public void plainProtobufRequestDecodesToDeclaredDto() throws Exception {
        // 使用独立 DynamicMessage 准备客户端的裸 Protobuf 请求。
        try (MetContext context = this.configuration("proto").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "proto");
            byte[] body = this.message(this.method(resource).getInputType(), "incoming", 6).toByteArray();
            HttpRequest input = this.input("application/proto", body, 1);

            // 参数类型取自发布接口，协议只负责完成描述消息与 DTO 的映射。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals("Protobuf", request.getSerializeType());
            this.assertDto(request.getParameterValues().get(0), "incoming", 6);
        }
    }

    @Test
    public void plainProtobufResponseContainsBareMessage() throws Exception {
        // 成功响应遵循 proto 的返回消息描述，HTTP 正文没有额外包装。
        try (MetContext context = this.configuration("proto").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "proto");
            RequestPayload request = this.request(service, TripleMessage.of("in", 1));
            HttpResponse encoded = codec.encode(request, this.success(TripleMessage.of("out", 2)));

            // 用独立描述符解析结果，而非再次调用被测解码器。
            DynamicMessage message = DynamicMessage.parseFrom(this.method(resource).getOutputType(), encoded.body());
            assertEquals("application/proto", encoded.header("content-type"));
            assertTrue(encoded.trailers().isEmpty());
            this.assertMessage(message, "out", 2);
        }
    }

    @Test
    public void plainProtobufResponseDecodesToDeclaredDto() throws Exception {
        // 服务端以 proto 返回消息构造独立响应。
        try (MetContext context = this.configuration("proto").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "proto");
            RequestPayload request = this.request(service, TripleMessage.of("in", 1));
            byte[] body = this.message(this.method(resource).getOutputType(), "reply", 4).toByteArray();
            HttpResponse input = this.response("application/proto", body, Map.of());

            // 消费端恢复接口返回的 DTO。
            ResponsePayload decoded = codec.decode(request, input);
            this.assertDto(decoded.getReturnData(), "reply", 4);
        }
    }

    @Test
    public void grpcProtobufRequestContainsOneNativeFrame() throws Exception {
        // IDL 请求通过原有 Java 发布接口提供参数，再转换成原生 gRPC 消息。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("request", 7));
            HttpRequest encoded = codec.encode(request);

            // 独立拆解五字节帧头，确认正文不是 Hessian 参数包装。
            byte[] body = this.unframe(encoded.body());
            DynamicMessage message = DynamicMessage.parseFrom(this.method(resource).getInputType(), body);
            assertEquals("application/grpc+proto", encoded.header("content-type"));
            assertEquals(2, encoded.version());
            this.assertMessage(message, "request", 7);
        }
    }

    @Test
    public void grpcProtobufRequestDecodesToDeclaredDto() throws Exception {
        // 对端提交标准 gRPC 帧，其中放置原生 proto 输入消息。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "incoming", 6).toByteArray();
            HttpRequest input = this.input("application/grpc+proto", this.frame(value), 2);

            // 接收链路不将原生 proto 数据解释为 Triple wrapper。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertFalse(codec.wrapped(request));
            this.assertDto(request.getParameterValues().get(0), "incoming", 6);
        }
    }

    @Test
    public void grpcDataRequestUsesBoundProtobufMessage() throws Exception {
        // Python SDK 用 +data 标记标准 gRPC 帧，消息形状仍由已绑定的 proto 决定。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "python", 6).toByteArray();
            HttpRequest input = this.input("application/grpc+data", this.frame(value), 2);

            // 别名只选择原生 Protobuf 解码，不把正文当成 Hessian wrapper 或 JSON。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals("Protobuf", request.getSerializeType());
            assertFalse(codec.wrapped(request));
            this.assertDto(request.getParameterValues().get(0), "python", 6);
        }
    }

    @Test
    public void grpcDataRequestReturnsCanonicalProtobufMediaType() throws Exception {
        // 请求接受 SDK 别名，成功响应仍使用对端同样接受的标准 Protobuf 媒体类型。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "request", 1).toByteArray();
            HttpRequest input = this.input("application/grpc+data", this.frame(value), 2);
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);

            // 独立解帧和 Protobuf 解析确认响应没有引入额外包装。
            HttpResponse encoded = codec.encode(request, this.success(TripleMessage.of("reply", 2)));
            DynamicMessage message = DynamicMessage.parseFrom(this.method(resource).getOutputType(), //
                    this.unframe(encoded.body()));
            assertEquals("application/grpc+proto", encoded.header("content-type"));
            this.assertMessage(message, "reply", 2);
        }
    }

    @Test
    public void grpcDataRequestAcceptsAnEmptyMessageBeforeTheBetaTerminator() throws Exception {
        // 默认值请求本身也可为空消息，第二个空帧仅在完整正文末尾充当 Beta 结束标记。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            this.publish(context, "Protobuf", this.resource());
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] body = this.concatenate(this.frame(new byte[0]), this.frame(new byte[0]));
            HttpRequest input = this.input("application/grpc+data", body, 2);

            // 首条消息仍按正式 proto 映射，不能因为它为空而漏掉业务参数。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals(1, request.getParameterValues().size());
            TripleMessage message = (TripleMessage) request.getParameterValues().get(0);
            assertEquals("", message.getText());
            assertEquals(0, message.getNumber());
        }
    }

    @Test
    public void grpcDataRequestRejectsANonEmptySecondMessage() throws Exception {
        // +data 的兼容分支不接受第二条业务消息。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "first", 1).toByteArray();
            byte[] body = this.concatenate(this.frame(value), this.frame(value));
            HttpRequest input = this.input("application/grpc+data", body, 2);
            RequestPayload request = codec.open(input);

            // 原有 unary 消息数量约束仍报告 INTERNAL，且没有交付部分参数。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(13, failure.getCode());
            assertTrue(request.getParameterValues().isEmpty());
        }
    }

    @Test
    public void grpcDataRequestRejectsAThirdMessageAfterTheBetaTerminator() throws Exception {
        // 首业务帧后跟两个空帧，不能把多个空消息都当成结束标记。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "first", 1).toByteArray();
            byte[] body = this.concatenate(this.frame(value), this.frame(new byte[0]), this.frame(new byte[0]));
            HttpRequest input = this.input("application/grpc+data", body, 2);
            RequestPayload request = codec.open(input);

            // 只接受恰好一个末尾标记，不能截断完整正文。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcDataRequestRejectsACompressedEmptyTerminator() throws Exception {
        // 压缩位不为零的五字节空帧不是该 SDK 的结束标记。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "first", 1).toByteArray();
            byte[] body = this.concatenate(this.frame(value), new byte[] { 1, 0, 0, 0, 0 });
            Map<String, List<String>> headers = Map.of( //
                    "content-type", List.of("application/grpc+data"), "grpc-encoding", List.of("gzip"));
            HttpRequest input = HttpRequest.newBuffered("POST", "/rpc/" + ALIAS + "/echo", headers, body, 2, Map.of());
            RequestPayload request = codec.open(input);

            // 即使已经声明 gzip，也不将压缩空帧静默解释成 EOF。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcDataRequestRejectsAnEmptyMessageInTheMiddle() throws Exception {
        // 空帧后继续出现业务消息，说明该空帧并非正文末尾的结束标记。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "last", 1).toByteArray();
            byte[] body = this.concatenate(this.frame(value), this.frame(new byte[0]), this.frame(value));
            HttpRequest input = this.input("application/grpc+data", body, 2);
            RequestPayload request = codec.open(input);

            // 不能忽略任意位置的空帧，也不能只读取第一条业务消息。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcDataResponseRejectsTheRequestOnlyBetaTerminator() throws Exception {
        // Python Beta 的特例仅作用于请求，响应仍必须恰好包含一条业务消息。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("request", 1));
            byte[] value = this.message(this.method(resource).getOutputType(), "reply", 2).toByteArray();
            byte[] body = this.concatenate(this.frame(value), this.frame(new byte[0]));
            HttpResponse input = this.response("application/grpc+data", body, Map.of("grpc-status", List.of("0")));

            // 编码别名不放宽成功响应的消息数量约束。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcDataStreamingMethodRejectsTheUnaryBetaTerminator() throws Exception {
        // 带响应流的方法不能借用普通调用的 Beta 结束标记兼容分支。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            context.publisher().rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", ALIAS).serialize("Protobuf").bindResource("triple", resource).register();
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = this.message(this.method(resource).getInputType(), "count", 1).toByteArray();
            byte[] body = this.concatenate(this.frame(value), this.frame(new byte[0]));
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/grpc+data"));
            HttpRequest input = HttpRequest.newBuffered("POST", "/rpc/" + ALIAS + "/count", headers, body, 2, Map.of());
            RequestPayload request = codec.open(input);

            // 普通解码入口对流式签名同样保留严格消息数量，流读取器自身不作特殊处理。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcNativeRequestRejectsUnknownMediaSuffix() throws Exception {
        // 有 proto 绑定也不能接受未约定的任意 gRPC 消息编码。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            this.publish(context, "Protobuf", this.resource());
            TripleHttpCodec codec = this.codec(context, "grpc");
            HttpRequest input = this.input("application/grpc+unknown", this.frame(new byte[0]), 2);

            // 在正文解码前保留明确的 HTTP 415。
            TripleHttpStatusException failure = assertThrows(TripleHttpStatusException.class, //
                    () -> codec.open(input));
            assertEquals(415, failure.getHttpStatus());
        }
    }

    @Test
    public void grpcProtobufResponseContainsOneNativeFrame() throws Exception {
        // 正常响应使用描述符对应的输出消息以及 gRPC 成功 trailer。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("in", 1));
            HttpResponse encoded = codec.encode(request, this.success(TripleMessage.of("out", 2)));

            // 独立解帧后检查原生消息及结束状态。
            byte[] body = this.unframe(encoded.body());
            DynamicMessage message = DynamicMessage.parseFrom(this.method(resource).getOutputType(), body);
            assertEquals(List.of("0"), encoded.trailers().get("grpc-status"));
            this.assertMessage(message, "out", 2);
        }
    }

    @Test
    public void grpcProtobufResponseDecodesToDeclaredDto() throws Exception {
        // 使用独立 proto 编码和帧头准备正常的远端响应。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("in", 1));
            byte[] value = this.message(this.method(resource).getOutputType(), "reply", 4).toByteArray();
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = this.response("application/grpc+proto", this.frame(value), trailers);

            // 收到成功 trailer 后恢复唯一返回值。
            ResponsePayload decoded = codec.decode(request, input);
            this.assertDto(decoded.getReturnData(), "reply", 4);
        }
    }

    @Test
    public void grpcDataResponseDecodesBoundProtobufMessage() throws Exception {
        // 请求使用标准 Protobuf，Python SDK 成功响应使用相同帧和正文的 +data 别名。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("request", 1));
            byte[] value = this.message(this.method(resource).getOutputType(), "python", 4).toByteArray();
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = this.response("application/grpc+data", this.frame(value), trailers);

            // 消费端继续按原请求的 proto 输出描述恢复 DTO。
            ResponsePayload decoded = codec.decode(request, input);
            this.assertDto(decoded.getReturnData(), "python", 4);
        }
    }

    @Test
    public void grpcJsonRequestRejectsDataResponse() throws Exception {
        // +data 不是 JSON 的别名，不能改变调用方已选择的 ProtoJSON 编码。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            MetBindInfo<Unary> service = this.publish(context, "Json", this.resource());
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("request", 1));
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = this.response("application/grpc+data", this.frame(new byte[0]), trailers);

            // 编码不匹配仍返回协议错误，不能依赖正文恰好能被另一种解析器读取。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcProtobufRequestRejectsUnknownResponseMediaSuffix() throws Exception {
        // 已绑定 Protobuf 也只接受明确支持的响应媒体类型。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            MetBindInfo<Unary> service = this.publish(context, "Protobuf", this.resource());
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("request", 1));
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = this.response("application/grpc+unknown", this.frame(new byte[0]), trailers);

            // 未知 suffix 不能因为共享 application/grpc 前缀而被放行。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcJsonRequestContainsDirectProtoJsonObject() throws Exception {
        // Json 编码与绑定 proto 共存时使用标准 ProtoJSON，而非位置参数数组。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Json", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("json", 5));
            HttpRequest encoded = codec.encode(request);

            // 官方 ProtoJSON 解析器直接读取消息对象。
            byte[] body = this.unframe(encoded.body());
            DynamicMessage.Builder message = DynamicMessage.newBuilder(this.method(resource).getInputType());
            JsonFormat.parser().merge(new String(body, StandardCharsets.UTF_8), message);
            assertEquals("application/grpc+json", encoded.header("content-type"));
            this.assertMessage(message.build(), "json", 5);
        }
    }

    @Test
    public void grpcJsonRequestDecodesDirectProtoJsonObject() throws Exception {
        // 使用标准 JSON 对象构造 gRPC+json 对端请求。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Json", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = "{\"text\":\"incoming\",\"number\":6}".getBytes(StandardCharsets.UTF_8);
            HttpRequest input = this.input("application/grpc+json", this.frame(value), 2);

            // 直接 DTO 参数恢复后仍保持标准 Json 编码标识。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals("Json", request.getSerializeType());
            this.assertDto(request.getParameterValues().get(0), "incoming", 6);
        }
    }

    @Test
    public void grpcJsonResponseContainsDirectProtoJsonObject() throws Exception {
        // 返回值按 proto 输出描述生成标准 ProtoJSON。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Json", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("in", 1));
            HttpResponse encoded = codec.encode(request, this.success(TripleMessage.of("reply", 4)));

            // 官方 JSON 解析器确认返回正文是输出消息。
            byte[] body = this.unframe(encoded.body());
            DynamicMessage.Builder message = DynamicMessage.newBuilder(this.method(resource).getOutputType());
            JsonFormat.parser().merge(new String(body, StandardCharsets.UTF_8), message);
            assertEquals("application/grpc+json", encoded.header("content-type"));
            this.assertMessage(message.build(), "reply", 4);
        }
    }

    @Test
    public void grpcJsonResponseDecodesToDeclaredDto() throws Exception {
        // 用独立 JSON 正文和成功 trailer 准备响应。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Json", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("in", 1));
            byte[] value = "{\"text\":\"reply\",\"number\":4}".getBytes(StandardCharsets.UTF_8);
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = this.response("application/grpc+json", this.frame(value), trailers);

            // 通过接口已声明的 DTO 类型消费 ProtoJSON。
            ResponsePayload decoded = codec.decode(request, input);
            this.assertDto(decoded.getReturnData(), "reply", 4);
        }
    }

    @Test
    public void plainJsonWithProtoUsesOrderedSingleArgumentArray() throws Exception {
        // HTTP JSON RPC 的请求格式是参数数组，数组内的对象仍遵循 ProtoJSON。
        try (MetContext context = this.configuration("json").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<Unary> service = this.publish(context, "Json", resource);
            TripleHttpCodec codec = this.codec(context, "json");
            RequestPayload request = this.request(service, TripleMessage.of("json", 5));
            HttpRequest encoded = codec.encode(request);

            // 参数数组只有一个原生消息，没有 Hessian 包装字段。
            JsonNode body = JSON.readTree(encoded.body());
            assertTrue(body.isArray());
            assertEquals(1, body.size());
            assertEquals("json", body.get(0).get("text").asText());
            assertEquals(5, body.get(0).get("number").asInt());
        }
    }

    @Test
    public void plainJsonWithProtoDecodesOrderedSingleArgumentArray() throws Exception {
        // 原生描述消息在普通 HTTP JSON 请求中使用单元素参数数组。
        try (MetContext context = this.configuration("json").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Json", resource);
            TripleHttpCodec codec = this.codec(context, "json");
            byte[] body = "[{\"text\":\"incoming\",\"number\":6}]".getBytes(StandardCharsets.UTF_8);
            HttpRequest input = this.input("application/json", body, 1);

            // 解码阶段先处理参数位置，再按 proto 校验消息字段。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            this.assertDto(request.getParameterValues().get(0), "incoming", 6);
        }
    }

    @Test
    public void protobufWithoutBoundProtoFileRemainsLocal() throws Exception {
        // 缺少 proto 的服务仍可本地使用，Triple 不暴露无法编码的端点。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            MetBindInfo<Unary> service = context.publisher().rsfService(Unary.class, request -> request) //
                    .aliasName("triple", ALIAS).serialize("Protobuf").register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            assertNull(MetBindInfoWrap.unwrap(service).getProtocolMeta("triple"));
        }
    }

    @Test
    public void methodMissingFromBoundServiceIsNotExposed() throws Exception {
        // Java 方法必须能在绑定的 proto 服务描述中找到。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<MissingMethod> service = context.publisher() //
                    .rsfService(MissingMethod.class, request -> request) //
                    .aliasName("triple", ALIAS) //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            ProtocolMeta declaration = MetBindInfoWrap.unwrap(service).getProtocolMeta("triple");
            assertSame(resource, declaration.getBindResource());
            assertTrue(declaration.getEndpoints().isEmpty());
        }
    }

    @Test
    public void flowShapeDifferentFromProtoDescriptorIsNotExposed() throws Exception {
        // count 在 proto 中返回流，普通单返回值签名不能假装匹配它。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<WrongFlowShape> service = context.publisher() //
                    .rsfService(WrongFlowShape.class, request -> request) //
                    .aliasName("triple", ALIAS) //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            ProtocolMeta declaration = MetBindInfoWrap.unwrap(service).getProtocolMeta("triple");
            assertSame(resource, declaration.getBindResource());
            assertTrue(declaration.getEndpoints().isEmpty());
        }
    }

    @Test
    public void plainHttpDoesNotExposeStreamingMethods() throws Exception {
        // HTTP unary 模式只发布普通调用接口，不能静默接收流式方法。
        try (MetContext context = this.configuration("proto").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<TripleFlowService> service = context.publisher() //
                    .rsfService(TripleFlowService.class, new TripleFlowServiceImpl()) //
                    .aliasName("triple", ALIAS) //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            ProtocolMeta declaration = MetBindInfoWrap.unwrap(service).getProtocolMeta("triple");
            assertSame(resource, declaration.getBindResource());
            assertTrue(declaration.getEndpoints().isEmpty());
        }
    }

    @Test
    public void grpcJsonRejectsFieldsAbsentFromProtoDescriptor() throws Exception {
        // 即使 Java Bean 可忽略某些字段，ProtoJSON 仍应按绑定描述拒绝未知字段。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            this.publish(context, "Json", resource);
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = "{\"text\":\"incoming\",\"unknown\":true}".getBytes(StandardCharsets.UTF_8);
            HttpRequest input = this.input("application/grpc+json", this.frame(value), 2);
            RequestPayload request = codec.open(input);
            assertThrows(IOException.class, () -> codec.readRequest(request, input));
        }
    }

    @Test
    public void rejectsNativeMessageWithDifferentProtoDescriptor() throws Exception {
        // 不同 proto 消息可能具有相同字段布局，仍不能冒充已发布的输入消息。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            GrpcProtoResource resource = this.resource();
            MetBindInfo<MessageUnary> service = context.publisher() //
                    .rsfService(MessageUnary.class, request -> request) //
                    .aliasName("triple", ALIAS) //
                    .serialize("Protobuf") //
                    .bindResource("triple", resource) //
                    .register();
            TripleHttpCodec codec = this.codec(context, "grpc");
            Message wrongType = this.message(this.method(resource).getOutputType(), "wrong descriptor", 7);
            RequestPayload request = this.request(service, wrongType);
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.encode(request));
            assertEquals(3, failure.getCode());
        }
    }

    @Test
    public void grpcProtoJsonRejectsMalformedUtf8BeforeDescriptorConversion() throws Exception {
        // 字符串中包含不合法 UTF-8，不能先转换成替代字符再交给 ProtoJSON。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            this.publish(context, "Json", this.resource());
            TripleHttpCodec codec = this.codec(context, "grpc");
            byte[] value = { '{', '"', 't', 'e', 'x', 't', '"', ':', '"', (byte) 0xc3, '(', '"', '}' };
            HttpRequest input = this.input("application/grpc+json", this.frame(value), 2);
            RequestPayload request = codec.open(input);

            // 原始字节必须严格解码，禁止让损坏的消息进入业务参数。
            assertThrows(IOException.class, () -> codec.readRequest(request, input));
        }
    }

    @Test
    public void grpcProtoJsonRejectsAnEmptyRequestDocument() throws Exception {
        // 五字节帧可以声明零字节正文，但 ProtoJSON 仍必须提供一个文档。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            this.publish(context, "Json", this.resource());
            TripleHttpCodec codec = this.codec(context, "grpc");
            HttpRequest input = this.input("application/grpc+json", this.frame(new byte[0]), 2);
            RequestPayload request = codec.open(input);

            // 空文档与字段全部使用默认值的合法空对象具有不同含义。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, //
                    () -> codec.readRequest(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    @Test
    public void grpcProtoJsonRejectsAnEmptyResponseDocument() throws Exception {
        // 成功状态不能让缺少 JSON 文档的响应自动成为默认返回 DTO。
        try (MetContext context = this.configuration("grpc").buildContext()) {
            MetBindInfo<Unary> service = this.publish(context, "Json", this.resource());
            TripleHttpCodec codec = this.codec(context, "grpc");
            RequestPayload request = this.request(service, TripleMessage.of("request", 1));
            Map<String, List<String>> trailers = Map.of("grpc-status", List.of("0"));
            HttpResponse input = this.response("application/grpc+json", this.frame(new byte[0]), trailers);

            // 返回方向同样要求完整 JSON 文档。
            GrpcStatusException failure = assertThrows(GrpcStatusException.class, () -> codec.decode(request, input));
            assertEquals(13, failure.getCode());
        }
    }

    private MetConfiguration configuration(String mode) {
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("triple");
        configuration.connector("http") //
                .option("httpVersion", StringUtils.equals("grpc", mode) ? 2 : 1) //
                .protocol("triple") //
                .contextPath("/rpc") //
                .option("httpMode", mode);
        return configuration;
    }

    private TripleHttpCodec codec(MetContext context, String mode) {
        ProtocolConfig configuration = ProtocolConfig.newConfig("triple", "triple", //
                Map.of("contextPath", "/rpc", "httpMode", mode));
        String endpointName = context.getSettings().getConnectorConfigs().iterator().next().name();
        return new TripleHttpCodec(configuration, new RouteMountIndex(endpointName, configuration), context);
    }

    private MetBindInfo<Unary> publish(MetContext context, String coding, GrpcProtoResource resource) {
        return context.publisher() //
                .rsfService(Unary.class, request -> request) //
                .aliasName("triple", ALIAS) //
                .serialize(coding) //
                .bindResource("triple", resource) //
                .register();
    }

    private GrpcProtoResource resource() throws Exception {
        return new GrpcProtoResource(this.getClass().getResource("/proto/triple-demo.proto"));
    }

    private MethodDescriptor method(GrpcProtoResource resource) {
        return GrpcProtoSchema.fromResource(resource).method(ALIAS, "echo");
    }

    private DynamicMessage message(Descriptor descriptor, String text, int number) {
        return DynamicMessage.newBuilder(descriptor) //
                .setField(descriptor.findFieldByName("text"), text) //
                .setField(descriptor.findFieldByName("number"), number) //
                .build();
    }

    private RequestPayload request(MetBindInfo<?> service, Object value) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod("echo");
        request.setSerializeType(service.getSerializeType());
        request.setClientTimeout(5000);
        Class<?> parameter = service.getBindType().getMethods()[0].getParameterTypes()[0];
        request.addParameter(parameter.getName(), value);
        return request;
    }

    private HttpRequest input(String contentType, byte[] body, int version) {
        Map<String, List<String>> headers = Map.of("content-type", List.of(contentType));
        return HttpRequest.newBuffered("POST", "/rpc/" + ALIAS + "/echo", headers, body, version, Map.of());
    }

    private HttpResponse response(String contentType, byte[] body, Map<String, List<String>> trailers) {
        return HttpResponse.newBuffered(200, Map.of("content-type", List.of(contentType)), body, trailers);
    }

    private ResponsePayload success(TripleMessage value) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    private byte[] frame(byte[] body) {
        return ByteBuffer.allocate(body.length + 5).put((byte) 0).putInt(body.length).put(body).array();
    }

    private byte[] concatenate(byte[]... frames) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            body.writeBytes(frame);
        }
        return body.toByteArray();
    }

    private byte[] unframe(byte[] body) {
        ByteBuffer input = ByteBuffer.wrap(body);
        assertEquals(0, input.get());
        int length = input.getInt();
        assertEquals(input.remaining(), length);
        byte[] value = new byte[length];
        input.get(value);
        return value;
    }

    private void assertMessage(DynamicMessage message, String text, int number) {
        Descriptor descriptor = message.getDescriptorForType();
        assertEquals(text, message.getField(descriptor.findFieldByName("text")));
        assertEquals(number, message.getField(descriptor.findFieldByName("number")));
    }

    private void assertDto(Object value, String text, int number) {
        assertEquals(TripleMessage.class, value.getClass());
        TripleMessage message = (TripleMessage) value;
        assertEquals(text, message.getText());
        assertEquals(number, message.getNumber());
    }

    public interface Unary {
        TripleMessage echo(TripleMessage request);
    }

    public interface MissingMethod {
        TripleMessage absent(TripleMessage request);
    }

    public interface WrongFlowShape {
        TripleMessage count(TripleMessage request);
    }

    public interface MessageUnary {
        Message echo(Message request);
    }
}
