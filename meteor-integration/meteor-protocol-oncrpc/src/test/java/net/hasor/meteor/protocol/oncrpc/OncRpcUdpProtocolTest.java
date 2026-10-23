/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProcedure;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProgram;
import net.hasor.meteor.protocol.oncrpc.session.OncRpcSession;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** UDP SPI 入口及完整数据报的协议前缀选择。 */
public class OncRpcUdpProtocolTest {
    @Test
    public void providerDeclaresOncRpcOverUdp() {
        // UDP 入口独立选择传输，但仍复用 ONC RPC 序列化契约。
        OncRpcUdpProtocol provider = new OncRpcUdpProtocol();
        assertEquals("oncrpc-udp", provider.name());
        assertEquals("udp", provider.transportType());
        assertEquals("oncrpc", provider.defaultSerialize());
        assertSame(byte[].class, provider.messageType());
    }

    @Test
    public void serviceLoaderDiscoversRootUdpProvider() {
        // 插件使用正式 SPI 描述同时提供 UDP 根入口。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(OncRpcUdpProtocol.class::isInstance) //
                .findFirst().orElseThrow();
        assertEquals("oncrpc-udp", provider.name());
    }

    @Test
    public void providerCreatesUnconnectedDatagramSessionWithoutWriting() {
        // 数据报会话仍等待其借用通道的建连通知。
        ProtocolSessionContext context = mock(ProtocolSessionContext.class);
        NetworkChannel<byte[]> channel = mock(NetworkChannel.class);
        ObjLongConsumer<Payload> messages = (payload, requestId) -> {
        };

        // 提供者只选择会话实现，不能抢先发送重传或关闭通道。
        ProtocolSession<byte[]> session = new OncRpcUdpProtocol().createSession( //
                this.config(Map.of()), context, channel, messages);
        assertTrue(session instanceof OncRpcSession);
        assertFalse(session.ready().isDone());
        verifyNoInteractions(channel);
    }

    @Test
    public void emptyDatagramRejectsWithoutWaitingForAnotherPacket() {
        // UDP 报文已经完整结束，不能借下一包补齐信封。
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), new byte[0]);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void shortDatagramRejectsWithoutNeedMoreResult() {
        // 只差版本字最后一字节的报文同样是不完整数据报。
        byte[] datagram = new byte[11];
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), datagram);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void callHeaderSelectsUdpRoute() {
        // UDP 正文从 XID 开始，没有 TCP Record Marking 片头。
        byte[] datagram = this.callPrefix(17, 0, 2);
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), datagram);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void rpcVersionMismatchStillReachesUdpDecoder() {
        // 版本错误交由协议层产生标准拒绝响应。
        byte[] datagram = this.callPrefix(17, 0, 7);
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), datagram);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void unsolicitedReplyDoesNotSelectInboundUdpRoute() {
        // 已有出站挂载负责回包，共享入站选择只接收 CALL。
        byte[] datagram = this.callPrefix(17, 1, 0);
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), datagram);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void unknownDirectionDoesNotSelectInboundUdpRoute() {
        // 不认识的信封方向不能抢占同端口的其他协议。
        byte[] datagram = this.callPrefix(17, 9, 2);
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), datagram);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void datagramOverConfiguredSizeRejectsRoute() {
        // 数据报长度按完整消息限制检查，不读取超限正文。
        ProtocolConfig config = this.config(Map.of("maxMessageSize", "12"));
        byte[] datagram = new byte[13];
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(config, datagram);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void datagramAtConfiguredSizeCanSelectRoute() {
        // 最大长度本身合法，完整结构仍由会话解码器负责。
        ProtocolConfig config = this.config(Map.of("maxMessageSize", "12"));
        byte[] datagram = this.callPrefix(17, 0, 2);
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(config, datagram);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void tcpRecordMarkerIsNotAnExtraUdpHeader() {
        // 普通非零 XID 的 TCP 记录直接发送到 UDP 不能得到相同的 CALL 位置。
        byte[] datagram = ByteBuffer.allocate(16) //
                .putInt(0x8000000c).putInt(17).putInt(0).putInt(2).array();
        RouteProbeResult result = new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), datagram);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void probingLeavesDatagramUnchanged() {
        // 路由选择只观察原始数据报，不消费或覆盖后续解码的字节。
        byte[] datagram = this.callPrefix(17, 0, 2);
        byte[] original = datagram.clone();
        new OncRpcUdpProtocol().probeProtocol(this.config(Map.of()), datagram);
        assertArrayEquals(original, datagram);
    }

    @Test
    public void publicPublisherValidatesUdpServiceContract() throws Exception {
        // 只有传输变化，程序号和过程号仍在正式发布入口检查。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").protocol("oncrpc-udp");
        try (MetContext context = configuration.buildContext()) {
            // 不启动监听就能验证 SPI 提供者参与服务登记。
            MetBindInfo<NativeContract> service = context.publisher().rsfService(NativeContract.class) //
                    .protocol("oncrpc-udp").register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertEquals(service.getBindID(), registered.getBindID());
        }
    }

    @Test
    public void publicPublisherSkipsUnsupportedUdpService() throws Exception {
        // 普通接口缺少 ONC RPC 契约时只拒绝此协议，服务登记仍然保留。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").protocol("oncrpc-udp");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class) //
                    .protocol("oncrpc-udp").register();

            // 被拒绝的协议没有发布元信息或可达端点。
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            assertNull(MetBindInfoWrap.unwrap(service).getProtocolMeta("oncrpc-udp"));
        }
    }

    @Test
    public void unrelatedServiceSkipsUdpContractValidation() throws Exception {
        // 同一个应用可以保留只通过其他协议发布的普通服务。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc-udp");
        configuration.connector("udp").protocol("oncrpc-udp");
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            // 未选择 ONC RPC 的声明不需要补充其程序或过程注解。
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class) //
                    .protocol("rsf").register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertEquals(service.getBindID(), registered.getBindID());
        }
    }

    private ProtocolConfig config(Map<String, String> options) {
        return ProtocolConfig.newConfig("oncrpc-udp", "oncrpc-udp", options);
    }

    private byte[] callPrefix(int xid, int type, int rpcVersion) {
        return ByteBuffer.allocate(12).putInt(xid).putInt(type).putInt(rpcVersion).array();
    }

    @OncRpcProgram(id = 0x20000078, version = 1)
    public interface NativeContract {
        @OncRpcProcedure(id = 1)
        int increment(int value);
    }
}
