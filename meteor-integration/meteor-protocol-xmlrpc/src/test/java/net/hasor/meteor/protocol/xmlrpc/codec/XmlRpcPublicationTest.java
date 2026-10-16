/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.xmlrpc.codec;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import java.util.concurrent.Flow;
import net.hasor.meteor.MetMessage;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

public class XmlRpcPublicationTest {
    @Test
    public void ordinaryServiceNeedsNoSchemaResource() {
        // 普通 Java 接口无需 IDL 或绑定资源。
        ServiceDomain<Unary> service = this.service(Unary.class);
        XmlRpcCodec.validateService(this.protocol(false), service);
    }

    @Test
    public void voidRequiresExplicitNilExtension() {
        // 标准 XML-RPC 没有空返回值类型，默认启动时拒绝。
        ServiceDomain<VoidReturn> service = this.service(VoidReturn.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void nilExtensionPermitsVoidReturn() {
        // 启用 nil 后 void 映射为单个 nil 响应，不会变成通知。
        XmlRpcCodec.validateService(this.protocol(true), this.service(VoidReturn.class));
    }

    @Test
    public void boxedVoidAlsoRequiresNilExtension() {
        // Void 同样只能返回 null，不能绕过发布检查。
        ServiceDomain<BoxedVoid> service = this.service(BoxedVoid.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void messageInterfaceIsRejected() {
        // 接口上的消息声明不能用于 XML-RPC。
        ServiceDomain<MessageService> service = this.service(MessageService.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(true), service));
    }

    @Test
    public void configuredMessageServiceIsRejected() {
        // 编程式 asMessage 与接口注解遵循相同约束。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setMessage(true);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(true), service));
    }

    @Test
    public void messageMethodIsRejected() {
        // 单方法消息声明也不能跳过响应。
        ServiceDomain<MessageMethod> service = this.service(MessageMethod.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(true), service));
    }

    @Test
    public void publisherReturnIsRejected() {
        // 持续输出流不能编码成单次 XML 值。
        ServiceDomain<PublisherReturn> service = this.service(PublisherReturn.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void publisherParameterIsRejected() {
        // 持续输入流不构成完整位置参数。
        ServiceDomain<PublisherParameter> service = this.service(PublisherParameter.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void inputStreamIsRejected() {
        // 二进制内容使用 byte[]，不能把流当作 DTO。
        ServiceDomain<Input> service = this.service(Input.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void outputStreamIsRejected() {
        // 输出流不是 XML-RPC 返回值。
        ServiceDomain<Output> service = this.service(Output.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void readerIsRejected() {
        // 文本流应在业务侧消费为字符串。
        ServiceDomain<TextInput> service = this.service(TextInput.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void writerIsRejected() {
        // Writer 不能承载服务返回通道。
        ServiceDomain<TextOutput> service = this.service(TextOutput.class);
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void invalidAliasIsRejected() {
        // 别名参与 methodName，必须满足 XML-RPC 方法名字符集合。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("xmlrpc", "bad alias");
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void blankServiceNameIsRejected() {
        // 不能生成缺失服务前缀的方法名。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setBindName("");
        assertThrows(IllegalArgumentException.class, () -> XmlRpcCodec.validateService(this.protocol(false), service));
    }

    @Test
    public void otherProtocolServiceKeepsItsOwnCapabilities() {
        // 插件不会限制只声明给 gRPC 的流服务。
        ServiceDomain<PublisherReturn> service = this.service(PublisherReturn.class);
        service.addBindProtocol("grpc");
        XmlRpcCodec.validateService(this.protocol(false), service);
    }

    private <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName("example");
        return service;
    }

    private ProtocolConfig protocol(boolean allowNil) {
        return ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("allowNil", String.valueOf(allowNil)));
    }

    public interface Unary {
        String echo(String value);
    }

    public interface VoidReturn {
        void consume(String value);
    }

    public interface BoxedVoid {
        Void consume(String value);
    }

    @MetMessage
    public interface MessageService {
        void consume(String value);
    }

    public interface MessageMethod {
        @MetMessage
        void consume(String value);
    }

    public interface PublisherReturn {
        Flow.Publisher<String> values();
    }

    public interface PublisherParameter {
        String consume(Flow.Publisher<String> values);
    }

    public interface Input {
        String consume(InputStream value);
    }

    public interface Output {
        OutputStream produce();
    }

    public interface TextInput {
        String consume(Reader value);
    }

    public interface TextOutput {
        Writer produce();
    }
}
