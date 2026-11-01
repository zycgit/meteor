/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.thrift.codec.ThriftServiceSchema;
import net.hasor.meteor.protocol.thrift.session.ThriftSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** TCP 入口的公开 SPI、帧前缀探测与提供者委托。 */
public class ThriftProtocolTest {
    @Test
    public void ordinaryNestedIfaceIsNotMistakenForAGeneratedService() {
        // 普通嵌套接口也可以叫 Iface，不能只按名字判断是否来自生成器。
        ServiceDomain<Iface> service = new ServiceDomain<>(Iface.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 没有官方 Processor 时，仍按普通注解契约编译。
        ProtocolBinding binding = new ThriftProtocol().prepareBinding(this.config(Map.of()), null, meta);
        assertTrue(binding.getDefinition() instanceof ThriftServiceSchema);
        assertEquals("Iface", binding.getDescription().path("name").asText());
        assertFalse(binding.getDescription().path("methods").isEmpty());
        assertFalse(binding.getDescription().path("types").isEmpty());
    }

    @Test
    public void annotationMappingDoesNotConsumeAnotherProtocolsBoundResource() {
        // 普通接口的 Thrift 契约由注解完整声明，绑定资源可以交给其他协议。
        ServiceDomain<Iface> service = new ServiceDomain<>(Iface.class);
        Object foreign = new Object();
        service.setBindResource("soap", foreign);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());

        // 当前发布结果持有自己的定义，用户的绑定资源保持原样。
        ProtocolBinding binding = new ThriftProtocol().prepareBinding(this.config(Map.of()), null, meta);
        assertTrue(binding.getDefinition() instanceof ThriftServiceSchema);
        assertSame(foreign, service.getProtocolMeta("soap").getBindResource());
    }

    public interface Iface {
        String echo(@MetParam(id = 1) String value);
    }

    @Test
    public void providerDeclaresNativeThriftOverTcp() {
        // 协议身份和消息类型共同决定复用的 TCP 传输。
        ThriftProtocol provider = new ThriftProtocol();
        assertEquals("thrift", provider.name());
        assertEquals("tcp", provider.transportType());
        assertEquals("thrift", provider.defaultSerialize());
        assertSame(byte[].class, provider.messageType());
    }

    @Test
    public void serviceLoaderDiscoversTheRootTcpProvider() {
        // 使用正式 SPI 资源发现根包入口，不直接登记测试专用提供者。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(ThriftProtocol.class::isInstance) //
                .findFirst().orElseThrow();
        assertEquals("thrift", provider.name());
    }

    @Test
    public void incompleteFrameLengthNeedsMoreBytes() {
        // 一个长度头拆成多个网络块时，路由判断必须等待完整的四字节头。
        byte[] length = ByteBuffer.allocate(Integer.BYTES).putInt(32).array();
        ProtocolConfig config = this.config(Map.of());
        for (int count = 0; count < Integer.BYTES; count++) {
            byte[] prefix = Arrays.copyOf(length, count);
            RouteProbeResult result = new ThriftProtocol().probeProtocol(config, prefix);
            assertSame(RouteProbeResult.NEED_MORE, result);
        }
    }

    @Test
    public void completeFrameLengthStillWaitsForProtocolMarker() {
        // 长度合法但正文尚未到达，不足以区分同端口的其他协议。
        byte[] prefix = ByteBuffer.allocate(Integer.BYTES).putInt(32).array();
        RouteProbeResult result = new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void zeroFrameLengthRejectsTheRoute() {
        // 零长度不能承载任何 Thrift 消息。
        byte[] prefix = this.framePrefix(0, 0x80);
        assertSame(RouteProbeResult.REJECT, new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix));
    }

