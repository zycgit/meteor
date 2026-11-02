/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.thrift.codec.ThriftServiceSchema;
import net.hasor.meteor.protocol.thrift.session.ThriftHttpSession;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** HTTP 提供者只提供协议组装，路由和物理传输由内核提供。 */
public class ThriftHttpProtocolTest {
    @Test
    public void providerDeclaresThriftOverSharedHttpExchanges() {
        // 独立 HTTP SPI 使用共享交换模型，不能宣称自己接收 TCP 字节块。
        ThriftHttpProtocol provider = new ThriftHttpProtocol();
        assertEquals("thrift-http", provider.name());
        assertEquals("http", provider.transportType());
        assertEquals("thrift", provider.defaultSerialize());
        assertSame(HttpExchange.class, provider.messageType());
    }

    @Test
    public void serviceLoaderDiscoversTheRootHttpProvider() {
        // HTTP 入口也必须出现在插件的正式 SPI 描述中。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(ThriftHttpProtocol.class::isInstance) //
                .findFirst().orElseThrow();
        assertEquals("thrift-http", provider.name());
    }

    @Test
    public void providerValidatesTheExplicitGeneratedResourceForHttp() {
        // 业务接口通过本地资源绑定 IDL，HTTP 提供者复用同一发布约束。
        ServiceDomain<ThriftDemoService> service = new ServiceDomain<>(ThriftDemoService.class);
        service.setBindName("demo");
        Object bindResource = new ThriftServiceResource(ExtendedInteropService.class);

        // context 只属于 HTTP 挂载，与生成字段元数据相互独立。
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, bindResource, Map.of());
        ProtocolBinding binding = new ThriftHttpProtocol().prepareBinding(config, null, meta);
        assertTrue(binding.getDefinition() instanceof ThriftServiceSchema);
        assertEquals("ExtendedInteropService", binding.getDescription().path("name").asText());
        assertFalse(binding.getDescription().path("methods").isEmpty());
        assertFalse(binding.getDescription().path("types").isEmpty());
    }

    @Test
    public void providerRejectsHttpParametersWithoutFieldIds() {
        // HTTP transport 不能为没有 IDL 的普通方法推测字段编号。
        ServiceDomain<Consumer> service = new ServiceDomain<>(Consumer.class);
        service.setBindName("missing-idl");
        ThriftHttpProtocol provider = new ThriftHttpProtocol();
        ProtocolConfig config = this.config();

        // 缺少 IDL 仅拒绝 HTTP Thrift，不中断整个服务登记。
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        assertNull(provider.prepareBinding(config, null, meta));
    }

    @Test
    public void providerCreatesTheHttpSessionWithoutOwningTheNetworkChannel() {
        // 协议会话借用 HTTP 通道，不在提供者阶段发送交换或关闭传输。
        ProtocolSessionContext context = mock(ProtocolSessionContext.class);
        NetworkChannel<HttpExchange> channel = mock(NetworkChannel.class);
        ObjLongConsumer<Payload> messages = (payload, requestId) -> {
        };
        ThriftHttpProtocol provider = new ThriftHttpProtocol();
        ProtocolSession<HttpExchange> session = provider.createSession(this.config(), context, channel, messages);

        // 建连仍由调用者通知，提供者只选择正确会话实现。
        assertTrue(session instanceof ThriftHttpSession);
        assertFalse(session.ready().isDone());
        verifyNoInteractions(channel);
    }

    private ProtocolConfig config() {
        return ProtocolConfig.newConfig("thrift-http", "thrift-http", Map.of("contextPath", "/rpc"));
    }
}
