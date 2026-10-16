/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.hasor.example.protocol.connect.ConnectDemoService;
import net.hasor.example.protocol.connect.ConnectDemoServiceImpl;
import net.hasor.example.protocol.connect.ConnectMessage;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetTimeoutException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.connect.ConnectMetadata;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectHttpCodecTest {
    @Test
    public void unaryPostReadsAnUnframedJsonMessage() throws Exception {
        // 发布消息服务，准备没有五字节帧头的普通 JSON 正文。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.post("echo", "application/json", "{\"text\":\"中文\",\"number\":7}");

            // 普通调用直接读取业务对象。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            ConnectMessage message = (ConnectMessage) request.getParameterValues().get(0);
            assertEquals("中文", message.getText());
            assertEquals(7, message.getNumber());
        }
    }

    @Test
    public void acceptsTheOptionalProtocolVersionHeader() throws Exception {
        // 正常版本号不改变普通调用的参数映射。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "connect-protocol-version", List.of("1"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertEquals("echo", this.codec(context, Map.of()).open(input).getTargetMethod());
        }
    }

    @Test
    public void rejectsAnUnknownProtocolVersion() throws Exception {
        // 版本号存在时，不能将未来版本当作 v1 解码。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "connect-protocol-version", List.of("2"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void rejectsAnUnsupportedHttpMethod() throws Exception {
        // PUT 不属于 Connect 调用入口。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.request("PUT", "echo", Map.of(), "{}");
            MetException failure = assertThrows(MetException.class, () -> this.codec(context, Map.of()).open(input));
            assertEquals(405, this.codec(context, Map.of()).error(failure).status());
        }
    }

    @Test
    public void rejectsAnUnsupportedContentType() throws Exception {
        // 普通调用不能使用任意媒体类型。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.post("echo", "text/plain", "{}");
            MetException failure = assertThrows(MetException.class, () -> this.codec(context, Map.of()).open(input));
            assertEquals(415, this.codec(context, Map.of()).error(failure).status());
        }
    }

    @Test
    public void rejectsAnUnknownService() throws Exception {
        // URL 中的服务名必须是当前挂载已发布的别名。
        try (MetContext context = this.configuration().buildContext()) {
            ConnectHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.post("echo", "application/json", "{}");
            assertThrows(UnsupportedOperationException.class, () -> codec.open(input));
        }
    }

    @Test
    public void rejectsAnUnknownMethod() throws Exception {
        // 服务存在，但方法未在接口中声明。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.post("missing", "application/json", "{}");
            assertThrows(UnsupportedOperationException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void rejectsAPathWithAnExtraSegment() throws Exception {
        // 方法路径不能多出第三层业务段。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.post("echo/extra", "application/json", "{}");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void parsesTimeoutAsMilliseconds() throws Exception {
        // Connect 超时头直接表示毫秒，不使用 gRPC 的单位后缀。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "connect-timeout-ms", List.of("1001"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertEquals(1001, this.codec(context, Map.of()).open(input).getClientTimeout());
        }
    }

    @Test
    public void rejectsTimeoutWithAUnitSuffix() throws Exception {
        // 超时头只接受十进制数字。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "connect-timeout-ms", List.of("10m"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void rejectsTimeoutWithMoreThanTenDigits() throws Exception {
        // 超出协议位数上限的超时不能溢出为负数。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "connect-timeout-ms", List.of("12345678901"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void readsRepeatedRequestMetadata() throws Exception {
        // 普通选项保留首值，完整元数据保留同名多值。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "authorization", List.of("Bearer first", "Bearer second"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");

            // 多值不会被单值兼容选项覆盖。
            RequestPayload request = this.codec(context, Map.of()).open(input);
            assertEquals("Bearer first", request.getOption("authorization"));
            List<String> authorization = ConnectMetadata.from(request).getHeader("authorization");
            assertEquals(List.of("Bearer first", "Bearer second"), authorization);
        }
    }

    @Test
    public void requestGzipIsDecodedBeforeDtoMapping() throws Exception {
        // HTTP Content-Encoding 控制普通请求的整段正文压缩。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            byte[] body = this.gzip("{\"text\":\"compressed\",\"number\":9}");
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "content-encoding", List.of("gzip"));
            HttpRequest input = HttpRequest.newBuffered("POST", this.path("echo"), headers, body, 1, Map.of());

            // 业务层取得解压后的消息。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            ConnectMessage message = (ConnectMessage) request.getParameterValues().get(0);
            assertEquals("compressed", message.getText());
        }
    }

    @Test
    public void rejectsAnUnsupportedRequestCompression() throws Exception {
        // 未实现的压缩不能回退为明文读取。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "content-encoding", List.of("br"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertThrows(ConnectStatusException.class, () -> {
                RequestPayload request = codec.open(input);
                codec.readRequest(request, input);
            });
        }
    }

    @Test
    public void rejectsMalformedGzipContent() throws Exception {
        // 声明 gzip，但输入不是压缩数据。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "content-encoding", List.of("gzip"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertThrows(IOException.class, () -> {
                RequestPayload request = codec.open(input);
                codec.readRequest(request, input);
            });
        }
    }

    @Test
    public void appliesTheLimitAfterDecompression() throws Exception {
        // 压缩正文很小，解压后超过配置限制。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of("maxMessageSize", "64"));
            byte[] body = this.gzip("{\"text\":\"" + "x".repeat(1000) + "\"}");
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "content-encoding", List.of("gzip"));
            HttpRequest input = HttpRequest.newBuffered("POST", this.path("echo"), headers, body, 1, Map.of());

            // 大小限制针对业务内容，而不是仅针对压缩包。
            ConnectStatusException failure = assertThrows(ConnectStatusException.class, () -> {
                RequestPayload request = codec.open(input);
                codec.readRequest(request, input);
            });
            assertEquals("resource_exhausted", failure.getCode());
        }
    }

    @Test
    public void unaryGetReadsPercentEncodedJson() throws Exception {
        // GET 仅调用 proto 明确声明为无副作用的方法。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            String json = URLEncoder.encode("{\"text\":\"a+b 中文\",\"number\":4}", StandardCharsets.UTF_8);
            String path = this.path("safeEcho") + "?encoding=json&message=" + json + "&connect=v1";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());

            // URL 解码不能破坏业务字符。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            ConnectMessage message = (ConnectMessage) request.getParameterValues().get(0);
            assertEquals("a+b 中文", message.getText());
        }
    }

    @Test
    public void unaryGetAcceptsUrlSafeBase64() throws Exception {
        // URL 安全 Base64 表示 GET 请求中的消息字节。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            byte[] body = "{\"text\":\"base64\"}".getBytes(StandardCharsets.UTF_8);
            String message = Base64.getUrlEncoder().withoutPadding().encodeToString(body);
            String path = this.path("safeEcho") + "?base64=1&encoding=json&message=" + message;
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());

            // base64 查询参数生效后再执行消息映射。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals("base64", ((ConnectMessage) request.getParameterValues().get(0)).getText());
        }
    }

    @Test
    public void unaryGetSupportsUnknownQueryParameters() throws Exception {
        // 普通查询参数可与 Connect 必需参数共同出现。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("safeEcho") + "?debug=1&message=%7B%7D&encoding=json";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            RequestPayload request = this.codec(context, Map.of()).open(input);
            assertEquals("safeEcho", request.getTargetMethod());
        }
    }

    @Test
    public void unaryGetRejectsAProcedureWithSideEffects() throws Exception {
        // 未声明 NO_SIDE_EFFECTS 的 echo 不能经 GET 访问。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("echo") + "?encoding=json&message=%7B%7D";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            ConnectHttpCodec codec = this.codec(context, Map.of());
            MetException failure = assertThrows(MetException.class, () -> codec.open(input));
            assertEquals(405, codec.error(failure).status());
        }
    }

    @Test
    public void unaryGetRejectsAnUnknownProtocolVersion() throws Exception {
        // GET 版本参数存在时必须为 v1。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("safeEcho") + "?connect=v2&encoding=json&message=%7B%7D";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void unaryGetRejectsAMissingMessage() throws Exception {
        // 缺少 message 不能被视为默认消息。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("safeEcho") + "?encoding=json";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            assertThrows(ConnectStatusException.class, () -> {
                ConnectHttpCodec codec = this.codec(context, Map.of());
                codec.readRequest(codec.open(input), input);
            });
        }
    }

    @Test
    public void unaryGetRejectsDuplicateMessageParameters() throws Exception {
        // 同一消息有两个值时不任意选择其中一个。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("safeEcho") + "?encoding=json&message=%7B%7D&message=%7B%7D";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            assertThrows(ConnectStatusException.class, () -> {
                ConnectHttpCodec codec = this.codec(context, Map.of());
                codec.readRequest(codec.open(input), input);
            });
        }
    }

    @Test
    public void outboundUnaryJsonUsesABareBody() throws Exception {
        // 通过协议编码输出普通 JSON 请求。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            RequestPayload request = this.payload(service, "echo");
            request.addParameter(ConnectMessage.class.getName(), ConnectMessage.of("outbound", 8));

            // 不增加 gRPC 或流式消息的帧头。
            HttpRequest encoded = this.codec(context, Map.of()).encode(request);
            assertEquals("POST", encoded.method());
            assertEquals("application/json", encoded.header("content-type"));
            assertEquals(this.path("echo"), encoded.uri());
            String body = new String(encoded.body(), StandardCharsets.UTF_8);
            assertTrue(body.startsWith("{"));
            assertTrue(body.contains("outbound"));
        }
    }

    @Test
    public void outboundUnaryGetUsesTheSafeMethodPath() throws Exception {
        // 显式开启 GET，仅输出 proto 中声明为安全的方法。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            RequestPayload request = this.payload(service, "safeEcho");
            request.addParameter(ConnectMessage.class.getName(), ConnectMessage.of("query", 2));

            // 查询正文进入 URL，HTTP 正文保持为空。
            HttpRequest encoded = this.codec(context, Map.of("get", "true")).encode(request);
            assertEquals("GET", encoded.method());
            assertTrue(encoded.uri().startsWith(this.path("safeEcho") + "?"));
            assertTrue(encoded.uri().contains("encoding=json"));
            assertEquals(0, encoded.body().length);
        }
    }

    @Test
    public void outboundUnaryRejectsMessageMode() throws Exception {
        // Connect 不支持没有响应生命周期的 notification。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            RequestPayload request = this.payload(service, "echo");
            request.setMessage(true);
            request.addParameter(ConnectMessage.class.getName(), ConnectMessage.of("notification", 1));
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).encode(request));
        }
    }

    @Test
    public void outboundUnaryRejectsAMissingArgument() throws Exception {
        // 普通调用必须直接提供一个请求对象。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            RequestPayload request = this.payload(service, "echo");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).encode(request));
        }
    }

    @Test
    public void unaryResponseUsesTheRequestMediaType() throws Exception {
        // 成功响应与请求使用同一消息编码。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            RequestPayload request = this.payload(service, "echo");
            ResponsePayload result = this.response(ConnectMessage.of("reply", 6));

            // 响应正文仍是没有帧头的 JSON。
            HttpResponse response = this.codec(context, Map.of()).encode(request, result);
            assertEquals(200, response.status());
            assertEquals("application/json", response.header("content-type"));
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("reply"));
        }
    }

    @Test
    public void unaryResponsePrefixesTrailingMetadata() throws Exception {
        // 普通响应把尾部字段放入 trailer- 前缀的 HTTP 头。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            ResponsePayload result = this.response(ConnectMessage.of("reply", 6));
            ConnectMetadata.from(result).addTrailer("operation-cost", "7");
            RequestPayload request = this.payload(service, "echo");
            HttpResponse response = this.codec(context, Map.of()).encode(request, result);
            assertEquals("7", response.header("trailer-operation-cost"));
            assertTrue(response.trailers().isEmpty());
        }
    }

    @Test
    public void unaryResponseDecodingSeparatesTrailingMetadata() throws Exception {
        // 接收响应时恢复 trailer- 前缀对应的尾部字段。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "x-region", List.of("east", "west"), "trailer-operation-cost", List.of("7", "8"));
            byte[] body = "{\"text\":\"reply\",\"number\":2}".getBytes(StandardCharsets.UTF_8);
            HttpResponse input = HttpResponse.newBuffered(200, headers, body, Map.of());

            // 头部与尾部各自保留多值，不混到同一命名空间。
            ResponsePayload response = this.codec(context, Map.of()).decode(this.payload(service, "echo"), input);
            assertEquals(List.of("east", "west"), ConnectMetadata.from(response).getHeader("x-region"));
            assertEquals(List.of("7", "8"), ConnectMetadata.from(response).getTrailer("operation-cost"));
        }
    }

    @Test
    public void unaryResponseFallsBackToIdentityWhenGzipIsNotAccepted() throws Exception {
        // 本地允许 gzip，但对端没有接受任何压缩。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            RequestPayload request = this.payload(service, "echo");
            request.addOption("connect-accept-encoding", "identity");
            HttpResponse response = this.codec(context, Map.of("compression", "gzip")) //
                    .encode(request, this.response(ConnectMessage.of("reply", 1)));
            String contentEncoding = response.header("content-encoding");
            assertTrue(contentEncoding == null || contentEncoding.isEmpty() || contentEncoding.contains("identity"));
        }
    }

    @Test
    public void remoteErrorRetainsUnicodeAndDetails() throws Exception {
        // 错误正文是 UTF-8 JSON，不采用 gRPC 消息百分号编码。
        try (MetContext context = this.configuration().buildContext()) {
            String json = "{\"code\":\"permission_denied\",\"message\":\"拒绝 + 调用\"," //
                    + "\"details\":[{\"type\":\"example.Reason\",\"value\":\"AQ\"}]}";
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"));
            HttpResponse input = HttpResponse.newBuffered(403, headers, body, Map.of());
            ConnectStatusException failure = assertThrows(ConnectStatusException.class, //
                    () -> this.codec(context, Map.of()).decode(new RequestPayload(), input));
            assertEquals("permission_denied", failure.getCode());
            assertEquals("拒绝 + 调用", failure.getDescription());
            assertEquals("example.Reason", failure.getDetails().get(0).get("type"));
        }
    }

    @Test
    public void malformedErrorFallsBackToTheHttpStatus() throws Exception {
        // HTTP 503 正文损坏时仍按 Connect 回退表报告 unavailable。
        try (MetContext context = this.configuration().buildContext()) {
            HttpResponse input = HttpResponse.newBuffered(503, Map.of(), new byte[] { '[' }, Map.of());
            ConnectStatusException failure = assertThrows(ConnectStatusException.class, //
                    () -> this.codec(context, Map.of()).decode(new RequestPayload(), input));
            assertEquals("unavailable", failure.getCode());
        }
    }

    @Test
    public void missingErrorBodyFallsBackToTheHttpStatus() throws Exception {
        // HTTP 404 无正文时报告 unimplemented。
        try (MetContext context = this.configuration().buildContext()) {
            HttpResponse input = HttpResponse.newBuffered(404, Map.of(), new byte[0], Map.of());
            ConnectStatusException failure = assertThrows(ConnectStatusException.class, //
                    () -> this.codec(context, Map.of()).decode(new RequestPayload(), input));
            assertEquals("unimplemented", failure.getCode());
        }
    }

    @Test
    public void localInvalidArgumentUsesHttp400() throws Exception {
        // 参数错误输出标准 Connect JSON 错误正文。
        try (MetContext context = this.configuration().buildContext()) {
            HttpResponse response = this.codec(context, Map.of()).error(new IllegalArgumentException("bad input"));
            assertEquals(400, response.status());
            assertEquals("application/json", response.header("content-type"));
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("invalid_argument"));
        }
    }

    @Test
    public void localCancellationUsesHttp499() throws Exception {
        // 本地取消映射为 canceled，而不是普通服务器错误。
        try (MetContext context = this.configuration().buildContext()) {
            HttpResponse response = this.codec(context, Map.of()).error(new CancellationException("cancelled"));
            assertEquals(499, response.status());
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("canceled"));
        }
    }

    @Test
    public void localDeadlineUsesHttp504() throws Exception {
        // 统一超时异常沿用 Connect 的 deadline_exceeded 状态。
        try (MetContext context = this.configuration().buildContext()) {
            HttpResponse response = this.codec(context, Map.of()).error(new MetTimeoutException("expired"));
            assertEquals(504, response.status());
            assertTrue(new String(response.body(), StandardCharsets.UTF_8).contains("deadline_exceeded"));
        }
    }

    @Test
    public void invalidCompressionConfigurationFailsImmediately() throws Exception {
        // 不能把未知压缩选项保留到调用时才发现。
        try (MetContext context = this.configuration().buildContext()) {
            assertThrows(IllegalArgumentException.class, () -> this.codec(context, Map.of("compression", "br")));
        }
    }

    @Test
    public void absentTimeoutAllowsTheExistingCallerToChooseTheDeadline() throws Exception {
        // 没有超时头时，不凭空引入协议层的短超时。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.post("echo", "application/json", "{}");
            assertEquals(Integer.MAX_VALUE, this.codec(context, Map.of()).open(input).getClientTimeout());
        }
    }

    @Test
    public void largeValidTimeoutIsClampedWithoutOverflow() throws Exception {
        // 协议允许十位毫秒，框架的 int 超时采用饱和转换。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "connect-timeout-ms", List.of("9999999999"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertEquals(Integer.MAX_VALUE, this.codec(context, Map.of()).open(input).getClientTimeout());
        }
    }

    @Test
    public void rejectsAZeroTimeout() throws Exception {
        // 超时必须是正整数，不能用 0 表达永不超时。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "connect-timeout-ms", List.of("0"));
            HttpRequest input = this.request("POST", "echo", headers, "{}");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void acceptsExplicitUtf8Charset() throws Exception {
        // HTTP 媒体参数声明 UTF-8 时继续使用 Connect JSON 编码。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.post("echo", "application/json; charset=UTF-8", "{}");
            assertEquals("Json", this.codec(context, Map.of()).open(input).getSerializeType());
        }
    }

    @Test
    public void rejectsANonUtf8Charset() throws Exception {
        // JSON 不接受与协议 UTF-8 约定冲突的字符集。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            HttpRequest input = this.post("echo", "application/json; charset=ISO-8859-1", "{}");
            MetException failure = assertThrows(MetException.class, () -> codec.open(input));
            assertEquals(415, codec.error(failure).status());
        }
    }

    @Test
    public void rejectsPostPathsContainingAQuery() throws Exception {
        // 普通 POST 不使用 GET 的查询正文机制。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.post("echo?extra=1", "application/json", "{}");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void rejectsPathsContainingFragments() throws Exception {
        // URL 片段不是过程地址的一部分。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            HttpRequest input = this.post("echo#part", "application/json", "{}");
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void getRejectsPercentEncodedInvalidUtf8() throws Exception {
        // 非法百分号字节不能替换为 Unicode 替代字符。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("safeEcho") + "?encoding=json&message=%FF";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void getRequiresBase64ForProtobuf() throws Exception {
        // 二进制正文必须通过 base64=1 明确表达。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("safeEcho") + "?encoding=proto&message=AA";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void getRequiresBase64ForCompressedJson() throws Exception {
        // 即使编码为 JSON，压缩后也已经是二进制字节。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            String path = this.path("safeEcho") + "?encoding=json&compression=gzip&message=AA";
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());
            assertThrows(ConnectStatusException.class, () -> this.codec(context, Map.of()).open(input));
        }
    }

    @Test
    public void getDecodesCompressedJson() throws Exception {
        // 压缩 GET 正文先通过 URL 安全 Base64，再经过 gzip 解压。
        try (MetContext context = this.configuration().buildContext()) {
            this.publish(context);
            ConnectHttpCodec codec = this.codec(context, Map.of());
            byte[] compressed = this.gzip("{\"text\":\"compressed-get\"}");
            String message = Base64.getUrlEncoder().withoutPadding().encodeToString(compressed);
            String path = this.path("safeEcho") + "?encoding=json&compression=gzip&base64=1&message=" + message;
            HttpRequest input = HttpRequest.newBuffered("GET", path, Map.of(), new byte[0], 1, Map.of());

            // 最终参数与未压缩的普通 JSON 对象相同。
            RequestPayload request = codec.open(input);
            codec.readRequest(request, input);
            assertEquals("compressed-get", ((ConnectMessage) request.getParameterValues().get(0)).getText());
        }
    }

    @Test
    public void responseGzipRequiresExplicitNegotiation() throws Exception {
        // 只有客户端接受 gzip，服务端才使用已配置的响应压缩。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            RequestPayload request = this.payload(service, "echo");
            request.addOption("connect-accept-encoding", "gzip,identity");
            HttpResponse response = this.codec(context, Map.of("compression", "gzip")) //
                    .encode(request, this.response(ConnectMessage.of("gzip-reply", 1)));

            // 用独立的 JDK 解压器读取实际响应字节。
            assertEquals("gzip", response.header("content-encoding"));
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(response.body()))) {
                String json = new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(json.contains("gzip-reply"));
            }
        }
    }

    @Test
    public void responseDecodeSupportsGzip() throws Exception {
        // 对端在普通响应上声明整段 gzip 编码。
        try (MetContext context = this.configuration().buildContext()) {
            MetBindInfo<?> service = this.publish(context);
            Map<String, List<String>> headers = Map.of("content-type", List.of("application/json"), //
                    "content-encoding", List.of("gzip"));
            byte[] body = this.gzip("{\"text\":\"gzip-decode\"}");
            HttpResponse input = HttpResponse.newBuffered(200, headers, body, Map.of());
            ResponsePayload response = this.codec(context, Map.of()).decode(this.payload(service, "echo"), input);
            assertEquals("gzip-decode", ((ConnectMessage) response.getReturnData()).getText());
        }
    }

    @Test
    public void ignoresAnErrorDocumentInAnUnrelatedMediaType() throws Exception {
        // 对端 text/plain 内容碰巧长得像 Connect 错误，仍使用 HTTP 回退表。
        try (MetContext context = this.configuration().buildContext()) {
            byte[] body = "{\"code\":\"permission_denied\"}".getBytes(StandardCharsets.UTF_8);
            HttpResponse input = HttpResponse.newBuffered(503, //
                    Map.of("content-type", List.of("text/plain")), body, Map.of());
            ConnectStatusException failure = assertThrows(ConnectStatusException.class, //
                    () -> this.codec(context, Map.of()).decode(new RequestPayload(), input));
            assertEquals("unavailable", failure.getCode());
        }
    }

    @Test
    public void readsCommaCombinedBinaryHeaders() throws Exception {
        // HTTP 中间层可能把两个同名二进制头合并为一行。
        try (MetContext context = this.configuration().buildContext()) {
            RequestPayload request = new RequestPayload();
            this.codec(context, Map.of()).readHeaders(Map.of("trace-bin", List.of("AQ==, Ag")), request);
            List<byte[]> values = ConnectMetadata.from(request).getBinaryHeader("trace-bin");
            assertEquals(2, values.size());
            assertArrayEquals(new byte[] { 1 }, values.get(0));
            assertArrayEquals(new byte[] { 2 }, values.get(1));
        }
    }

    @Test
    public void getAcceptsAnEmptyIncrementalHttpBody() throws Exception {
        // 传输层统一用增量正文表示 GET，空正文不应被误判为携带消息内容。
        try (MetContext context = this.configuration().buildContext(); //
             HttpBodyStream body = new HttpBodyStream(64)) {
            this.publish(context);
            body.finish(Map.of());
            String path = this.path("safeEcho") + "?encoding=json&message=%7B%7D";
            HttpRequest input = HttpRequest.newStreaming("GET", path, Map.of(), body, 2);
            assertEquals("safeEcho", this.codec(context, Map.of()).open(input).getTargetMethod());
        }
    }

    @Test
    public void businessIoFailureDoesNotBecomeAnInvalidRequest() throws Exception {
        // 服务执行期间抛出的 IOException 是业务失败，不能归咎于客户端消息格式。
        try (MetContext context = this.configuration().buildContext()) {
            ResponsePayload response = new ResponsePayload();
            response.setFailure(new IOException("storage read failed"));
            HttpResponse encoded = this.codec(context, Map.of()).error(response.getFailure(), response);
            assertEquals(500, encoded.status());
            assertTrue(new String(encoded.body(), StandardCharsets.UTF_8).contains("unknown"));
        }
    }

    @Test
    public void malformedWireIoFailureIsAnInvalidArgument() throws Exception {
        // 协议接收阶段的 IOException 对应损坏的请求报文。
        try (MetContext context = this.configuration().buildContext()) {
            HttpResponse encoded = this.codec(context, Map.of()).error(new IOException("truncated input"));
            assertEquals(400, encoded.status());
            assertTrue(new String(encoded.body(), StandardCharsets.UTF_8).contains("invalid_argument"));
        }
    }

    private MetBindInfo<ConnectDemoService> publish(MetContext context) throws Exception {
        GrpcProtoResource resource = new GrpcProtoResource(this.getClass().getResource("/proto/connect-demo.proto"));
        return context.publisher().rsfService(ConnectDemoService.class, new ConnectDemoServiceImpl()) //
                .aliasName("connect", "meteor.connect.demo.ConnectDemoService") //
                .serialize("Json").bindResource(resource).register();
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("connect");
        configuration.connector("http").option("httpVersion", 2).protocol("connect");
        return configuration;
    }

    private ConnectHttpCodec codec(MetContext context, Map<String, String> extra) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("contextPath", "/");
        options.put("httpVersion", "2");
        options.put("maxMessageSize", "4096");
        options.putAll(extra);
        return new ConnectHttpCodec(ProtocolConfig.newConfig("connect", "connect", options), context);
    }

    private RequestPayload payload(MetBindInfo<?> service, String method) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod(method);
        request.setSerializeType("Json");
        request.setClientTimeout(2000);
        return request;
    }

    private ResponsePayload response(Object value) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setSerializeType("Json");
        response.setReturnData(value);
        return response;
    }

    private HttpRequest post(String method, String contentType, String body) {
        return this.request("POST", method, Map.of("content-type", List.of(contentType)), body);
    }

    private HttpRequest request(String httpMethod, String method, Map<String, List<String>> headers, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuffered(httpMethod, this.path(method), headers, bytes, 1, Map.of());
    }

    private String path(String method) {
        return "/meteor.connect.demo.ConnectDemoService/" + method;
    }

    private byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream output = new GZIPOutputStream(bytes)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
