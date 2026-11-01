/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.Flow;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.tcp.TcpProtocolProvider;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Dubbo 二进制入口的 SPI 身份、帧探测和发布期边界。 */
public class DubboProtocolTest {
    @Test
    public void providerDeclaresDubboBinaryOverTcp() {
        // 协议复用已有 TCP 传输，Hessian2 是默认对象编码。
        DubboProtocol protocol = new DubboProtocol();
        assertEquals("dubbo", protocol.name());
        assertEquals("tcp", protocol.transportType());
        assertEquals("Hessian2", protocol.defaultSerialize());
        assertSame(byte[].class, protocol.messageType());
    }

    @Test
    public void serviceLoaderDiscoversTheRootProvider() {
        // 通过发布产物使用的正式 SPI 描述发现插件入口。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(DubboProtocol.class::isInstance) //
                .findFirst().orElseThrow();
        assertEquals("dubbo", provider.name());
        assertTrue(provider instanceof TcpProtocolProvider);
    }

    @Test
    public void emptyInputNeedsMoreProbeBytes() {
        // 尚未收到 magic 前不能选中或排除该协议。
        RouteProbeResult result = new DubboProtocol().probeProtocol(this.config(), new byte[0]);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void matchingFirstMagicByteNeedsMoreInput() {
        // DA 只是完整 magic 的前缀，等待第二个字节再决定。
        byte[] prefix = { (byte) 0xda };
        RouteProbeResult result = new DubboProtocol().probeProtocol(this.config(), prefix);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void unrelatedFirstByteRejectsDubbo() {
        // 非 DA 的首字节不属于 Dubbo 帧。
        RouteProbeResult result = new DubboProtocol().probeProtocol(this.config(), new byte[] { 0x12 });
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void mismatchingSecondMagicByteRejectsDubbo() {
        // 第二个 magic 字节不匹配时让其它已挂载协议继续探测。
        byte[] prefix = { (byte) 0xda, (byte) 0xbc };
        RouteProbeResult result = new DubboProtocol().probeProtocol(this.config(), prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void completeMagicSelectsDubboBeforeTheBodyArrives() {
        // 两字节 magic 已足以选择协议，帧完整性由后续编解码层处理。
        byte[] prefix = { (byte) 0xda, (byte) 0xbb };
        RouteProbeResult result = new DubboProtocol().probeProtocol(this.config(), prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void completeFramePrefixStillSelectsDubbo() {
        // 探测不消费完整帧的头部或正文。
        byte[] frame = { (byte) 0xda, (byte) 0xbb, (byte) 0xc2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0x4e };
        RouteProbeResult result = new DubboProtocol().probeProtocol(this.config(), frame);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void collectionAndMapDeclarationsRemainOrdinaryValues() {
        // 容器接口是对象值的声明，不应误判为远程回调对象。
        ServiceDomain<CollectionService> service = this.service(CollectionService.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new DubboProtocol().prepareBinding(config, null, meta);

        // 返回时定义和描述已同时准备完成，无需再次调用提供者。
        assertNotNull(binding.getDefinition());
        assertEquals("count", binding.getDescription().path("methods").get(0).path("name").asText());
        assertEquals("Hessian2", binding.getDescription().path("serializeType").asText());
    }

    @Test
    public void publisherParameterIsRejectedAtPublication() {
        // Dubbo 二进制帧不提供单次 RPC 的多消息输入流。
        ServiceDomain<PublisherParameter> service = this.service(PublisherParameter.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void publisherReturnIsRejectedAtPublication() {
        // 不把多消息返回值静默当作可序列化普通对象。
        ServiceDomain<PublisherReturn> service = this.service(PublisherReturn.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void inputStreamParameterIsRejectedAtPublication() {
        // 原生 Java 字节流没有属于此协议的生命周期和反压约定。
        ServiceDomain<InputStreamParameter> service = this.service(InputStreamParameter.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void outputStreamReturnIsRejectedAtPublication() {
        // 返回 OutputStream 不能形成可调用远程对象。
        ServiceDomain<OutputStreamReturn> service = this.service(OutputStreamReturn.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void readerParameterIsRejectedAtPublication() {
        // 字符读取流同样属于不支持的直接流参数。
        ServiceDomain<ReaderParameter> service = this.service(ReaderParameter.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void writerReturnIsRejectedAtPublication() {
        // 字符写入流不得作为普通 Hessian2 返回值发布。
        ServiceDomain<WriterReturn> service = this.service(WriterReturn.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void callbackInterfaceParameterIsRejectedAtPublication() {
        // 业务接口参数需要反向调用能力，本插件不声明这项能力。
        ServiceDomain<CallbackParameter> service = this.service(CallbackParameter.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void remoteObjectReturnIsRejectedAtPublication() {
        // 业务接口返回值不能变成可调用的远程对象引用。
        ServiceDomain<RemoteObjectReturn> service = this.service(RemoteObjectReturn.class);
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    @Test
    public void incompatibleSerializerDeclinesOnlyTheDubboPublication() {
        // 服务仍保留给容器及其它协议，Dubbo 不接受 JSON 正文。
        ServiceDomain<CollectionService> service = this.service(CollectionService.class);
        service.setSerializeType("json");
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(new DubboProtocol().prepareBinding(config, null, meta));
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("dubbo", "dubbo", Map.of());
    }

    private <T> ServiceDomain<T> service(Class<T> type) {
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setBindName(type.getName());
        service.putAliasName("dubbo", type.getName());
        service.setServiceType(MetServiceType.Provider);
        service.setSerializeType("Hessian2");
        return service;
    }

    public interface CollectionService {
        Map<String, Integer> count(List<String> values);
    }

    public interface PublisherParameter {
        String consume(Flow.Publisher<String> values);
    }

    public interface PublisherReturn {
        Flow.Publisher<String> watch(String value);
    }

    public interface InputStreamParameter {
        String upload(InputStream input);
    }

    public interface OutputStreamReturn {
        OutputStream upload();
    }

    public interface ReaderParameter {
        String upload(Reader input);
    }

    public interface WriterReturn {
        Writer upload();
    }

    public interface CallbackParameter {
        void watch(Callback callback);
    }

    public interface RemoteObjectReturn {
        Callback open();
    }

    public interface Callback {
        void accept(String value);
    }
}