/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import hprose.io.HproseWriter;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.hprose.HproseUnitAccess.Call;
import net.hasor.meteor.protocol.hprose.HproseUnitAccess.Codec;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseRequestTest {
    public interface Echo {
        String echo(String value);

        void pair(List<String> first, List<String> second);

        String under_score(String value);
    }

    public static class EchoImpl implements Echo {
        public String echo(String value) {
            return value;
        }

        public void pair(List<String> first, List<String> second) {
        }

        public String under_score(String value) {
            return value;
        }
    }

    @Test
    public void argumentsPreserveSharedTypedReferences() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            Codec codec = this.codec(context);

            // 两个参数共用一个列表，参数引用表与方法名的引用表分别重置。
            List<String> shared = new ArrayList<>(List.of("中文", "shared"));
            byte[] body = this.call("echo_pair", new Object[] { shared, shared }, false);
            Call call = codec.receive(this.http(body)).calls().get(0);
            assertNull(call.failure());
            List<Object> arguments = call.request().getParameterValues();
            assertEquals(shared, arguments.get(0));
            assertSame(arguments.get(0), arguments.get(1));
        }
    }

    @Test
    public void methodNameReferencesDoNotLeakIntoArguments() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            Codec codec = this.codec(context);

            // 方法名与实参文本相同，也不能误用方法名的引用编号。
            byte[] repeatedName = this.call("echo_echo", new Object[] { "echo_echo" }, false);
            Call repeated = codec.receive(this.http(repeatedName)).calls().get(0);
            assertEquals("echo_echo", repeated.request().getParameterValues().get(0));
        }
    }

    @Test
    public void batchKeepsItsPositionsAndAcceptsReferenceResults() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            Codec codec = this.codec(context);

            // 将三个调用拼成一个批次，只有批次末尾保留结束标记。
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            for (String name : List.of("echo_echo", "missing", "ECHO_ECHO")) {
                byte[] call = this.call(name, new Object[] { "value" }, true);
                body.write(call, 0, call.length - 1);
            }
            body.write('z');

            // 中间的方法不存在不会影响两侧方法，引用回传标记按调用独立保存。
            List<Call> calls = codec.receive(this.http(body.toByteArray())).calls();
            assertEquals(3, calls.size());
            assertTrue(calls.get(0).byReference());
            assertNotNull(calls.get(1).failure());
            assertTrue(calls.get(2).byReference());
            assertEquals("echo", calls.get(2).request().getTargetMethod());
        }
    }

    @Test
    public void truncatedBatchIsRejected() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            Codec codec = this.codec(context);
            byte[] complete = this.call("echo_echo", new Object[] { "value" }, false);

            // 缺失批次结束标记时，拒绝整个请求。
            byte[] invalid = Arrays.copyOf(complete, complete.length - 1);
            assertThrows(IOException.class, () -> codec.receive(this.http(invalid)));
        }
    }

    @Test
    public void trailingBatchBytesAreRejected() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            Codec codec = this.codec(context);
            byte[] complete = this.call("echo_echo", new Object[] { "value" }, false);

            // 结束标记之后的多余字节不能被静默忽略。
            byte[] invalid = Arrays.copyOf(complete, complete.length + 1);
            assertThrows(IOException.class, () -> codec.receive(this.http(invalid)));
        }
    }

    @Test
    public void aliasesAndMethodNamesMayBothContainUnderscores() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher() //
                    .rsfService(Echo.class, new EchoImpl()) //
                    .aliasName("Hprose", "test_echo") //
                    .register();
            Codec codec = this.codec(context);

            // 别名与方法名均含下划线，仍须定位到完整声明。
            byte[] body = this.call("test_echo_under_score", new Object[] { "value" }, false);
            Call call = codec.receive(this.http(body)).calls().get(0);
            assertEquals("under_score", call.request().getTargetMethod());
            assertEquals("value", call.request().getParameterValues().get(0));
        }
    }

    @Test
    public void directCallsRespectServiceExposure() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).protocol("rsf").register();
            Codec codec = this.codec(context);

            // 只暴露 RSF 的服务不能被直接 Hprose 调用绕过协议限制。
            byte[] body = this.call("echo_echo", new Object[] { "value" }, false);
            Call call = codec.receive(this.http(body)).calls().get(0);
            assertNull(call.request());
            assertNotNull(call.failure());
        }
    }

    @Test
    public void oneCallIsAcceptedAtTheBatchLimit() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            byte[] one = this.call("echo_echo", new Object[] { "one" }, false);
            Map<String, String> options = Map.of("contextPath", "/hprose", //
                    "maxBatchCalls", "1", "maxMessageSize", "1024");
            Codec codec = new Codec(new ProtocolConfig("hprose", "hprose", "hprose", options), context);

            // 一条调用恰好达到批次上限，仍是有效请求。
            List<Call> calls = codec.receive(this.http(one)).calls();
            assertEquals(1, calls.size());
        }
    }

    @Test
    public void batchCountLimitRejectsAnExtraCall() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            byte[] one = this.call("echo_echo", new Object[] { "one" }, false);
            Map<String, String> options = Map.of("contextPath", "/hprose", //
                    "maxBatchCalls", "1", "maxMessageSize", "1024");
            Codec codec = new Codec(new ProtocolConfig("hprose", "hprose", "hprose", options), context);

            // 组合两条调用，触发数量限制而不是正文大小限制。
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            batch.write(one, 0, one.length - 1);
            batch.write(one);
            HttpRequest request = this.http(batch.toByteArray());
            IOException tooMany = assertThrows(IOException.class, () -> codec.receive(request));
            assertTrue(tooMany.getMessage().contains("maxBatchCalls"));
        }
    }

    @Test
    public void requestBodyLimitRejectsOversize() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            Map<String, String> options = Map.of("contextPath", "/hprose", //
                    "maxBatchCalls", "1", "maxMessageSize", "1024");
            Codec codec = new Codec(new ProtocolConfig("hprose", "hprose", "hprose", options), context);

            // 正文超过上限一个字节时，在解析调用前拒绝请求。
            HttpRequest request = this.http(new byte[1025]);
            IOException tooLarge = assertThrows(IOException.class, () -> codec.receive(request));
            assertTrue(tooLarge.getMessage().contains("maxMessageSize"));
        }
    }

    @Test
    public void responseBodyLimitRejectsOversize() throws Exception {
        // 注册接口声明，供解码器选择方法并恢复参数类型。
        try (MetContext context = new MetConfiguration().buildContext()) {
            context.publisher().rsfService(Echo.class, new EchoImpl()).register();
            Map<String, String> options = Map.of("contextPath", "/hprose", //
                    "maxBatchCalls", "1", "maxMessageSize", "1024");
            Codec codec = new Codec(new ProtocolConfig("hprose", "hprose", "hprose", options), context);

            // 同一大小限制也约束响应，不能只校验入站请求。
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), new byte[1025], Map.of());
            RequestPayload request = new RequestPayload();
            IOException oversizedResponse = assertThrows(IOException.class, () -> codec.decode(request, response));
            assertTrue(oversizedResponse.getMessage().contains("maxMessageSize"));
        }
    }

    private Codec codec(MetContext context) {
        ProtocolConfig protocol = new ProtocolConfig("hprose", "hprose", "hprose", Map.of("contextPath", "/hprose"));
        return new Codec(protocol, context);
    }

    private HttpRequest http(byte[] body) {
        return HttpRequest.newBuffered("POST", "/hprose", Map.of(), body, 1, Map.of());
    }

    private byte[] call(String method, Object[] arguments, boolean byReference) throws IOException {
        // 方法名使用独立引用表，写入参数前重置以匹配 Hprose 调用格式。
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write('C');
        HproseWriter writer = new HproseWriter(body);
        writer.writeString(method);
        writer.reset();
        writer.writeArray(arguments);

        // 按需添加引用回传标记，并结束本次调用报文。
        if (byReference) {
            body.write('t');
        }
        body.write('z');
        return body.toByteArray();
    }
}
