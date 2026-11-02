/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.codec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetIgnore;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class XmlRpcBindingTest {
    private MetContext      context;
    private ProtocolConfig  protocol;
    private RouteMountIndex routes;
    private XmlRpcCodec     codec;
    private XmlRpcWireCodec wire;

    @BeforeEach
    public void preparePublishedMetadata() throws Exception {
        MetConfiguration configuration = new MetConfiguration().setDefaultGroup("wire");
        configuration.connector("tcp").protocol("rsf");
        configuration.connector("http").protocol("xmlrpc").contextPath("/rpc").addContextPath("/private");
        this.context = configuration.buildContext();
        this.context.publisher().rsfService(Methods.class, new Methods()) //
                .name("Methods").aliasName("xmlrpc", "math").register();
        this.protocol = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("contextPath", "/rpc"));
        this.routes = new RouteMountIndex("http-1", this.protocol);
        this.codec = new XmlRpcCodec(this.protocol, this.routes, this.context);
        this.wire = new XmlRpcWireCodec(this.protocol);
    }

    @AfterEach
    public void closeContext() throws Exception {
        this.context.close();
    }

    @Test
    public void positionalParametersUseDeclaredOrder() throws Exception {
        // 两个参数按接口声明顺序传给 Dispatcher。
        RequestPayload request = this.decode("math.subtract", List.of(7, 2));

        // 本地 ID 由会话分配，XML-RPC 没有通知模式。
        assertEquals(List.of("int", "int"), request.getParameterTypes());
        assertEquals(List.of(7, 2), request.getParameterValues());
        assertEquals("wire", request.getServiceGroup());
        assertEquals("Methods", request.getServiceName());
        assertFalse(request.isMessage());
        assertEquals(0, request.getRequestID());
    }

    @Test
    public void genericListRetainsDtoElements() throws Exception {
        // 数组中的结构体按照参数泛型恢复。
        RequestPayload request = this.decode("math.people", List.of(List.of(Map.of("name", "Ada"))));
        List<?> result = (List<?>) request.getParameterValues().get(0);
        assertEquals("Ada", ((Person) result.get(0)).getName());
    }

    @Test
    public void genericMapRetainsDtoValues() throws Exception {
        // 字典值使用 Java 泛型定义而不是普通 Map。
        RequestPayload request = this.decode("math.peopleByName", List.of(Map.of("first", Map.of("name", "Ada"))));
        Map<?, ?> result = (Map<?, ?>) request.getParameterValues().get(0);
        assertEquals("Ada", ((Person) result.get("first")).getName());
    }

    @Test
    public void arrayParameterUsesDeclaredComponent() throws Exception {
        // XML 数组可以绑定 Java 的原始类型数组。
        RequestPayload request = this.decode("math.integers", List.of(List.of(3, 5)));
        assertArrayEquals(new int[] { 3, 5 }, (int[]) request.getParameterValues().get(0));
    }

    @Test
    public void binaryParameterRemainsByteArray() throws Exception {
        // base64 标签映射为原始字节，不经过字符串中转。
        byte[] bytes = { 0, 1, -1 };
        RequestPayload request = this.decode("math.binary", List.of(bytes));
        assertArrayEquals(bytes, (byte[]) request.getParameterValues().get(0));
    }

    @Test
    public void dateParameterRemainsDate() throws Exception {
        // XML 日期转换后保留 Date 类型和秒精度。
        Date value = new Date(1700000000000L);
        RequestPayload request = this.decode("math.date", List.of(value));
        assertEquals(value, request.getParameterValues().get(0));
    }

    @Test
    public void nestedObjectMembersPreserveDateAndBinary() throws Exception {
        // 无固定类型的 DTO 字段仍保留 XML 明确声明的日期与二进制类型。
        Date date = new Date(1700000000000L);
        byte[] bytes = { 3, 4 };
        Map<String, Object> members = Map.of("value", date, "other", bytes);
        RequestPayload request = this.decode("math.dynamic", List.of(members));

        // 不能先转为 JSON 再恢复，这会把 Object 日期退化为数字。
        Dynamic result = (Dynamic) request.getParameterValues().get(0);
        assertEquals(date, result.value);
        assertArrayEquals(bytes, (byte[]) result.other);
    }

    @Test
    public void genericObjectMapRetainsNativeValues() throws Exception {
        // Map<String,Object> 同样保留原始日期对象。
        Date date = new Date(1700000000000L);
        RequestPayload request = this.decode("math.dynamicMap", List.of(Map.of("date", date)));
        Map<?, ?> result = (Map<?, ?>) request.getParameterValues().get(0);
        assertEquals(date, result.get("date"));
    }

    @Test
    public void genericObjectListRetainsNativeValues() throws Exception {
        // List<Object> 不把日期转换成时间戳。
        Date date = new Date(1700000000000L);
        RequestPayload request = this.decode("math.dynamicList", List.of(List.of(date)));
        assertEquals(List.of(date), request.getParameterValues().get(0));
    }

    @Test
    public void propertyAliasUsesJacksonDeclaration() throws Exception {
        // DTO 的公开属性别名同时作用于输入和输出结构体。
        RequestPayload request = this.decode("math.renamed", List.of(Map.of("display_name", "Ada")));
        assertEquals("Ada", ((Renamed) request.getParameterValues().get(0)).name);
    }

    @Test
    public void narrowerNumericTypeRejectsOverflow() throws Exception {
        // 不能将 XML 的 255 隐式截断为 Java byte 的 -1。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.small", List.of(255)));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void scopedServiceUsesUnqualifiedMethod() throws Exception {
        // 指定服务的挂载点只需要发送方法名。
        ProtocolConfig scoped = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", //
                Map.of("contextPath", "/rpc", "service", "math"));
        this.codec = new XmlRpcCodec(scoped, new RouteMountIndex("http-1", scoped), this.context);
        assertEquals("ping", this.decode("ping", List.of()).getTargetMethod());
    }

    @Test
    public void otherScopedServiceIsNotVisible() {
        // service 选项不允许借助完整名称跨越到别的服务。
        ProtocolConfig scoped = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", //
                Map.of("contextPath", "/rpc", "service", "other"));
        this.codec = new XmlRpcCodec(scoped, new RouteMountIndex("http-1", scoped), this.context);
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.ping", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void omittedAliasUsesServiceName() throws Exception {
        // 服务名可以直接作为默认方法前缀。
        this.context.publisher().rsfService(Methods.class, new Methods()).name("fallback").register();
        assertEquals("fallback", this.decode("fallback.ping", List.of()).getServiceName());
    }

    @Test
    public void consumerDeclarationIsNotInvocable() {
        // 订阅元信息不能作为本地提供者调用。
        this.context.publisher().rsfService(Methods.class).name("consumer").register();
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("consumer.ping", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void hiddenServiceIsNotInvocable() {
        // shadow 服务不能从公开挂载点调用。
        MetBindInfo<?> info = this.context.getServiceInfo("wire", "Methods", "1.0.0");
        ServiceDomain<?> service = MetBindInfoWrap.unwrap(info);
        service.setShadow(true);
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.ping", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void otherProtocolServiceIsNotVisible() {
        // 只开放 RSF 的服务不出现在 XML-RPC 方法表。
        this.context.publisher().rsfService(Methods.class, new Methods()).name("private").protocol("rsf").register();
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("private.ping", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void rejectedProtocolDoesNotExposeOrdinaryMethods() {
        // 未启用 nil 的端点拒绝带有空返回值的整个接口，RSF 仍可接受它。
        MetBindInfo<UnsupportedMethods> service = this.context.publisher() //
                .rsfService(UnsupportedMethods.class, new UnsupportedMethods()) //
                .name("rejected").register();
        ServiceDomain<?> domain = MetBindInfoWrap.unwrap(service);
        assertNull(domain.getProtocolMeta("xmlrpc"));

        // 不能通过普通方法名绕过发布阶段的拒绝结果。
        XmlRpcException failure = assertThrows(XmlRpcException.class, () -> this.decode("rejected.ping", List.of()));
        assertEquals(-32601, failure.getCode());
    }

    @Test
    public void otherContextServiceIsNotVisible() {
        // 显式挂载到私有目录的服务不在默认目录暴露。
        this.context.publisher().rsfService(Methods.class, new Methods()).name("private") //
                .contextPathByPrefix("xmlrpc", "/private").register();
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("private.ping", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void distinctAritySelectsUniqueOverload() throws Exception {
        // 参数数量可以唯一确定方法时允许同名重载。
        RequestPayload request = this.decode("math.arity", List.of(2, 3));
        assertEquals(2, request.getParameterTypes().size());
    }

    @Test
    public void sameArityOverloadsAreRejectedBeforeInboundDispatch() {
        // 同数量重载无法由 XML-RPC 唯一定位，发布检查拒绝整个接口。
        MetBindInfo<AmbiguousMethods> service = this.context.publisher() //
                .rsfService(AmbiguousMethods.class, new AmbiguousMethods()) //
                .name("ambiguous").register();
        ServiceDomain<?> domain = MetBindInfoWrap.unwrap(service);
        assertNull(domain.getProtocolMeta("xmlrpc"));

        // 服务仍在容器中，入站报文不能绕过实际端点的拒绝结果。
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.decode("ambiguous.overload", List.of(1)));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void numericStringCannotBecomeInteger() {
        // 类型不匹配不能通过字符串解析偷偷修正。
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.decode("math.subtract", List.of("7", 2)));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void fractionCannotBecomeInteger() {
        // 小数不能截断为整数后继续调用。
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.decode("math.subtract", List.of(7.5, 2)));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void stringCannotBecomeBinary() {
        // 即使字符串内容是合法 base64，也需要明确的 base64 标签。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.binary", List.of("YQ==")));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void integerCannotBecomeDate() {
        // 日期必须来自 dateTime.iso8601，而不是任意整数时间戳。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.date", List.of(123)));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void unknownBeanPropertyIsRejected() {
        // 错拼字段不能被静默丢弃。
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.decode("math.people", List.of(List.of(Map.of("nam", "Ada")))));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void objectMethodIsNotVisible() {
        // Object 方法不是服务的方法。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.toString", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void ignoredMethodIsNotVisible() {
        // MetIgnore 标注的方法不进入协议方法表。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.hidden", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void staticMethodIsNotVisible() {
        // 静态工具方法不构成实例服务方法。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.staticMethod", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void namesRemainCaseSensitive() {
        // 方法名称沿用 XML-RPC 的大小写区分。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.PING", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void missingArgumentsReturnInvalidParams() {
        // 找到方法名称但参数数量不匹配时返回参数错误。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.subtract", List.of(1)));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void unknownMethodReturnsMethodNotFound() {
        // 不存在的方法使用独立的查找错误。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.missing", List.of()));
        assertEquals(-32601, error.getCode());
    }

    @Test
    public void inheritedGenericParameterResolvesFromPublishedType() throws Exception {
        // 父类方法声明 T，具体发布类型绑定为 Person。
        this.context.publisher().rsfService(PersonService.class, new PersonService()).name("generic").register();
        RequestPayload request = this.decode("generic.select", List.of(Map.of("name", "Ada")));
        assertEquals("Ada", ((Person) request.getParameterValues().get(0)).getName());
        assertEquals(List.of(Object.class.getName()), request.getParameterTypes());
    }

    @Test
    public void nilCannotBecomePrimitiveDefault() throws Exception {
        // nil 扩展不会把 null 默认化成原始类型的零值。
        ProtocolConfig nil = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", //
                Map.of("contextPath", "/rpc", "allowNil", "true"));
        this.codec = new XmlRpcCodec(nil, new RouteMountIndex("http-1", nil), this.context);
        this.wire = new XmlRpcWireCodec(nil);
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.decode("math.subtract", Arrays.asList(null, 2)));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void nilCanBePassedToReferenceParameter() throws Exception {
        // 启用 nil 后，引用类型参数可以收到真正的 null。
        ProtocolConfig nil = ProtocolConfig.newConfig("xmlrpc", "xmlrpc", //
                Map.of("contextPath", "/rpc", "allowNil", "true"));
        this.codec = new XmlRpcCodec(nil, new RouteMountIndex("http-1", nil), this.context);
        this.wire = new XmlRpcWireCodec(nil);
        RequestPayload request = this.decode("math.binary", Arrays.asList((Object) null));
        assertNull(request.getParameterValues().get(0));
    }

    @Test
    public void doubleOverflowCannotBecomeInfiniteFloat() {
        // 合法 XML double 不能在 Java float 窄化后产生无穷大。
        XmlRpcException error = assertThrows(XmlRpcException.class, () -> this.decode("math.decimal", List.of(3e100)));
        assertEquals(-32602, error.getCode());
    }

    @Test
    public void malformedXmlUsesParseFault() {
        // 解析失败在绑定边界转换为约定 fault，不能当作普通内部异常。
        byte[] body = "<methodCall>".getBytes(StandardCharsets.UTF_8);
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.codec.decodeRequest(body, this.routes.match("/rpc")));
        assertEquals(-32700, error.getCode());
    }

    @Test
    public void invalidEnvelopeUsesParseFault() {
        // XML 本身有效但并非 XML-RPC 调用时，同样属于文档解析错误。
        byte[] body = "<other/>".getBytes(StandardCharsets.UTF_8);
        XmlRpcException error = assertThrows(XmlRpcException.class, //
                () -> this.codec.decodeRequest(body, this.routes.match("/rpc")));
        assertEquals(-32700, error.getCode());
    }

    @Test
    public void methodTypeVariableShadowsThePublishedClassVariable() throws Exception {
        // 方法的 <T> 遮蔽父类的 T，不应被具体发布类上的 Person 绑定替换。
        this.context.publisher().rsfService(ShadowPersonService.class, new ShadowPersonService()) //
                .name("shadow").register();

        // 未确定的方法类型保持普通值，不把整数误当作 Person 结构体。
        RequestPayload request = this.decode("shadow.select", List.of(7));
        assertEquals(List.of(7), request.getParameterValues());
    }

    private RequestPayload decode(String method, List<?> values) throws Exception {
        byte[] body = this.wire.encodeRequest(method, values);
        return this.codec.decodeRequest(body, this.routes.match("/rpc"));
    }

    public static class UnsupportedMethods {
        public String ping() {
            return "pong";
        }

        public void consume(String value) {
        }
    }

    public static class Methods {
        public int subtract(int left, int right) {
            return left - right;
        }

        public String ping() {
            return "pong";
        }

        public List<Person> people(List<Person> people) {
            return people;
        }

        public Map<String, Person> peopleByName(Map<String, Person> people) {
            return people;
        }

        public int[] integers(int[] values) {
            return values;
        }

        public byte[] binary(byte[] value) {
            return value;
        }

        public Date date(Date value) {
            return value;
        }

        public Dynamic dynamic(Dynamic value) {
            return value;
        }

        public Map<String, Object> dynamicMap(Map<String, Object> value) {
            return value;
        }

        public List<Object> dynamicList(List<Object> value) {
            return value;
        }

        public Renamed renamed(Renamed value) {
            return value;
        }

        public byte small(byte value) {
            return value;
        }

        public float decimal(float value) {
            return value;
        }

        public int arity(int value) {
            return value;
        }

        public int arity(int first, int second) {
            return first + second;
        }

        @MetIgnore
        public String hidden() {
            return "hidden";
        }

        public static String staticMethod() {
            return "static";
        }
    }

    public static class AmbiguousMethods {
        public int overload(int value) {
            return value;
        }

        public int overload(String value) {
            return value.length();
        }
    }

    public static class GenericService<T> {
        public T select(T value) {
            return value;
        }
    }

    public static class PersonService extends GenericService<Person> {
    }

    public static class ShadowGenericService<T> {
        public <T> T select(T value) {
            return value;
        }
    }

    public static class ShadowPersonService extends ShadowGenericService<Person> {
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

    public static class Dynamic {
        public Object value;
        public Object other;
    }

    public static class Renamed {
        @JsonProperty("display_name")
        public String name;
    }
}
