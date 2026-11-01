/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Flow;
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TripleMethodShapeTest {
    @Test
    public void ordinaryMethodRetainsMultipleParameters() throws Exception {
        // 普通 Java 方法通过参数列表承载多个值。
        Method method = Service.class.getMethod("join", String.class, int.class);
        MethodMeta description = ServiceMetaParser.method(Service.class, method);
        TripleMethodShape shape = new TripleMethodShape(description);
        assertSame(description.getJavaMethod(), shape.method());
        assertSame(Object[].class, shape.requestType());
        assertSame(String.class, shape.responseType());
        assertFalse(shape.streaming());
    }

    @Test
    public void ordinaryMethodMayHaveNoParameters() throws Exception {
        // 无参调用仍使用普通请求列表而不是 Flow。
        TripleMethodShape shape = this.shape("ping");
        assertSame(Object[].class, shape.requestType());
        assertFalse(shape.streamingRequest());
    }

    @Test
    public void ordinaryMethodMayReturnVoid() throws Exception {
        // void 表示普通调用没有返回值，不等同于流式结束协议。
        TripleMethodShape shape = this.shape("notify", String.class);
        assertSame(void.class, shape.responseType());
        assertFalse(shape.streamingResponse());
    }

    @Test
    public void overloadRetainsExactJavaSignature() throws Exception {
        // 同名重载的反射签名必须保持独立。
        TripleMethodShape text = this.shape("overloaded", String.class);
        TripleMethodShape number = this.shape("overloaded", int.class);
        assertArrayEquals(new Class<?>[] { String.class }, text.method().getParameterTypes());
        assertArrayEquals(new Class<?>[] { int.class }, number.method().getParameterTypes());
    }

    @Test
    public void ordinaryObjectValueDoesNotBecomeAStream() throws Exception {
        // 普通对象可以交给包装序列化器处理。
        TripleMethodShape shape = this.shape("ordinaryObject", Object.class);
        assertSame(Object.class, shape.responseType());
        assertFalse(shape.streaming());
    }

    @Test
    public void requestFlowUsesConcreteElementType() throws Exception {
        // 单个直接 Flow 参数表示客户端消息流。
        TripleMethodShape shape = this.shape("upload", Flow.Publisher.class);
        assertTrue(shape.streamingRequest());
        assertFalse(shape.streamingResponse());
        assertSame(String.class, shape.requestType());
        assertSame(Integer.class, shape.responseType());
    }

    @Test
    public void responseFlowUsesConcreteElementType() throws Exception {
        // 直接 Flow 返回值表示服务端消息流。
        TripleMethodShape shape = this.shape("download", String.class);
        assertFalse(shape.streamingRequest());
        assertTrue(shape.streamingResponse());
        assertSame(String.class, shape.responseType());
    }

    @Test
    public void bidirectionalFlowTracksBothDirections() throws Exception {
        // 参数和返回值同时为 Flow 时保留两个方向的元素类型。
        TripleMethodShape shape = this.shape("chat", Flow.Publisher.class);
        assertTrue(shape.streamingRequest());
        assertTrue(shape.streamingResponse());
        assertTrue(shape.streaming());
        assertSame(String.class, shape.requestType());
        assertSame(Integer.class, shape.responseType());
    }

    @Test
    public void rejectsRawRequestFlow() {
        // 原始 Flow 参数没有可用于解码的消息类型。
        assertThrows(IllegalArgumentException.class, () -> this.shape("rawRequest", Flow.Publisher.class));
    }

    @Test
    public void rejectsRawResponseFlow() {
        // 原始 Flow 返回值没有明确的消息类型。
        assertThrows(IllegalArgumentException.class, () -> this.shape("rawResponse"));
    }

    @Test
    public void rejectsObjectRequestFlow() {
        // Flow<Object> 不能确定网络请求消息的实际类型。
        assertThrows(IllegalArgumentException.class, () -> this.shape("objectRequest", Flow.Publisher.class));
    }

    @Test
    public void rejectsObjectResponseFlow() {
        // Flow<Object> 不能建立稳定的响应消息契约。
        assertThrows(IllegalArgumentException.class, () -> this.shape("objectResponse"));
    }

    @Test
    public void rejectsNestedRequestFlow() {
        // Flow 不能再次包装另一个 Flow 通道。
        assertThrows(IllegalArgumentException.class, () -> this.shape("nestedRequest", Flow.Publisher.class));
    }

    @Test
    public void rejectsNestedResponseFlow() {
        // 返回值也不能形成嵌套流生命周期。
        assertThrows(IllegalArgumentException.class, () -> this.shape("nestedResponse"));
    }

    @Test
    public void rejectsParameterizedRequestElement() {
        // 参数化集合不是直接的具体消息类。
        assertThrows(IllegalArgumentException.class, () -> this.shape("listRequest", Flow.Publisher.class));
    }

    @Test
    public void rejectsParameterizedResponseElement() {
        // 返回流的元素同样要求能够确定的消息类。
        assertThrows(IllegalArgumentException.class, () -> this.shape("listResponse"));
    }

    @Test
    public void rejectsWildcardRequestElement() {
        // 通配符不能作为请求解码的目标类型。
        assertThrows(IllegalArgumentException.class, () -> this.shape("wildcardRequest", Flow.Publisher.class));
    }

    @Test
    public void rejectsGenericResponseElement() {
        // 未解析的泛型变量不能作为线上响应契约。
        assertThrows(IllegalArgumentException.class, () -> this.shape("genericResponse"));
    }

    @Test
    public void rejectsFlowAlongsideOrdinaryParameter() {
        // 请求流必须是唯一直接参数，不能再附带普通参数。
        assertThrows(IllegalArgumentException.class, () -> this.shape("mixed", Flow.Publisher.class, String.class));
    }

    @Test
    public void rejectsPublisherSubtypeParameter() {
        // 发布接口使用标准 Publisher 契约，不能要求框架构造用户子类型。
        assertThrows(IllegalArgumentException.class, () -> this.shape("customRequest", CustomPublisher.class));
    }

    @Test
    public void rejectsPublisherSubtypeReturn() {
        // 自定义 Publisher 返回值不能退化成普通 DTO 交给序列化器。
        assertThrows(IllegalArgumentException.class, () -> this.shape("customResponse"));
    }

    @Test
    public void rejectsInputStreamParameter() {
        // 阻塞字节输入流不属于 Triple 消息参数。
        assertThrows(IllegalArgumentException.class, () -> this.shape("inputParameter", InputStream.class));
    }

    @Test
    public void rejectsOutputStreamParameter() {
        // 阻塞字节输出流不能跨 RPC 传递所有权。
        assertThrows(IllegalArgumentException.class, () -> this.shape("outputParameter", OutputStream.class));
    }

    @Test
    public void rejectsReaderParameter() {
        // 字符输入流不能当作普通序列化值。
        assertThrows(IllegalArgumentException.class, () -> this.shape("readerParameter", Reader.class));
    }

    @Test
    public void rejectsWriterParameter() {
        // 字符输出流不能当作普通序列化值。
        assertThrows(IllegalArgumentException.class, () -> this.shape("writerParameter", Writer.class));
    }

    @Test
    public void rejectsInputStreamReturn() {
        // 返回字节流不能代替响应 Flow。
        assertThrows(IllegalArgumentException.class, () -> this.shape("inputReturn"));
    }

    @Test
    public void rejectsOutputStreamReturn() {
        // 返回输出流不具备消息级流控契约。
        assertThrows(IllegalArgumentException.class, () -> this.shape("outputReturn"));
    }

    @Test
    public void rejectsReaderReturn() {
        // 返回字符输入流需要应用改为显式消息流。
        assertThrows(IllegalArgumentException.class, () -> this.shape("readerReturn"));
    }

    @Test
    public void rejectsWriterReturn() {
        // 返回字符输出流不能建立网络响应通道。
        assertThrows(IllegalArgumentException.class, () -> this.shape("writerReturn"));
    }

    @Test
    public void rejectsIoStreamAsFlowElement() {
        // Flow 元素仍须是数据，不能将阻塞 I/O 流藏入其中。
        assertThrows(IllegalArgumentException.class, () -> this.shape("ioRequest", Flow.Publisher.class));
    }

    @Test
    public void rejectsIoStreamResponseElement() {
        // 响应 Flow 也不能传输 I/O 流对象。
        assertThrows(IllegalArgumentException.class, () -> this.shape("ioResponse"));
    }

    @Test
    public void inheritedRequestFlowUsesThePublishedServiceType() throws Exception {
        // 泛型父接口的 T 由具体发布接口绑定为 String。
        Method method = TextFlow.class.getMethod("upload", Flow.Publisher.class);
        MethodMeta description = ServiceMetaParser.method(TextFlow.class, method);

        // 流形态从公共描述读取已解析的元素，不把父接口的 T 当作未绑定变量。
        assertTrue(TripleMethodShape.supports(description));
        assertEquals(String.class, new TripleMethodShape(description).requestType());
    }

    @Test
    public void inheritedResponseFlowUsesThePublishedServiceType() throws Exception {
        // 返回 Publisher<T> 继承自同一个泛型父接口。
        Method method = TextFlow.class.getMethod("download");
        MethodMeta description = ServiceMetaParser.method(TextFlow.class, method);

        // 已确定的响应消息类型与请求流使用相同的公共泛型规则。
        assertTrue(TripleMethodShape.supports(description));
        assertEquals(String.class, new TripleMethodShape(description).responseType());
    }

    @Test
    public void inheritedRequestFlowKeepsTheParameterizedMessageRestriction() throws Exception {
        // T 虽然已确定为 List<String>，现有 Triple Flow 仍只支持具体的单消息类。
        Method method = ListFlow.class.getMethod("upload", Flow.Publisher.class);
        MethodMeta description = ServiceMetaParser.method(ListFlow.class, method);

        // 泛型解析不能绕过直接声明 Flow<List<String>> 时的协议限制。
        assertFalse(TripleMethodShape.supports(description));
    }

    @Test
    public void inheritedResponseFlowKeepsTheParameterizedMessageRestriction() throws Exception {
        // 返回消息同样遵守协议自身的类型范围。
        Method method = ListFlow.class.getMethod("download");
        MethodMeta description = ServiceMetaParser.method(ListFlow.class, method);

        // 请求方向和响应方向共享同一份已解析类型校验。
        assertFalse(TripleMethodShape.supports(description));
    }

    private TripleMethodShape shape(String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = Service.class.getMethod(name, parameters);
        return new TripleMethodShape(ServiceMetaParser.method(Service.class, method));
    }

    public interface GenericFlow<T> {
        String upload(Flow.Publisher<T> values);

        Flow.Publisher<T> download();
    }

    public interface TextFlow extends GenericFlow<String> {
    }

    public interface ListFlow extends GenericFlow<List<String>> {
    }

    public interface CustomPublisher extends Flow.Publisher<String> {
    }

    public interface Service {
        String join(String prefix, int count);

        String ping();

        void notify(String value);

        String overloaded(String value);

        String overloaded(int value);

        Object ordinaryObject(Object value);

        Integer upload(Flow.Publisher<String> request);

        Flow.Publisher<String> download(String request);

        Flow.Publisher<Integer> chat(Flow.Publisher<String> request);

        @SuppressWarnings("rawtypes")
        String rawRequest(Flow.Publisher request);

        @SuppressWarnings("rawtypes")
        Flow.Publisher rawResponse();

        String objectRequest(Flow.Publisher<Object> request);

        Flow.Publisher<Object> objectResponse();

        String nestedRequest(Flow.Publisher<Flow.Publisher<String>> request);

        Flow.Publisher<Flow.Publisher<String>> nestedResponse();

        String listRequest(Flow.Publisher<List<String>> request);

        Flow.Publisher<List<String>> listResponse();

        String wildcardRequest(Flow.Publisher<? extends String> request);

        <T> Flow.Publisher<T> genericResponse();

        String mixed(Flow.Publisher<String> request, String value);

        String customRequest(CustomPublisher request);

        CustomPublisher customResponse();

        String inputParameter(InputStream request);

        String outputParameter(OutputStream request);

        String readerParameter(Reader request);

        String writerParameter(Writer request);

        InputStream inputReturn();

        OutputStream outputReturn();

        Reader readerReturn();

        Writer writerReturn();

        String ioRequest(Flow.Publisher<InputStream> request);

        Flow.Publisher<Reader> ioResponse();
    }
}
