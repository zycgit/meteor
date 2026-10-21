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
import java.util.function.ObjLongConsumer;
import net.hasor.example.protocol.thrift.ThriftDemoService;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.thrift.session.ThriftHttpSession;
import org.junit.Test;
import static org.junit.Assert.*;
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
    public void mountedHttpRoutesDoNotProbeTheRequestBody() {
        // 挂载目录负责选择 HTTP 协议，正文标识不能抢占其他路由。
        HttpRequest request = HttpRequest.newBuffered("POST", "/rpc/demo", Map.of(), //
                new byte[] { (byte) 0x80, 1, 0, 1 }, 1, Map.of());
        HttpExchange exchange = new HttpExchange(request);

        // 原生 Thrift 正文也不使 HTTP 提供者参与字节探测。
        assertSame(RouteProbeResult.REJECT, new ThriftHttpProtocol().probe(this.config(), exchange));
    }

    @Test
    public void providerValidatesTheExplicitGeneratedResourceForHttp() {
        // 业务接口通过本地资源绑定 IDL，HTTP 提供者复用同一发布约束。
        ServiceDomain<ThriftDemoService> service = new ServiceDomain<>(ThriftDemoService.class);
        service.setBindName("demo");
        service.setBindResource(new ThriftServiceResource(ExtendedInteropService.class));

        // context 只属于 HTTP 挂载，与生成字段元数据相互独立。
        ProtocolConfig config = this.config();
        ProtocolMeta meta = new ProtocolMeta(service, null, Map.of());
        ProtocolBinding binding = new ThriftHttpProtocol().validate(config, meta, null);
        assertSame(service.getBindResource(), binding.getDefinition());
    }

    @Test
    public void providerRejectsHttpServiceWithoutAThriftContract() {
        // HTTP transport 不能为没有 IDL 的普通方法推测字段编号。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindName("missing-idl");
        ThriftHttpProtocol provider = new ThriftHttpProtocol();
        ProtocolConfig config = this.config();

        // 缺少 IDL 仅拒绝 HTTP Thrift，不中断整个服务登记。
        ProtocolMeta meta = new ProtocolMeta(service, null, Map.of());
        assertNull(provider.validate(config, meta, null));
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
