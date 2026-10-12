/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import hprose.io.HproseReader;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetIgnore;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseCodecTest {
    public interface Parent {
        @MetIgnore
        default String inheritedHidden() {
            return "hidden";
        }
    }

    public interface Echo extends Parent {
        String echo(String value);

        @MetIgnore
        default String echo() {
            return "hidden overload";
        }

        @MetIgnore
        default String hidden() {
            return "hidden";
        }

        static String helper() {
            return "helper";
        }
    }

    @Test
    public void callsDecodeUsingMetadataAndHaveIndependentIdsAndResponseEncoders() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            // 相同报文重复进入解码器时，必须得到两个独立的调用标识。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            request.addParameter("java.lang.String", "中文🙂");
            HttpRequest wire = protocol.encode(request);
            assertEquals("/rpc/wire/Echo/1.0.0", wire.uri());
            HproseInvocation first = protocol.receive(wire);
            HproseInvocation second = protocol.receive(wire);
            assertNotEquals(first.request().getRequestID(), second.request().getRequestID());
            assertEquals("中文🙂", first.request().getParameterValues().get(0));
            assertEquals("java.lang.String", first.request().getParameterTypes().get(0));

            // 响应编码器与入站调用绑定，解码后使用调用方提供的请求 ID。
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(first.request().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("结果🙂");
            ResponsePayload decoded = protocol.decode(91, first.encoder().encode(response));
            assertEquals(91, decoded.getRequestID());
            assertEquals("结果🙂", decoded.getReturnData());
        }
    }

    @Test
    public void functionListOmitsServicesNotPublishedThroughHprose() throws Exception {
        // 服务只向 RSF 暴露，Hprose 的函数发现也不能列出其方法。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).protocol("rsf").register();
            ProtocolConfig mount = new ProtocolConfig("hprose", "hprose", Map.of());
            HproseCodec codec = new HproseCodec(mount, context);
            HttpRequest request = HttpRequest.newBuffered("POST", "/hprose", Map.of(), new byte[] { 'z' }, 1);
            byte[] body = codec.receive(request).immediate().body();
            HproseReader reader = new HproseReader(new ByteArrayInputStream(body, 1, body.length - 1));
            assertArrayEquals(new String[] { "*" }, reader.unserialize(String[].class));
        }
    }

    @Test
    public void functionListAndPathMismatchAreLocalHttpResponses() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            // 函数发现只列出允许发布的方法，忽略方法和静态方法都不可见。
            byte[] discoveryBody = { 'z' };
            HttpRequest listRequest = HttpRequest.newBuffered("POST", "/rpc", Collections.emptyMap(), discoveryBody, 1);
            HproseInvocation functions = protocol.receive(listRequest);
            assertNull(functions.request());
            assertEquals('F', functions.immediate().body()[0]);
            byte[] body = functions.immediate().body();
            byte[] functionData = Arrays.copyOfRange(body, 1, body.length);
            HproseReader reader = new HproseReader(new ByteArrayInputStream(functionData));
            List<String> names = Arrays.asList(reader.unserialize(String[].class));
            assertTrue(names.contains("echo_echo"));
            assertFalse(names.contains("echo_hidden"));
            assertFalse(names.contains("echo_inheritedHidden"));
            assertFalse(names.contains("echo_helper"));

            // 相近前缀不能被当作当前挂载路径。
            HttpRequest otherPath = HttpRequest.newBuffered("POST", "/rpc-other", Collections.emptyMap(), discoveryBody, 1);
            HproseInvocation unmatched = protocol.receive(otherPath);
            assertEquals(404, unmatched.immediate().status());
        }
    }

    @Test
    public void directWireCallsCannotResolveIgnoredOverloadsOrStaticMethods() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            // 直接构造报文也不能调用被忽略、无参重载或静态方法。
            for (String method : List.of("hidden", "inheritedHidden", "echo", "helper")) {
                RequestPayload request = new RequestPayload();
                request.setServiceGroup("wire");
                request.setServiceName("Echo");
                request.setServiceVersion("1.0.0");
                request.setTargetMethod(method);
                HttpRequest wire = protocol.encode(request);
                MetException failure = assertThrows(MetException.class, () -> protocol.receive(wire));
                assertEquals(ProtocolStatus.NotFound, failure.getStatus());
            }
        }
    }

    @Test
    public void unicodeBusinessErrorsRemainFailedResponses() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            ResponsePayload error = new ResponsePayload();
            error.setStatus(ProtocolStatus.NotFound);
            error.addOption("message", "不存在🙂");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            HproseUtils.parseResponse(7, error, bytes);
            HttpResponse wire = HttpResponse.newBuffered(200, Collections.emptyMap(), bytes.toByteArray(), Map.of());
            ResponsePayload decoded = protocol.decode(7, wire);
            assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));

            // 非结构化错误仍通过协议错误帧返回，保留原始文本。
            HttpResponse plainError = protocol.error(new IOException("plain error"));
            ResponsePayload plain = protocol.decode(8, plainError);
            assertEquals(ProtocolStatus.InvokeError, plain.getStatus());
            assertEquals("plain error", plain.getOption("message"));
        }
    }

    @Test
    public void structuredErrorsAcceptNumericAndStringStatusesFromOtherPeers() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            for (String status : Arrays.asList("404", "\"404\"")) {
                String json = "{\"status\":" + status + ",\"message\":\"不存在🙂\",\"extra\":true}";
                HttpResponse wire = protocol.error(new IOException(json));
                ResponsePayload decoded = protocol.decode(7, wire);
                assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
                assertEquals("不存在🙂", decoded.getOption("message"));
            }

            // Hprose 错误报文即使携带成功状态，也不能转成成功调用。
            for (short status : new short[] { ProtocolStatus.OK, ProtocolStatus.Accept }) {
                String json = "{\"status\":" + status + ",\"message\":\"still an error\"}";
                HttpResponse wire = protocol.error(new IOException(json));
                ResponsePayload decoded = protocol.decode(8, wire);
                assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
                assertEquals("still an error", decoded.getOption("message"));
            }
        }
    }

    @Test
    public void malformedStructuredErrorsRemainReadableAsPlainErrors() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            // 结构缺失、状态非法和多个 JSON 正文都退回可读的错误文本。
            List<String> errors = new ArrayList<>();
            errors.add("{");
            errors.add("[]");
            errors.add("null");
            errors.add("{\"status\":null}");
            errors.add("{\"status\":\"bad\"}");
            errors.add("{\"status\":99999}");
            errors.add("{\"status\":404} {\"status\":200}");
            errors.add("ordinary error 中文🙂");
            for (String error : errors) {
                HttpResponse wire = protocol.error(new IOException(error));
                ResponsePayload decoded = protocol.decode(9, wire);
                assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
                assertEquals(error, decoded.getOption("message"));
            }
        }
    }

    @Test
    public void malformedOrTruncatedSuccessfulResponsesAreRejected() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            // 分开描述空报文、截断、尾部多余数据和未知标记。
            byte[] truncated = { 'R', 'n' };
            byte[] trailing = { 'R', 'n', 'z', 'x' };
            byte[] unknown = { '?' };
            List<byte[]> invalid = List.of(new byte[0], truncated, trailing, unknown);
            for (byte[] body : invalid) {
                HttpResponse malformed = HttpResponse.newBuffered(200, Collections.emptyMap(), body, Map.of());
                assertThrows(IOException.class, () -> protocol.decode(1, malformed));
            }

            // 有完整终止符的 null 才是成功响应。
            byte[] nullBody = { 'R', 'n', 'z' };
            HttpResponse emptyValue = HttpResponse.newBuffered(200, Collections.emptyMap(), nullBody, Map.of());
            assertNull(protocol.decode(1, emptyValue).getReturnData());
        }
    }

    @Test
    public void nonSuccessHttpStatusPreservesHeadersWithoutDecodingBody() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);

            // 网关错误正文不必是 Hprose，HTTP 状态及追踪头仍应反馈调用方。
            Map<String, String> gatewayHeaders = Map.of("trace-id", "gateway");
            HttpResponse gateway = HttpResponse.newBuffered(503, gatewayHeaders, new byte[] { '?' }, Map.of());
            ResponsePayload response = protocol.decode(42, gateway);
            assertEquals(42, response.getRequestID());
            assertEquals(503, response.getStatus());
            assertEquals("HTTP status 503", response.getOption("message"));
            assertEquals("gateway", response.getOption("trace-id"));
            assertNull(response.getReturnData());
        }
    }

    @Test
    public void truncatedAndTrailingErrorFramesAreRejected() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = new ProtocolConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseCodec protocol = new HproseCodec(config, context);
            HttpResponse valid = protocol.error(new IOException("remote failure"));
            byte[] body = valid.body();

            // 错误响应与成功响应一样必须完整结束，尾部不能拼接额外报文。
            byte[] truncated = Arrays.copyOf(body, body.length - 1);
            byte[] trailing = Arrays.copyOf(body, body.length + 1);
            for (byte[] malformed : List.of(truncated, trailing)) {
                HttpResponse response = HttpResponse.newBuffered(200, Collections.emptyMap(), malformed, Map.of());
                assertThrows(IOException.class, () -> protocol.decode(1, response));
            }
            assertEquals("remote failure", protocol.decode(1, valid).getOption("message"));
        }
    }

}
