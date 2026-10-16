/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.codec;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetIgnore;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.payload.RequestPayload;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonRpcBindingTest {
    private MetContext     context;
    private ProtocolConfig protocol;
    private JsonRpcCodec   codec;

    @Before
    public void prepareServiceMetadata() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        configuration.connector("tcp").protocol("rsf");
        configuration.connector("http").protocol("jsonrpc").contextPath("/rpc").addContextPath("/private");
        this.context = configuration.buildContext();
        this.context.publisher().rsfService(Methods.class, new Methods() {
                }) //
                .name("Methods").aliasName("jsonrpc", "math").register();
        this.protocol = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", Map.of("contextPath", "/rpc"));
        this.codec = new JsonRpcCodec(this.protocol, this.context);
    }

    @After
    public void closeContext() throws Exception {
        this.context.close();
    }

    @Test
    public void positionalParametersUseDeclaredOrderAndTypes() {
        // 参数按接口顺序转换成 Dispatcher 可识别的类型和值。
        JsonRpcCall call = this.decode("math.add", "[2,3]", "7");
        RequestPayload request = call.request();

        // 请求 ID 仍由会话分配，不能复用外部数字 ID。
        assertEquals(List.of("int", "int"), request.getParameterTypes());
        assertEquals(List.of(2, 3), request.getParameterValues());
        assertEquals(0, request.getRequestID());
        assertEquals("wire", request.getServiceGroup());
        assertEquals("Methods", request.getServiceName());
        assertEquals("add", request.getTargetMethod());
    }

    @Test
    public void namedParametersUseDeclaredNamesIndependentlyOfOrder() {
        // 命名参数不依赖 JSON 对象中的字段顺序。
        JsonRpcCall call = this.decode("math.add", "{\"right\":3,\"left\":2}", "7");
        assertEquals(List.of(2, 3), call.request().getParameterValues());
    }

    @Test
    public void genericListParametersKeepTheirElementTypes() {
        // 泛型集合中的 DTO 按声明类型恢复。
        JsonRpcCall call = this.decode("math.people", "[[{\"name\":\"Ada\"}]]", "7");
        List<?> values = (List<?>) call.request().getParameterValues().get(0);
        assertTrue(values.get(0) instanceof Person);
        assertEquals("Ada", ((Person) values.get(0)).getName());
    }

    @Test
    public void missingIdMakesAnOrdinaryMethodANotification() {
        // 通知由线协议缺少 id 决定，不要求 Java 消息注解。
        JsonRpcCall call = this.decode("math.add", "[2,3]", null);
        assertNull(call.id());
        assertTrue(call.request().isMessage());
    }

    @Test
    public void explicitNullIdRemainsARequest() {
        // 显式 null 是合法 ID，不能按缺少字段的通知处理。
        JsonRpcCall call = this.decode("math.add", "[2,3]", "null");
        assertTrue(call.id().isNull());
        assertFalse(call.request().isMessage());
    }

    @Test
    public void stringIdKeepsItsTypeAndValue() {
        // 字符串 ID 不转换为数值请求 ID。
        JsonRpcCall call = this.decode("math.add", "[2,3]", "\"0007\"");
        assertTrue(call.id().isTextual());
        assertEquals("0007", call.id().textValue());
    }

    @Test
    public void decimalIdKeepsItsExactValue() {
        // 十进制 ID 不能经过 double 损失精度。
        String decimal = "12345678901234567890.123456789";
        JsonRpcCall call = this.decode("math.add", "[2,3]", decimal);
        assertEquals(decimal, call.id().decimalValue().toPlainString());
    }

    @Test
    public void explicitIdOverridesJavaMessageAnnotationForInboundCall() {
        // Java 注解不改变外部请求显式要求响应的语义。
        JsonRpcCall call = this.decode("math.notice", "[\"hello\"]", "7");
        assertFalse(call.request().isMessage());
    }

    @Test
    public void unknownNotificationDoesNotReturnAnError() {
        // 有效通知即使方法不存在也不能产生响应。
        JsonRpcCall call = this.decode("math.missing", "[]", null);
        assertNull(call.request());
        assertNull(call.response());
    }

    @Test
    public void invalidParameterNotificationDoesNotReturnAnError() {
        // 参数绑定失败属于通知执行错误，仍不发回响应。
        JsonRpcCall call = this.decode("math.add", "[1]", null);
        assertNull(call.request());
        assertNull(call.response());
    }

    @Test
    public void omittedParametersAreValidForNoArgumentMethod() {
        // 无参方法允许省略 params。
        JsonRpcCall call = this.decode("math.ping", null, "7");
        assertTrue(call.request().getParameterValues().isEmpty());
    }

    @Test
    public void differentAritySelectsAnUnambiguousOverload() {
        // 参数个数不同的重载可以唯一确定方法。
        JsonRpcCall call = this.decode("math.arity", "[1,2]", "7");
        assertEquals(2, call.request().getParameterTypes().size());
    }

    @Test
    public void compilerBridgeDoesNotCreateAnAmbiguousOverload() {
        // 泛型特化生成的桥接方法不属于另一项业务重载。
        this.context.publisher().rsfService(Specialized.class, new Specialized() {
        }).name("specialized").register();
        JsonRpcCall call = this.decode("specialized.select", "[\"value\"]", "7");

        // 采用真实的 String 参数签名，而非编译器生成的 Object 入口。
        assertNotNull(call.request());
        assertEquals(List.of(String.class.getName()), call.request().getParameterTypes());
    }

    @Test
    public void scopedServiceAcceptsUnqualifiedMethodNames() {
        // 端点绑定指定服务后，线协议方法名无需服务前缀。
        ProtocolConfig scoped = ProtocolConfig.newConfig("jsonrpc", "jsonrpc", //
                Map.of("contextPath", "/rpc", "service", "math"));
        this.codec = new JsonRpcCodec(scoped, this.context);
        JsonRpcCall call = this.decode("add", "[1,2]", "7");
        assertNotNull(call.request());
    }

    @Test
    public void missingAliasFallsBackToPublishedServiceName() {
        // 普通接口发布不强制额外声明 JSON-RPC 别名。
        this.context.publisher().rsfService(Fallback.class, new Fallback() {
        }).name("fallback").register();
        JsonRpcCall call = this.decode("fallback.ping", null, "7");
        assertEquals("fallback", call.request().getServiceName());
    }

    @Test
    public void consumerDeclarationsAreNotInvocableProviders() {
        // 仅订阅的接口不能被当作本地服务提供者。
        this.context.publisher().rsfService(Fallback.class).name("consumer").register();
        JsonRpcCall call = this.decode("consumer.ping", null, "7");
        assertEquals(-32601, call.response().path("error").path("code").intValue());
    }

    @Test
    public void otherProtocolServicesAreNotVisible() {
        // 限定其他协议的服务不暴露到 JSON-RPC。
        this.context.publisher().rsfService(Fallback.class, new Fallback() {
                }) //
                .name("private").protocol("rsf").register();
        JsonRpcCall call = this.decode("private.ping", null, "7");
        assertEquals(-32601, call.response().path("error").path("code").intValue());
    }

    @Test
    public void serviceOutsideSelectedContextIsNotVisible() {
        // 默认挂载点不能访问另一个显式上下文发布的服务。
        this.context.publisher().rsfService(Fallback.class, new Fallback() {
                }) //
                .name("private").contextPathByPrefix("jsonrpc", "/private").register();
        JsonRpcCall call = this.decode("private.ping", null, "7");
        assertEquals(-32601, call.response().path("error").path("code").intValue());
    }

    @Test
    public void mixedBatchPreservesNotificationsAndInvalidItems() {
        // 一个批次包含普通调用、通知和无效对象。
        String body = "[{\"jsonrpc\":\"2.0\",\"method\":\"math.ping\",\"id\":1},";
        body += "{\"jsonrpc\":\"2.0\",\"method\":\"math.ping\"},false]";
        JsonRpcEnvelope envelope = this.codec.decodeRequest(body.getBytes(StandardCharsets.UTF_8), //
                this.protocol.matchContext("/rpc"));

        // 只有通知不占用响应，错误项保留自己的位置。
        assertTrue(envelope.batch());
        assertFalse(envelope.calls().get(0).request().isMessage());
        assertTrue(envelope.calls().get(1).request().isMessage());
        assertEquals(-32600, envelope.calls().get(2).response().path("error").path("code").intValue());
    }

    @Test
    public void unknownMethodIsMethodNotFound() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.missing", "[]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32601, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void methodNamesAreCaseSensitive() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.ADD", "[1,2]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32601, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void reservedMethodNamespaceIsNotExposed() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("rpc.ping", "[]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32601, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void missingArgumentsAreInvalidParams() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.add", "[1]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void extraArgumentsAreInvalidParams() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.add", "[1,2,3]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void wrongNamedParameterIsInvalidParams() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.add", "{\"left\":1,\"RIGHT\":2}", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void integerOverflowIsInvalidParams() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.add", "[2147483648,1]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void fractionCannotBeTruncatedToInteger() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.add", "[1.5,2]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void numericStringCannotBeCoercedToInteger() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.add", "[\"1\",2]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void nullCannotBecomePrimitiveDefault() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.add", "[null,2]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void sameArityOverloadsAreRejectedDeterministically() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.overload", "[1]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32602, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void ignoredMethodsAreNotExposed() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.hidden", "[]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32601, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void staticMethodsAreNotExposed() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.staticMethod", "[]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32601, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    @Test
    public void objectMethodsAreNotExposed() {
        // 该输入只验证一个方法解析或参数绑定边界。
        JsonRpcCall call = this.decode("math.toString", "[]", "7");

        // 错误保留请求 ID，不进入 Dispatcher。
        assertNull(call.request());
        assertEquals(-32601, call.response().path("error").path("code").intValue());
        assertEquals(7, call.response().path("id").intValue());
    }

    private JsonRpcCall decode(String method, String params, String id) {
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"";
        if (params != null) {
            body += ",\"params\":" + params;
        }
        if (id != null) {
            body += ",\"id\":" + id;
        }
        byte[] bytes = (body + "}").getBytes(StandardCharsets.UTF_8);
        return this.codec.decodeRequest(bytes, this.protocol.matchContext("/rpc")).calls().get(0);
    }

    public interface Methods {
        default int add(int left, int right) {
            return left + right;
        }

        default String ping() {
            return "pong";
        }

        default List<Person> people(List<Person> values) {
            return values;
        }

        @MetMessage
        default void notice(String value) {
        }

        default int overload(int value) {
            return value;
        }

        default int overload(String value) {
            return value.length();
        }

        default int arity(int first) {
            return first;
        }

        default int arity(int first, int second) {
            return first + second;
        }

        @MetIgnore
        default String hidden() {
            return "hidden";
        }

        static void staticMethod() {
        }
    }

    public interface Fallback {
        default String ping() {
            return "pong";
        }
    }

    public interface Generic<T> {
        T select(T value);
    }

    public interface Specialized extends Generic<String> {
        @Override
        default String select(String value) {
            return value;
        }
    }

    public static class Person {
        private String name;

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
