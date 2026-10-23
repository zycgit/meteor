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
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Flow;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRpcPublicationTest {
    @Test
    public void explicitRemoteNameMayUseXmlRpcNameCharacters() throws Exception {
        // XML-RPC 的远程名称允许点和斜线，不受 Java 标识符形式限制。
        ProtocolConfig protocol = this.protocol(false);
        ServiceDomain<RemoteNames> service = this.service(RemoteNames.class);
        Method method = RemoteNames.class.getMethod("repeat", String.class);

        assertEquals("v1.echo/text", XmlRpcCodec.prepareService(protocol, service).get(method));
    }

    @Test
    public void ordinaryServiceNeedsNoSchemaResource() {
        // 普通 Java 接口无需 IDL 或绑定资源。
        ServiceDomain<Unary> service = this.service(Unary.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNotNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void voidRequiresExplicitNilExtension() {
        // 标准 XML-RPC 没有空返回值类型，默认启动时拒绝。
        ServiceDomain<VoidReturn> service = this.service(VoidReturn.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void nilExtensionPermitsVoidReturn() {
        // 启用 nil 后 void 映射为单个 nil 响应，不会变成通知。
        ProtocolConfig protocol = this.protocol(true);
        assertNotNull(XmlRpcCodec.prepareService(protocol, this.service(VoidReturn.class)));
    }

    @Test
    public void boxedVoidAlsoRequiresNilExtension() {
        // Void 同样只能返回 null，不能绕过发布检查。
        ServiceDomain<BoxedVoid> service = this.service(BoxedVoid.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void messageInterfaceIsRejected() {
        // 接口上的消息声明不能用于 XML-RPC。
        ServiceDomain<MessageService> service = this.service(MessageService.class);
        ProtocolConfig protocol = this.protocol(true);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void configuredMessageServiceIsRejected() {
        // 编程式 asMessage 与接口注解遵循相同约束。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setMessage(true);
        ProtocolConfig protocol = this.protocol(true);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void messageMethodIsRejected() {
        // 单方法消息声明也不能跳过响应。
        ServiceDomain<MessageMethod> service = this.service(MessageMethod.class);
        ProtocolConfig protocol = this.protocol(true);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void publisherReturnIsRejected() {
        // 持续输出流不能编码成单次 XML 值。
        ServiceDomain<PublisherReturn> service = this.service(PublisherReturn.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void publisherParameterIsRejected() {
        // 持续输入流不构成完整位置参数。
        ServiceDomain<PublisherParameter> service = this.service(PublisherParameter.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void inputStreamIsRejected() {
        // 二进制内容使用 byte[]，不能把流当作 DTO。
        ServiceDomain<Input> service = this.service(Input.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void outputStreamIsRejected() {
        // 输出流不是 XML-RPC 返回值。
        ServiceDomain<Output> service = this.service(Output.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void readerIsRejected() {
        // 文本流应在业务侧消费为字符串。
        ServiceDomain<TextInput> service = this.service(TextInput.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void writerIsRejected() {
        // Writer 不能承载服务返回通道。
        ServiceDomain<TextOutput> service = this.service(TextOutput.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void invalidAliasIsRejected() {
        // 别名参与 methodName，必须满足 XML-RPC 方法名字符集合。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.putAliasName("xmlrpc", "bad alias");
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void blankServiceNameIsRejected() {
        // 不能生成缺失服务前缀的方法名。
        ServiceDomain<Unary> service = this.service(Unary.class);
        service.setBindName("");
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void capabilityCheckDoesNotFollowDeclaredProtocol() {
        // 服务声明给 gRPC 不会让 XML-RPC 接受流式返回类型。
        ServiceDomain<PublisherReturn> service = this.service(PublisherReturn.class);
        service.addBindProtocol("grpc");
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void providerReturnsAcceptedBindingForSupportedService() {
        // XML-RPC 接受普通服务并生成单个接入结果。
        XmlRpcProtocol provider = new XmlRpcProtocol();
        ServiceDomain<Unary> service = this.service(Unary.class);
        ProtocolConfig protocol = this.protocol(false);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 发布检查返回可供编解码使用的 Java 方法映射。
        ProtocolBinding binding = provider.prepareBinding(protocol, null, meta);
        assertNotNull(binding.getDefinition());
    }

    @Test
    public void providerPreparesTheRemoteNameDescriptionTogetherWithTheBinding() throws Exception {
        // 远程方法名与 Java 方法名不同，描述必须来自准备好的线上操作表。
        ServiceDomain<RemoteNames> service = this.service(RemoteNames.class);
        ProtocolConfig protocol = this.protocol(false);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new XmlRpcProtocol().prepareBinding(protocol, null, meta);

        // 返回的绑定已经包含定义和相同的线上名称，不再经过独立描述阶段。
        Method method = RemoteNames.class.getMethod("repeat", String.class);
        Map<?, ?> definition = (Map<?, ?>) binding.getDefinition();
        String operationName = binding.getDescription().path("methods").get(0).path("name").asText();
        assertEquals(definition.get(method), operationName);
        assertEquals("v1.echo/text", operationName);
    }

    @Test
    public void sameArityOverloadsAreNotSupported() {
        // 线协议只有方法名和参数数量，无法区分同数量的 Java 重载。
        ServiceDomain<SameArity> service = this.service(SameArity.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void differentArityOverloadsRemainSupported() {
        // 参数数量不同仍可唯一选定方法，不拒绝整类重载。
        ServiceDomain<DifferentArity> service = this.service(DifferentArity.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNotNull(XmlRpcCodec.prepareService(protocol, service));
    }

    @Test
    public void generatedBridgeMethodDoesNotCreateAnOverload() {
        // 泛型实现的桥接方法不属于线协议方法表。
        ServiceDomain<GenericEcho> service = this.service(GenericEcho.class);
        ProtocolConfig protocol = this.protocol(false);
        assertNotNull(XmlRpcCodec.prepareService(protocol, service));
    }

    private <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName("example");
        return service;
    }

    private ProtocolConfig protocol(boolean allowNil) {
        return ProtocolConfig.newConfig("xmlrpc", "xmlrpc", Map.of("allowNil", String.valueOf(allowNil)));
    }

    public interface SameArity {
        int echo(int value);

        String echo(String value);
    }

    public interface DifferentArity {
        int echo(int value);

        int echo(int first, int second);
    }

    public interface Generic<T> {
        T echo(T value);
    }

    public static class GenericEcho implements Generic<String> {
        @Override
        public String echo(String value) {
            return value;
        }
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

    public interface RemoteNames {
        @MetMethod(name = "v1.echo/text")
        String repeat(String value);
    }

}