    @Test
    public void negativeFrameLengthRejectsTheRoute() {
        // 负长度不能绕过有符号整型的消息大小校验。
        byte[] prefix = this.framePrefix(-1, 0x80);
        assertSame(RouteProbeResult.REJECT, new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix));
    }

    @Test
    public void frameLargerThanConfiguredMaximumRejectsTheRoute() {
        // 在读取完整正文之前拒绝超过挂载上限的长度头。
        ProtocolConfig config = this.config(Map.of("maxMessageSize", "16"));
        byte[] prefix = this.framePrefix(17, 0x80);
        assertSame(RouteProbeResult.REJECT, new ThriftProtocol().probeProtocol(config, prefix));
    }

    @Test
    public void frameAtConfiguredMaximumCanSelectTheRoute() {
        // 上限本身属于允许范围，正文完整性仍交由后续解码处理。
        ProtocolConfig config = this.config(Map.of("maxMessageSize", "16"));
        byte[] prefix = this.framePrefix(16, 0x80);
        assertSame(RouteProbeResult.MATCH, new ThriftProtocol().probeProtocol(config, prefix));
    }

    @Test
    public void strictBinaryMarkerSelectsFramedThrift() {
        // 版本化 Binary 的首字节足以在长度头之后识别协议。
        byte[] prefix = this.framePrefix(32, 0x80);
        assertSame(RouteProbeResult.MATCH, new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix));
    }

    @Test
    public void compactMarkerSelectsFramedThrift() {
        // Compact 使用自己的协议标识，不需要先收到完整消息。
        byte[] prefix = this.framePrefix(32, 0x82);
        assertSame(RouteProbeResult.MATCH, new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix));
    }

    @Test
    public void jsonMarkerSelectsFramedThrift() {
        // 原生 Thrift JSON 消息以数组开头。
        byte[] prefix = this.framePrefix(32, '[');
        assertSame(RouteProbeResult.MATCH, new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix));
    }

    @Test
    public void legacyBinaryNameLengthSelectsFramedThrift() {
        // 非版本化 Binary 先写方法名长度，大小受消息上限约束。
        byte[] prefix = this.framePrefix(32, 0);
        assertSame(RouteProbeResult.MATCH, new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix));
    }

    @Test
    public void unrelatedFramedBodyDoesNotSelectThrift() {
        // 合法长度头不能使任意正文抢占 Thrift 路由。
        byte[] prefix = this.framePrefix(32, 'G');
        assertSame(RouteProbeResult.REJECT, new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix));
    }

    @Test
    public void emptyUnframedInputNeedsMoreBytes() {
        // buffered transport 没有长度头，空输入仍需要等待协议首字节。
        ProtocolConfig config = this.config(Map.of("framed", "false"));
        assertSame(RouteProbeResult.NEED_MORE, new ThriftProtocol().probeProtocol(config, new byte[0]));
    }

    @Test
    public void unframedBinaryStartsDirectlyWithProtocolMarker() {
        // 不把原生 Binary 的四字节版本头误读为 transport 帧长度。
        ProtocolConfig config = this.config(Map.of("framed", "false"));
        byte[] prefix = { (byte) 0x80, 1, 0, 1 };
        assertSame(RouteProbeResult.MATCH, new ThriftProtocol().probeProtocol(config, prefix));
    }

    @Test
    public void unframedCompactStartsDirectlyWithProtocolMarker() {
        // Compact buffered transport 同样不携带 framed transport 头。
        ProtocolConfig config = this.config(Map.of("framed", "false"));
        assertSame(RouteProbeResult.MATCH, new ThriftProtocol().probeProtocol(config, new byte[] { (byte) 0x82 }));
    }

    @Test
    public void unrelatedUnframedInputDoesNotSelectThrift() {
        // 其他协议的首字节应交还共享监听的路由选择。
        ProtocolConfig config = this.config(Map.of("framed", "false"));
        assertSame(RouteProbeResult.REJECT, new ThriftProtocol().probeProtocol(config, new byte[] { 'G' }));
    }

    @Test
    public void probingDoesNotConsumeOrAlterTheInput() {
        // 共享监听会继续把相同字节交给被选中的协议会话。
        byte[] prefix = this.framePrefix(32, 0x80);
        byte[] original = prefix.clone();
        new ThriftProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertArrayEquals(original, prefix);
    }

    @Test
    public void providerValidatesTheExplicitGeneratedResource() {
        // 普通业务接口保留消息注解，字段定义来自官方生成的 IDL 契约。
        ServiceDomain<ThriftDemoService> service = new ServiceDomain<>(ThriftDemoService.class);
        service.setBindName("demo");
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());

        // 发布经过提供者校验，返回当前接入点独立的绑定结果。
        ProtocolConfig config = this.config(Map.of());
        ProtocolBinding binding = new ThriftProtocol().prepareBinding(config, null, meta);
        assertTrue(binding.getDefinition() instanceof ThriftServiceSchema);
    }

    @Test
    public void providerRejectsParametersWithoutFieldIds() {
        // 带参数的 Java 签名仍需稳定的字段编号或文件契约。
        ServiceDomain<Consumer> service = new ServiceDomain<>(Consumer.class);
        service.setBindName("missing-idl");
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ThriftProtocol provider = new ThriftProtocol();
        ProtocolConfig config = this.config(Map.of());

        // 缺少 IDL 仅拒绝当前协议，不中断整个服务登记。
        assertNull(provider.prepareBinding(config, null, meta));
    }

    @Test
    public void rejectingAnotherEndpointPreservesAlreadyAcceptedBindings() {
        // 分组已包含一个接受结果，另一个端点仅选择其他服务。
        ServiceDomain<ThriftDemoService> service = new ServiceDomain<>(ThriftDemoService.class);
        ThriftServiceResource resource = new ThriftServiceResource(ExtendedInteropService.class);
        ThriftProtocol provider = new ThriftProtocol();
        ProtocolMeta input = new ProtocolMeta(service, null, resource, Map.of());
        ProtocolBinding accepted = provider.prepareBinding(this.config(Map.of()), null, input);
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of("accepted", accepted));
        ProtocolConfig other = this.config(Map.of("service", "another-service"));

        // 本次拒绝不改变传入分组中已接受端点的定义。
        assertNull(provider.prepareBinding(other, null, meta));
        assertSame(accepted, meta.getEndpoint("accepted"));
        assertTrue(accepted.getDefinition() instanceof ThriftServiceSchema);
    }

    @Test
    public void invalidEndpointEncodingStillFailsPublication() {
        // 不支持的 Java 接口不能掩盖部署配置中填写错误的编码名称。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolConfig config = this.config(Map.of("encoding", "unsupported"));
        ThriftProtocol provider = new ThriftProtocol();

        // 配置错误保持异常，区别于接口契约不受支持时的空拒绝结果。
        assertThrows(IllegalArgumentException.class, () -> provider.prepareBinding(config, null, meta));
    }

    @Test
    public void anotherProtocolsResourceDoesNotSupplyMissingParameterIds() {
        // 服务携带的绑定资源可以属于另一个协议，Thrift 不负责解释它。
        ServiceDomain<Consumer> service = new ServiceDomain<>(Consumer.class);
        service.setBindResource("soap", new Object());
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ThriftProtocol provider = new ThriftProtocol();
        ProtocolConfig config = this.config(Map.of());

        // 当前协议返回拒绝结果，交由发布流程继续尝试其他协议。
        assertNull(provider.prepareBinding(config, null, meta));
    }

    @Test
    public void anotherProtocolsResourcePreservesAlreadyAcceptedEndpoint() {
        // 已接受的端点持有自己的定义，不依赖后续传入的服务绑定资源。
        ServiceDomain<ThriftDemoService> service = new ServiceDomain<>(ThriftDemoService.class);
        service.setBindResource("thrift", new ThriftServiceResource(ExtendedInteropService.class));
        ThriftProtocol provider = new ThriftProtocol();
        ProtocolConfig config = this.config(Map.of());
        ProtocolMeta declaration = service.getProtocolMeta("thrift");
        ProtocolBinding accepted = provider.prepareBinding(config, null, declaration);
        Object resource = declaration.getBindResource();
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of("accepted", accepted));
        service.setBindResource("soap", new Object());

        // 其他协议的资源声明不会改变当前 Thrift 契约或原接入结果。
        assertNotNull(provider.prepareBinding(config, null, meta));
        assertSame(accepted, meta.getEndpoint("accepted"));
        assertTrue(accepted.getDefinition() instanceof ThriftServiceSchema);
    }

    @Test
    public void providerCreatesTheTcpSessionWithoutWritingToTheBorrowedChannel() {
        // 创建会话仅组装插件状态，网络资源仍归连接器持有。
        ProtocolSessionContext context = mock(ProtocolSessionContext.class);
        NetworkChannel<byte[]> channel = mock(NetworkChannel.class);
        ObjLongConsumer<Payload> messages = (payload, requestId) -> {
        };
        ThriftProtocol provider = new ThriftProtocol();
        ProtocolSession<byte[]> session = provider.createSession(this.config(Map.of()), context, channel, messages);

        // 会话等建连通知后才就绪，提供者不抢先写出或关闭通道。
        assertTrue(session instanceof ThriftSession);
        assertFalse(session.ready().isDone());
        verifyNoInteractions(channel);
    }

    private ProtocolConfig config(Map<String, String> options) {
        return ProtocolConfig.newConfig("thrift", "thrift", options);
    }

    private byte[] framePrefix(int length, int marker) {
        return ByteBuffer.allocate(Integer.BYTES + 1).putInt(length).put((byte) marker).array();
    }
}
