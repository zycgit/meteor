/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.*;
import java.util.concurrent.Flow;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.hessian.codec.HessianCodecTestFixture.BusinessException;
import net.hasor.meteor.protocol.hessian.codec.HessianCodecTestFixture.Methods;
import net.hasor.meteor.protocol.hessian.codec.HessianCodecTestFixture.Person;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class HessianCodecTest {
    private HessianCodecTestFixture fixture;

    @Before
    public void prepareMetadata() throws Exception {
        this.fixture = new HessianCodecTestFixture();
    }

    @After
    public void closeContext() throws Exception {
        this.fixture.close();
    }

    @Test
    public void hessianOneCallMapsServiceAndParameters() throws Exception {
        // Caucho 生成位置参数调用，服务别名只放在 HTTP 路径上。
        byte[] body = HessianCodecTestFixture.call(1, "subtract", 7, 2);
        RequestPayload request = this.fixture.decode(body);

        // 请求模型保留 Java 类型，内部请求 ID 留给会话分配。
        assertEquals("wire", request.getServiceGroup());
        assertEquals("Methods", request.getServiceName());
        assertEquals("subtract", request.getTargetMethod());
        assertEquals(List.of("int", "int"), request.getParameterTypes());
        assertEquals(List.of(7, 2), request.getParameterValues());
        assertEquals("Hessian", request.getSerializeType());
        assertEquals(0, request.getRequestID());
        assertFalse(request.isMessage());
    }

    @Test
    public void hessianTwoCallMapsServiceAndParameters() throws Exception {
        // Hessian 2 的长度字段与 Java 参数个数一致。
        byte[] body = HessianCodecTestFixture.call(2, "subtract", 9, 4);
        RequestPayload request = this.fixture.decode(body);

        // 不经 JSON 中转，整数类型保持不变。
        assertEquals("subtract", request.getTargetMethod());
        assertEquals(List.of(9, 4), request.getParameterValues());
    }

    @Test
    public void mangledStringOverloadIsSelected() throws Exception {
        // 使用官方方法名算法明确选择 String 重载。
        String method = HessianCodecTestFixture.method("overloaded", String.class);
        byte[] body = HessianCodecTestFixture.call(2, method, "Ada");
        RequestPayload request = this.fixture.decode(body);

        // 交给 Dispatcher 的目标仍为原始 Java 方法名。
        assertEquals("overloaded", request.getTargetMethod());
        assertEquals(List.of(String.class.getName()), request.getParameterTypes());
        assertEquals(List.of("Ada"), request.getParameterValues());
    }

    @Test
    public void mangledIntegerOverloadIsSelected() throws Exception {
        // 同名不同类型的方法不会按反射枚举顺序选错。
        String method = HessianCodecTestFixture.method("overloaded", int.class);
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(1, method, 11));

        // 原始类型声明保留给内核调用。
        assertEquals(List.of("int"), request.getParameterTypes());
        assertEquals(List.of(11), request.getParameterValues());
    }

    @Test
    public void ambiguousUnmangledOverloadIsRejected() throws Exception {
        // 不带参数类型的同名方法无法唯一定位。
        byte[] body = HessianCodecTestFixture.call(2, "overloaded", "Ada");
        assertThrows(Exception.class, () -> this.fixture.decode(body));
    }

    @Test
    public void hessianTwoArityDisambiguatesPlainMethod() throws Exception {
        // Hessian 2 明确携带参数个数，可以区分不同参数数量的重载。
        byte[] body = HessianCodecTestFixture.call(2, "arity", 7, 2);
        RequestPayload request = this.fixture.decode(body);
        assertEquals(List.of("int", "int"), request.getParameterTypes());
    }

    @Test
    public void hessianOneZeroArgumentOverloadUsesExactMangledName() throws Exception {
        // 官方零参方法的 mangled 名就是方法名，不附加参数类型。
        byte[] body = HessianCodecTestFixture.call(1, "optional");
        RequestPayload request = this.fixture.decode(body);

        // 同时存在 optional(String) 时，也能明确选择零参方法。
        assertEquals("optional", request.getTargetMethod());
        assertTrue(request.getParameterTypes().isEmpty());
        assertTrue(request.getParameterValues().isEmpty());
    }

    @Test
    public void legacyTwoReplyCallSelectsZeroArgumentOverload() throws Exception {
        // c 2 0 只改变回复版本，调用参数仍使用 Hessian 1 编码。
        byte[] body = HessianCodecTestFixture.call(1, "optional");
        body[1] = 2;
        RequestPayload request = this.fixture.decode(body);

        // 没有显式参数数量的请求仍按精确 mangled 名选择零参重载。
        assertEquals(2, this.fixture.codec.responseVersion(body));
        assertEquals("optional", request.getTargetMethod());
        assertTrue(request.getParameterTypes().isEmpty());
    }

    @Test
    public void hessianTwoPlainNameUsesArgumentCountOverZeroArgumentOverload() throws Exception {
        // Hessian 2 的普通方法名携带一个参数，不能误选同名零参方法。
        byte[] body = HessianCodecTestFixture.call(2, "optional", "chosen");
        RequestPayload request = this.fixture.decode(body);

        // 参数数量和原始 Java 签名共同确定目标。
        assertEquals("optional", request.getTargetMethod());
        assertEquals(List.of(String.class.getName()), request.getParameterTypes());
        assertEquals(List.of("chosen"), request.getParameterValues());
    }

    @Test
    public void binaryParameterKeepsItsBytes() throws Exception {
        // 二进制块不退化为文本或整数列表。
        byte[] value = { 0, -1, 42 };
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(2, "binary", value));
        assertArrayEquals(value, (byte[]) request.getParameterValues().get(0));
    }

    @Test
    public void dateParameterKeepsMillisecondPrecision() throws Exception {
        // 非整秒日期保留 Hessian 的原生日期语义。
        Date value = new Date(1700000000123L);
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(2, "date", value));
        assertEquals(value, request.getParameterValues().get(0));
    }

    @Test
    public void beanParameterKeepsDeclaredClass() throws Exception {
        // 使用真实可序列化 DTO 作为单个参数。
        Person person = new Person();
        person.setName("Ada");
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(2, "person", person));

        // 参数类型和字段直接按 Hessian 类型信息恢复。
        Person decoded = (Person) request.getParameterValues().get(0);
        assertEquals("Ada", decoded.getName());
        assertNotSame(person, decoded);
    }

    @Test
    public void beanCyclePreservesReferenceIdentity() throws Exception {
        // 同一 DTO 自引用，必须通过引用编号而非递归复制传输。
        Person person = new Person();
        person.setName("cycle");
        person.setNext(person);
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(2, "person", person));

        // 解码对象的环仍指回自身。
        Person decoded = (Person) request.getParameterValues().get(0);
        assertSame(decoded, decoded.getNext());
    }

    @Test
    public void listSharedReferencePreservesIdentity() throws Exception {
        // 列表两个位置引用同一 DTO。
        Person person = new Person();
        person.setName("shared");
        List<Person> values = new ArrayList<>(List.of(person, person));
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(2, "people", values));

        // 引用关系不会被转成两个内容相同的独立对象。
        List<?> decoded = (List<?>) request.getParameterValues().get(0);
        assertEquals("shared", ((Person) decoded.get(0)).getName());
        assertSame(decoded.get(0), decoded.get(1));
    }

    @Test
    public void mapPreservesNativeNestedValues() throws Exception {
        // 一个 Map 中同时携带日期与二进制数据。
        Date date = new Date(1700000000123L);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("date", date);
        values.put("binary", new byte[] { 0, -1 });
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(1, "struct", values));

        // Object 类型的值仍保留原始类型。
        Map<?, ?> decoded = (Map<?, ?>) request.getParameterValues().get(0);
        assertEquals(date, decoded.get("date"));
        assertArrayEquals(new byte[] { 0, -1 }, (byte[]) decoded.get("binary"));
    }

    @Test
    public void primitiveArrayKeepsComponentType() throws Exception {
        // 传递 Java 原始类型数组。
        int[] values = { 1, -2, 3 };
        RequestPayload request = this.fixture.decode(HessianCodecTestFixture.call(2, "integers", values));
        assertArrayEquals(values, (int[]) request.getParameterValues().get(0));
    }

    @Test
    public void nullReferenceParameterIsAccepted() throws Exception {
        // Hessian 原生支持 null，无需额外开关。
        byte[] body = HessianCodecTestFixture.call(2, "echo", (Object) null);
        RequestPayload request = this.fixture.decode(body);
        assertNull(request.getParameterValues().get(0));
    }

    @Test
    public void queryStringDoesNotChangeServiceAlias() throws Exception {
        // HTTP 查询参数不参与服务路径定位。
        byte[] body = HessianCodecTestFixture.call(2, "ping");
        RequestPayload request = this.fixture.codec.decodeRequest(body, "/rpc/math?trace=true", //
                this.fixture.protocol.mountIndex().get("/rpc"));
        assertEquals("Methods", request.getServiceName());
    }

    @Test
    public void unknownServiceIsRejected() throws Exception {
        // 合法方法报文不能命中不存在的路径服务。
        byte[] body = HessianCodecTestFixture.call(2, "ping");
        assertThrows(Exception.class, () -> this.fixture.codec.decodeRequest(body, "/rpc/missing", //
                this.fixture.protocol.mountIndex().get("/rpc")));
    }

    @Test
    public void unknownMethodIsRejected() throws Exception {
        // 服务存在但方法未发布，不能转入反射兜底调用。
        byte[] body = HessianCodecTestFixture.call(2, "missing");
        assertThrows(Exception.class, () -> this.fixture.decode(body));
    }

    @Test
    public void ignoredMethodIsNotExposed() throws Exception {
        // MetIgnore 声明继续控制协议暴露边界。
        byte[] body = HessianCodecTestFixture.call(2, "ignored");
        assertThrows(Exception.class, () -> this.fixture.decode(body));
    }

    @Test
    public void staticMethodIsNotExposed() throws Exception {
        // 静态方法不是发布实例的 RPC 方法。
        byte[] body = HessianCodecTestFixture.call(2, "staticMethod");
        assertThrows(Exception.class, () -> this.fixture.decode(body));
    }

    @Test
    public void objectMethodIsNotExposed() throws Exception {
        // 不能调用 Java Object 的基础方法。
        byte[] body = HessianCodecTestFixture.call(2, "getClass");
        assertThrows(Exception.class, () -> this.fixture.decode(body));
    }

    @Test
    public void consumerOnlyServiceIsNotExposed() throws Exception {
        // 订阅类型不是服务端实现。
        this.fixture.context.publisher().rsfService(Methods.class).name("Consumer").register();
        byte[] body = HessianCodecTestFixture.call(2, "ping");

        // 通过消费者名称访问时返回方法不可用。
        assertThrows(Exception.class, () -> this.fixture.codec.decodeRequest(body, "/rpc/Consumer", //
                this.fixture.protocol.mountIndex().get("/rpc")));
    }

    @Test
    public void outboundPathUsesProtocolAlias() throws Exception {
        // 服务在内核中叫 Methods，对外别名为 math。
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        assertEquals("/rpc/math", this.fixture.codec.requestPath(request));
    }

    @Test
    public void outboundRequestKeepsMangledOverload() throws Exception {
        // 出站编码根据确切 Java 参数类型选择官方重载命名。
        RequestPayload request = this.fixture.request("overloaded", new Class<?>[] { int.class }, 21);
        byte[] body = this.fixture.codec.encodeRequest(request);
        RequestPayload decoded = this.fixture.decode(body);

        // 往返后仍为 int 重载，而不是同名 String 方法。
        assertEquals(List.of("int"), decoded.getParameterTypes());
        assertEquals(List.of(21), decoded.getParameterValues());
    }

    @Test
    public void normalResponseRestoresDeclaredTypeAndRequestId() throws Throwable {
        // 独立服务端返回普通字符串结果。
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        byte[] body = HessianCodecTestFixture.reply(2, "pong");
        ResponsePayload response = this.fixture.codec.decodeResponse(request, body);

        // 协议没有 wire ID，由请求关联信息恢复。
        assertEquals("pong", response.getReturnData());
        assertEquals(String.class.getName(), response.getReturnType());
        assertEquals(7, response.getRequestID());
        assertEquals(ProtocolStatus.OK, response.getStatus());
    }

    @Test
    public void beanResponseKeepsSelfReference() throws Throwable {
        // 服务端返回带环的 DTO。
        Person person = new Person();
        person.setName("response");
        person.setNext(person);
        RequestPayload request = this.fixture.request("person", new Class<?>[] { Person.class }, person);
        byte[] body = HessianCodecTestFixture.reply(2, person);

        // 结果映射不能打散引用关系。
        Person decoded = (Person) this.fixture.codec.decodeResponse(request, body).getReturnData();
        assertEquals("response", decoded.getName());
        assertSame(decoded, decoded.getNext());
    }

    @Test
    public void genericListResponseKeepsDtoElements() throws Throwable {
        // 列表元素自身携带 Hessian 类型，不退化为 Map。
        Person person = new Person();
        person.setName("Ada");
        List<Person> values = new ArrayList<>(List.of(person));
        RequestPayload request = this.fixture.request("people", new Class<?>[] { List.class }, values);
        byte[] body = HessianCodecTestFixture.reply(1, values);

        // 消费者结果仍然可以直接使用 DTO。
        List<?> decoded = (List<?>) this.fixture.codec.decodeResponse(request, body).getReturnData();
        assertEquals("Ada", ((Person) decoded.get(0)).getName());
    }

    @Test
    public void voidResponseUsesNativeNull() throws Throwable {
        // void 调用仍然有一个正常 Hessian 响应。
        RequestPayload request = this.fixture.request("consume", new Class<?>[] { String.class }, "value");
        byte[] body = HessianCodecTestFixture.reply(2, null);
        ResponsePayload response = this.fixture.codec.decodeResponse(request, body);

        // 不把 void 错当成无需响应的 message。
        assertEquals("void", response.getReturnType());
        assertNull(response.getReturnData());
    }

    @Test
    public void applicationFaultPreservesCheckedException() throws Throwable {
        // Dispatcher 保存的原始业务异常成为 Hessian fault detail。
        BusinessException failure = new BusinessException(42, "rejected");
        ResponsePayload payload = HessianCodecTestFixture.response(null);
        payload.setStatus(ProtocolStatus.InvokeError);
        payload.setFailure(failure);
        byte[] body = this.fixture.codec.encodeResponse(payload, 2);

        // 客户端恢复异常类型及业务字段，不能退化成普通响应。
        RequestPayload request = this.fixture.request("ping", new Class<?>[0]);
        BusinessException decoded = assertThrows(BusinessException.class, //
                () -> this.fixture.codec.decodeResponse(request, body));
        assertEquals(42, decoded.getCode());
        assertEquals("rejected", decoded.getMessage());
    }

    @Test
    public void ordinaryServiceNeedsNoBindingResource() {
        // Hessian 根据 Java 方法声明发布，不需要 IDL。
        HessianCodec.validateService(this.fixture.protocol, this.service(Unary.class));
    }

    @Test
    public void currentDirectoryAliasIsRejectedAtPublication() {
        // 单个点会被 URL 客户端归一化为当前目录，不能作为服务路径。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("hessian", ".");

        // 在发布阶段明确拒绝，避免运行期匹配到错误的挂载点。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
        assertEquals("Invalid Hessian service alias: .", failure.getMessage());
    }

    @Test
    public void parentDirectoryAliasIsRejectedAtPublication() {
        // 双点会被 URL 客户端归一化为父目录，不能作为服务路径。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("hessian", "..");

        // 在发布阶段拒绝跨目录的别名。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
        assertEquals("Invalid Hessian service alias: ..", failure.getMessage());
    }

    @Test
    public void voidServiceIsPublishable() {
        // null 是 Hessian 原生值，void 服务无需额外配置。
        HessianCodec.validateService(this.fixture.protocol, this.service(VoidReturn.class));
    }

    @Test
    public void messageInterfaceIsRejectedAtPublication() {
        // Hessian 请求响应协议不支持接口级消息模式。
        ServiceDomain<MessageService> service = this.service(MessageService.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void messageMethodIsRejectedAtPublication() {
        // 单个方法也不能声明无需响应。
        ServiceDomain<MessageMethod> service = this.service(MessageMethod.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void configuredMessageIsRejectedAtPublication() {
        // 编程式消息声明与注解遵守相同边界。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setMessage(true);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void publisherParameterIsRejectedAtPublication() {
        // 持续输入流不能作为完整 Hessian 值发送。
        ServiceDomain<PublisherInput> service = this.service(PublisherInput.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void publisherReturnIsRejectedAtPublication() {
        // 持续结果流不属于本插件的单次响应模型。
        ServiceDomain<PublisherOutput> service = this.service(PublisherOutput.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void inputStreamIsRejectedAtPublication() {
        // 二进制内容应使用 byte[]，不能发送流对象。
        ServiceDomain<BinaryInput> service = this.service(BinaryInput.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void outputStreamIsRejectedAtPublication() {
        // OutputStream 不能作为远程写通道。
        ServiceDomain<BinaryOutput> service = this.service(BinaryOutput.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void readerIsRejectedAtPublication() {
        // Reader 不是可完整序列化的业务对象。
        ServiceDomain<TextInput> service = this.service(TextInput.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void writerIsRejectedAtPublication() {
        // Writer 不能充当 Hessian 返回值。
        ServiceDomain<TextOutput> service = this.service(TextOutput.class);
        assertThrows(IllegalArgumentException.class, //
                () -> HessianCodec.validateService(this.fixture.protocol, service));
    }

    @Test
    public void unrelatedProtocolKeepsItsStreamingContract() {
        // 只挂载其他协议的接口不受 Hessian 发布检查影响。
        ServiceDomain<PublisherOutput> service = this.service(PublisherOutput.class);
        service.addBindProtocol("grpc");
        HessianCodec.validateService(this.fixture.protocol, service);
    }

    private <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName("example");
        return service;
    }

    public interface Unary {
        String echo(String value);
    }

    public interface VoidReturn {
        void consume(String value);
    }

    @MetMessage
    public interface MessageService {
        void consume(String value);
    }

    public interface MessageMethod {
        @MetMessage
        void consume(String value);
    }

    public interface PublisherInput {
        String consume(Flow.Publisher<String> values);
    }

    public interface PublisherOutput {
        Flow.Publisher<String> values();
    }

    public interface BinaryInput {
        String consume(InputStream value);
    }

    public interface BinaryOutput {
        OutputStream produce();
    }

    public interface TextInput {
        String consume(Reader value);
    }

    public interface TextOutput {
        Writer produce();
    }
}
