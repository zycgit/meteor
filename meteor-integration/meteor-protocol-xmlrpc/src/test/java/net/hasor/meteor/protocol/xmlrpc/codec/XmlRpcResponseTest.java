/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.codec;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRpcResponseTest {
    private MetContext      context;
    private XmlRpcCodec     codec;
    private XmlRpcWireCodec wire;

    @Before
    public void prepareConsumerMetadata() throws Exception {
        this.context = new MetConfiguration().setDefaultGroup("wire").buildContext();
        this.context.publisher().rsfService(XmlRpcBindingTest.Methods.class) //
                .name("Methods").aliasName("xmlrpc", "math").register();
        ProtocolConfig protocol = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("contextPath", "/rpc"));
        this.codec = new XmlRpcCodec(protocol, this.context);
        this.wire = new XmlRpcWireCodec(protocol);
    }

    @After
    public void closeContext() throws Exception {
        this.context.close();
    }

    @Test
    public void outgoingMethodUsesServiceAlias() throws Exception {
        // 公开的别名组成远端 methodName。
        XmlRpcCall call = this.wire.decodeRequest(this.codec.encodeRequest(this.request("ping")));
        assertEquals("math.ping", call.methodName());
        assertTrue(call.parameters().isEmpty());
    }

    @Test
    public void outgoingContextUsesConsumerDeclaration() {
        // 请求地址从消费者的挂载选择取得。
        assertEquals("/rpc", this.codec.requestPath(this.request("ping")));
    }

    @Test
    public void scopedRequestOmitsServicePrefix() throws Exception {
        // scoped 端点直接使用方法名。
        ProtocolConfig config = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", //
                Map.of("contextPath", "/rpc", "service", "math"));
        XmlRpcCodec scoped = new XmlRpcCodec(config, this.context);
        assertEquals("ping", this.wire.decodeRequest(scoped.encodeRequest(this.request("ping"))).methodName());
    }

    @Test
    public void otherScopedServiceCannotBeUsed() {
        // 不允许把当前服务发送到为另一个服务配置的入口。
        ProtocolConfig config = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", //
                Map.of("contextPath", "/rpc", "service", "other"));
        XmlRpcCodec scoped = new XmlRpcCodec(config, this.context);
        assertThrows(IOException.class, () -> scoped.encodeRequest(this.request("ping")));
    }

    @Test
    public void messageRequestFailsBeforeEncoding() {
        // 不把消息调用伪装成需要响应的 XML-RPC 请求。
        RequestPayload request = this.request("ping");
        request.setMessage(true);
        assertThrows(IOException.class, () -> this.codec.encodeRequest(request));
    }

    @Test
    public void outgoingDtoKeepsNativeDateAndBinary() throws Exception {
        // DTO 对象展开为结构体时保留 Date 与 byte[] 的标签。
        XmlRpcBindingTest.Dynamic value = new XmlRpcBindingTest.Dynamic();
        value.value = new Date(1700000000000L);
        value.other = new byte[] { 0, -1 };
        RequestPayload request = this.request("dynamic");
        request.addParameter(XmlRpcBindingTest.Dynamic.class.getName(), value);

        // 对端解码后看到原始 XML 类型，而不是数字日期和字符串二进制。
        XmlRpcCall call = this.wire.decodeRequest(this.codec.encodeRequest(request));
        Map<?, ?> members = (Map<?, ?>) call.parameters().get(0);
        assertEquals(value.value, members.get("value"));
        assertArrayEquals((byte[]) value.other, (byte[]) members.get("other"));
    }

    @Test
    public void outgoingPrimitiveArrayBecomesArrayValue() throws Exception {
        // byte[] 以外的数组逐项编码为 XML array。
        RequestPayload request = this.request("integers");
        request.addParameter(int[].class.getName(), new int[] { 2, 3 });
        XmlRpcCall call = this.wire.decodeRequest(this.codec.encodeRequest(request));
        assertEquals(List.of(2, 3), call.parameters().get(0));
    }

    @Test
    public void outgoingPropertyAliasUsesDeclaredName() throws Exception {
        // 输出结构体沿用字段的 JsonProperty 别名。
        XmlRpcBindingTest.Renamed value = new XmlRpcBindingTest.Renamed();
        value.name = "Ada";
        byte[] body = this.codec.encodeResponse(this.response(value));
        assertEquals(Map.of("display_name", "Ada"), this.wire.decodeResponse(body));
    }

    @Test
    public void repeatedObjectReferenceIsExpanded() throws Exception {
        // 同一个对象出现在兄弟位置不是循环，可以展开两份结构体。
        XmlRpcBindingTest.Person person = new XmlRpcBindingTest.Person();
        person.setName("Ada");
        byte[] body = this.codec.encodeResponse(this.response(List.of(person, person)));
        assertEquals(List.of(Map.of("name", "Ada"), Map.of("name", "Ada")), this.wire.decodeResponse(body));
    }

    @Test
    public void cyclicResultProducesInternalFault() throws Exception {
        // 自引用对象不能递归到栈溢出，也不能泄漏 Java 结构信息。
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        byte[] body = this.codec.encodeResponse(this.response(cyclic));
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.wire.decodeResponse(body));
        assertEquals(-32603, error.getCode());
    }

    @Test
    public void cyclicRequestFailsBeforeWrite() {
        // 入网前拒绝循环请求参数。
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        RequestPayload request = this.request("dynamicList");
        request.addParameter(List.class.getName(), cyclic);
        assertThrows(IOException.class, () -> this.codec.encodeRequest(request));
    }

    @Test
    public void nonStringMapKeysProduceInternalFault() throws Exception {
        // XML struct 的 member 名称只能为字符串。
        byte[] body = this.codec.encodeResponse(this.response(Map.of(1, "value")));
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.wire.decodeResponse(body));
        assertEquals(-32603, error.getCode());
    }

    @Test
    public void nullResultWithoutExtensionProducesInternalFault() throws Exception {
        // 标准协议无法表示 null，不能伪造为空字符串。
        byte[] body = this.codec.encodeResponse(this.response(null));
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.wire.decodeResponse(body));
        assertEquals(-32603, error.getCode());
    }

    @Test
    public void nativeFaultPreservesApplicationCodeAndMessage() throws Exception {
        // 业务显式异常采用约定的 fault 字段。
        Throwable failure = new InvocationTargetException(new XmlRpcException(42, "Rejected"));
        byte[] body = this.codec.encodeError(failure);
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.wire.decodeResponse(body));
        assertEquals(42, error.getCode());
        assertEquals("Rejected", error.getMessage());
    }

    @Test
    public void arbitraryFailureDoesNotLeakDetails() throws Exception {
        // 普通 Java 异常映射为内部错误，故障正文不携带实现细节。
        byte[] body = this.codec.encodeError(new IllegalStateException("private database credentials"));
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.wire.decodeResponse(body));
        assertEquals(-32603, error.getCode());
        assertEquals("Internal error", error.getMessage());
    }

    @Test
    public void failedPayloadPreservesItsApplicationException() throws Exception {
        // Dispatcher 的失败通过原始异常映射为 fault。
        ResponsePayload response = this.response(null);
        response.setStatus(ProtocolStatus.InvokeError);
        response.setFailure(new XmlRpcException(18, "Not available"));
        byte[] body = this.codec.encodeResponse(response);
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.wire.decodeResponse(body));
        assertEquals(18, error.getCode());
    }

    @Test
    public void incomingResultUsesDeclaredGenericDto() throws Exception {
        // 消费者接口的泛型恢复数组元素类型。
        RequestPayload request = this.request("people");
        request.addParameter(List.class.getName(), List.of());
        byte[] body = this.wire.encodeResponse(List.of(Map.of("name", "Ada")));
        ResponsePayload response = this.codec.decodeResponse(request, body);
        List<?> values = (List<?>) response.getReturnData();
        assertEquals("Ada", ((XmlRpcBindingTest.Person) values.get(0)).getName());
        assertEquals(7, response.getRequestID());
        assertEquals(ProtocolStatus.OK, response.getStatus());
    }

    @Test
    public void incomingDateKeepsItsType() throws Exception {
        // 日期结果按照消费者 Date 声明恢复。
        RequestPayload request = this.request("date");
        request.addParameter(Date.class.getName(), new Date(0));
        Date date = new Date(1700000000000L);
        ResponsePayload response = this.codec.decodeResponse(request, this.wire.encodeResponse(date));
        assertEquals(date, response.getReturnData());
    }

    @Test
    public void incomingBinaryKeepsItsType() throws Exception {
        // base64 结果不退化成字符串。
        RequestPayload request = this.request("binary");
        request.addParameter(byte[].class.getName(), new byte[0]);
        byte[] bytes = { 1, -1, 0 };
        ResponsePayload response = this.codec.decodeResponse(request, this.wire.encodeResponse(bytes));
        assertArrayEquals(bytes, (byte[]) response.getReturnData());
    }

    @Test
    public void incomingNativeValuesInsideDtoStayNative() throws Exception {
        // DTO 的 Object 字段仍保留 XML 类型而非 JSON 类型。
        RequestPayload request = this.request("dynamic");
        request.addParameter(XmlRpcBindingTest.Dynamic.class.getName(), null);
        Date date = new Date(1700000000000L);
        byte[] body = this.wire.encodeResponse(Map.of("value", date, "other", new byte[] { 1 }));
        ResponsePayload response = this.codec.decodeResponse(request, body);
        XmlRpcBindingTest.Dynamic value = (XmlRpcBindingTest.Dynamic) response.getReturnData();
        assertEquals(date, value.value);
        assertArrayEquals(new byte[] { 1 }, (byte[]) value.other);
    }

    @Test
    public void wrongResultTypeFailsConsumerCall() throws Exception {
        // 整数不能被静默转换成 String 返回值。
        byte[] body = this.wire.encodeResponse(7);
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("ping"), body));
    }

    @Test
    public void faultCannotBecomeBusinessResult() throws Exception {
        // 消费者接到 fault 时直接获得代码和信息。
        byte[] body = this.wire.encodeFault(32, "Rejected");
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.codec.decodeResponse(this.request("ping"), body));
        assertEquals(32, error.getCode());
    }

    @Test
    public void unavailableConsumerMethodFailsClearly() throws Exception {
        // 响应不能被装配到订阅接口中不存在的方法。
        byte[] body = this.wire.encodeResponse("pong");
        assertThrows(IOException.class, () -> this.codec.decodeResponse(this.request("missing"), body));
    }

    @Test
    public void normalizationDepthIsBoundedBeforeXmlEncoding() {
        // DTO 展开也遵循深度限制，不能等待 XML 写出后才限制。
        ProtocolConfig config = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("maxDepth", "2"));
        XmlRpcCodec limited = new XmlRpcCodec(config, this.context);
        RequestPayload request = this.request("dynamicMap");
        request.addParameter(Map.class.getName(), Map.of("outer", Map.of("inner", 1)));
        assertThrows(IOException.class, () -> limited.encodeRequest(request));
    }

    @Test
    public void emptyStructCanRoundTripWithoutBeanProperties() throws Exception {
        // 空 Map 是合法空结构体，不需要 DTO 属性。
        byte[] body = this.codec.encodeResponse(this.response(new LinkedHashMap<>()));
        assertEquals(Map.of(), this.wire.decodeResponse(body));
    }

    @Test
    public void inheritedGenericResultResolvesFromConsumerType() throws Exception {
        // 消费者继承泛型方法时，以订阅类型解析 T。
        this.context.publisher().rsfService(XmlRpcBindingTest.PersonService.class).name("generic").register();
        RequestPayload request = this.request("select");
        request.setServiceName("generic");
        request.addParameter(Object.class.getName(), null);
        byte[] body = this.wire.encodeResponse(Map.of("name", "Ada"));
        ResponsePayload response = this.codec.decodeResponse(request, body);
        assertEquals("Ada", ((XmlRpcBindingTest.Person) response.getReturnData()).getName());
    }

    @Test
    public void voidResultUsesNilWhenEnabled() throws Exception {
        // nil 扩展为无返回值方法提供合法单个响应。
        this.context.publisher().rsfService(EmptyResult.class).name("empty").register();
        ProtocolConfig nil = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("allowNil", "true"));
        XmlRpcCodec codec = new XmlRpcCodec(nil, this.context);
        RequestPayload request = this.request("consume");
        request.setServiceName("empty");

        // void 的 nil 正常结束调用，不产生通知回执。
        byte[] body = codec.encodeResponse(this.response(null));
        ResponsePayload response = codec.decodeResponse(request, body);
        assertNull(response.getReturnData());
        assertEquals("void", response.getReturnType());
    }

    @Test
    public void nonNilVoidResultIsRejected() throws Exception {
        // void 消费者不能接受被对端伪造的业务结果。
        this.context.publisher().rsfService(EmptyResult.class).name("empty").register();
        ProtocolConfig nil = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("allowNil", "true"));
        XmlRpcCodec codec = new XmlRpcCodec(nil, this.context);
        RequestPayload request = this.request("consume");
        request.setServiceName("empty");
        byte[] body = this.wire.encodeResponse("unexpected");
        assertThrows(IOException.class, () -> codec.decodeResponse(request, body));
    }

    public interface EmptyResult {
        void consume();
    }

    private RequestPayload request(String method) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(7);
        request.setServiceGroup("wire");
        request.setServiceName("Methods");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod(method);
        return request;
    }

    private ResponsePayload response(Object value) {
        ResponsePayload response = new ResponsePayload();
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }
}
