/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import hprose.io.HproseReader;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetIgnore;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodecTestAccess;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodecTestAccess.DecodedCall;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodecTestAccess.DecodedInvocation;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodecTestAccess.HttpCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseHttpCodecTest {
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
    public void decodeLeavesRequestIdForSession() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 编解码器只恢复调用内容，请求关联标识由会话接管。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            request.addParameter("java.lang.String", "中文🙂");
            HttpRequest wire = protocol.encodeRequest(request);
            DecodedInvocation decoded = protocol.decodeRequest(wire);

            assertEquals(0, decoded.calls().get(0).request().getRequestID());
        }
    }

    @Test
    public void decodedCallPreservesDeclaredArgumentType() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 解码时同时恢复参数值和服务方法声明的 Java 类型。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            request.addParameter("java.lang.String", "中文🙂");
            HttpRequest wire = protocol.encodeRequest(request);
            RequestPayload decoded = protocol.decodeRequest(wire).calls().get(0).request();
            assertEquals("中文🙂", decoded.getParameterValues().get(0));
            assertEquals("java.lang.String", decoded.getParameterTypes().get(0));
        }
    }

    @Test
    public void responseUsesTheCallersRequestId() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 编码服务端响应，再以调用方请求 ID 解码同一条响应。
            HttpRequest wire = protocol.encodeRequest(this.request(1));
            DecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.request().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("结果🙂");
            HttpResponse responseWire = this.response(protocol, call, response);
            ResponsePayload decoded = protocol.decodeResponse(this.request(91), responseWire);
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
            ProtocolConfig mount = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/hprose"));
            HttpCodec codec = new HttpCodec(mount, context);

            // 请求函数列表，确认只发布到 RSF 的服务没有泄漏到 Hprose。
            HttpRequest request = HttpRequest.newBuffered("POST", "/hprose", Map.of(), new byte[] { 'z' }, 1, Map.of());
            byte[] body = codec.decodeRequest(request).immediate().body();
            ByteArrayInputStream functionData = new ByteArrayInputStream(body, 1, body.length - 1);
            HproseReader reader = new HproseReader(functionData);
            assertArrayEquals(new String[0], reader.unserialize(String[].class));
        }
    }

    @Test
    public void functionDiscoveryOmitsIgnoredAndStaticMethods() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 函数发现只列出允许发布的方法，忽略方法和静态方法都不可见。
            byte[] discoveryBody = { 'z' };
            Map<String, List<String>> headers = Collections.emptyMap();
            HttpRequest listRequest = HttpRequest.newBuffered("POST", "/rpc", headers, discoveryBody, 1, Map.of());
            DecodedInvocation functions = protocol.decodeRequest(listRequest);
            assertTrue(functions.calls().isEmpty());
            assertEquals('F', functions.immediate().body()[0]);
            byte[] body = functions.immediate().body();
            byte[] functionData = Arrays.copyOfRange(body, 1, body.length);
            HproseReader reader = new HproseReader(new ByteArrayInputStream(functionData));
            List<String> names = Arrays.asList(reader.unserialize(String[].class));
            assertTrue(names.contains("echo_echo"));
            assertFalse(names.contains("echo_hidden"));
            assertFalse(names.contains("echo_inheritedHidden"));
            assertFalse(names.contains("echo_helper"));
        }
    }

    @Test
    public void similarPathPrefixDoesNotMatchTheMount() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            byte[] discoveryBody = { 'z' };
            // 相近前缀不能被当作当前挂载路径。
            Map<String, List<String>> headers = Collections.emptyMap();
            HttpRequest otherPath = HttpRequest.newBuffered("POST", "/rpc-other", headers, discoveryBody, 1, Map.of());
            DecodedInvocation unmatched = protocol.decodeRequest(otherPath);
            assertEquals(404, unmatched.immediate().status());
        }
    }

    @Test
    public void ignoredMethodCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 构造被忽略方法的调用，确认按不存在的方法处理。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("hidden");
            HttpRequest wire = protocol.encodeRequest(request);
            DecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void inheritedIgnoredMethodCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 构造父接口中被忽略方法的调用，确认继承关系不会绕过限制。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("inheritedHidden");
            HttpRequest wire = protocol.encodeRequest(request);
            DecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void ignoredOverloadCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 调用被忽略的无参重载，确认不会误选已发布的带参方法。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            HttpRequest wire = protocol.encodeRequest(request);
            DecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void staticMethodCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 尝试调用接口静态方法，确认辅助方法不能作为 RPC 执行。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("helper");
            HttpRequest wire = protocol.encodeRequest(request);
            DecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void unicodeBusinessErrorsRemainFailedResponses() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 编码含 Unicode 文本的业务错误，确认状态和消息完整恢复。
            ResponsePayload error = new ResponsePayload();
            error.setStatus(ProtocolStatus.NotFound);
            error.addOption("message", "不存在🙂");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            HproseHttpCodecTestAccess.writeResponse(7, error, bytes);
            HttpResponse wire = HttpResponse.newBuffered(200, Collections.emptyMap(), bytes.toByteArray(), Map.of());
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void unstructuredRemoteErrorPreservesItsText() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 非结构化错误仍通过协议错误帧返回，保留原始文本。
            HttpResponse plainError = protocol.encodeErrorResponse(new IOException("plain error"));
            ResponsePayload plain = protocol.decodeResponse(this.request(8), plainError);
            assertEquals(ProtocolStatus.InvokeError, plain.getStatus());
            assertEquals("plain error", plain.getOption("message"));
        }
    }

    @Test
    public void numericStructuredStatusIsPreserved() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 使用数字类型的结构化状态，确认按该状态恢复远端错误。
            String json = "{\"status\":404,\"message\":\"不存在🙂\",\"extra\":true}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void stringStructuredStatusIsPreserved() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 使用字符串类型的结构化状态，确认兼容解析并保留错误消息。
            String json = "{\"status\":\"404\",\"message\":\"不存在🙂\",\"extra\":true}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void okStatusInAnErrorFrameRemainsFailure() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 错误帧中即使声明 OK，也不能被解码为调用成功。
            String json = "{\"status\":200,\"message\":\"不存在🙂\",\"extra\":true}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void acceptStatusInAnErrorFrameRemainsFailure() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 错误帧中即使声明 Accept，也必须保持调用失败。
            int accepted = ProtocolStatus.Accept;
            String json = "{\"status\":" + accepted + ",\"message\":\"不存在🙂\",\"extra\":true}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void malformedJsonErrorRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 不完整 JSON 作为普通错误文本保留，不丢失远端诊断信息。
            String error = "{";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void arrayJsonErrorRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // JSON 数组不是结构化错误对象，仍原样返回错误文本。
            String error = "[]";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void nullJsonErrorRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // JSON null 不提供错误结构，保留其原始文本。
            String error = "null";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void nullStructuredStatusRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 结构化状态为 null 时，按普通调用错误保留完整文本。
            String error = "{\"status\":null}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void invalidStructuredStatusRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 状态无法解析成数值时，不得吞掉原始错误消息。
            String error = "{\"status\":\"bad\"}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void outOfRangeStructuredStatusRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 状态超出协议取值范围时，回退到普通调用错误。
            String error = "{\"status\":99999}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void multipleStructuredErrorsRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 多个相邻 JSON 对象不是单个错误结构，应保留整段文本。
            String error = "{\"status\":404} {\"status\":200}";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void ordinaryErrorTextRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 普通 Unicode 错误文本不需要 JSON 包装，解码后保持可读。
            String error = "ordinary error 中文🙂";
            HttpResponse wire = protocol.encodeErrorResponse(new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void emptySuccessfulResponseIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 成功 HTTP 响应没有协议正文，必须拒绝解码。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void truncatedSuccessfulResponseIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 成功帧缺少结束标记，必须报告截断错误。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { 'R', 'n' }, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void trailingSuccessfulResponseIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 结束标记之后仍有数据，必须拒绝多余尾部。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { 'R', 'n', 'z', 'x' }, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void unknownSuccessfulResponseTagIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 未知响应标签不能被当作合法返回值。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { '?' }, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void terminatedNullResponseIsSuccessful() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 带完整结束标记的 null 响应是合法成功结果。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { 'R', 'n', 'z' }, Map.of());
            assertNull(protocol.decodeResponse(this.request(1), wire).getReturnData());
        }
    }

    @Test
    public void nonSuccessHttpStatusPreservesHeadersWithoutDecodingBody() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 网关错误正文不必是 Hprose，HTTP 状态及追踪头仍应反馈调用方。
            Map<String, List<String>> gatewayHeaders = Map.of("trace-id", List.of("gateway"));
            HttpResponse gateway = HttpResponse.newBuffered(503, gatewayHeaders, new byte[] { '?' }, Map.of());
            ResponsePayload response = protocol.decodeResponse(this.request(42), gateway);
            assertEquals(42, response.getRequestID());
            assertEquals(503, response.getStatus());
            assertEquals("HTTP status 503", response.getOption("message"));
            assertEquals("gateway", response.getOption("trace-id"));
            assertNull(response.getReturnData());
        }
    }

    @Test
    public void truncatedErrorResponseIsRejected() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 先生成完整错误帧，再移除结束标记模拟截断。
            HttpResponse valid = protocol.encodeErrorResponse(new IOException("remote failure"));
            byte[] body = valid.body();

            byte[] invalid = Arrays.copyOf(body, body.length - 1);
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), invalid, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), response));
        }
    }

    @Test
    public void trailingErrorResponseIsRejected() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 先生成完整错误帧，再追加尾部数据模拟损坏。
            HttpResponse valid = protocol.encodeErrorResponse(new IOException("remote failure"));
            byte[] body = valid.body();

            byte[] invalid = Arrays.copyOf(body, body.length + 1);
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), invalid, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), response));
        }
    }

    @Test
    public void terminatedErrorResponsePreservesRemoteFailure() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = HproseHttpCodecTestAccess.protocolConfig(Map.of("contextPath", "/rpc"));
            HttpCodec protocol = new HttpCodec(config, context);

            // 完整错误帧应恢复原始远端错误消息。
            HttpResponse valid = protocol.encodeErrorResponse(new IOException("remote failure"));
            byte[] body = valid.body();

            ResponsePayload decoded = protocol.decodeResponse(this.request(1), valid);
            assertEquals("remote failure", decoded.getOption("message"));
        }
    }

    private RequestPayload request(long id) {
        // 构造当前测试服务的调用描述，调用方自行决定请求 ID。
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), null);
        return request;
    }

    private HttpResponse response(HttpCodec codec, DecodedCall call, ResponsePayload response) throws Exception {
        // 单条结果编码后补充 Hprose 结束标记，组成完整 HTTP 响应正文。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(codec.encodeResponse(call, response));
        bytes.write('z');
        return HttpResponse.newBuffered(200, Map.of(), bytes.toByteArray(), Map.of());
    }
}
