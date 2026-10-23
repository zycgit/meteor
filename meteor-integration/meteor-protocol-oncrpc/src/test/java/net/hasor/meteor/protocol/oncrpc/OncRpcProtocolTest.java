/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc;
import java.nio.ByteBuffer;
import java.util.Arrays;
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

/** TCP SPI 入口及不消费输入的 Record Marking 前缀选择。 */
public class OncRpcProtocolTest {
    @Test
    public void providerDeclaresOncRpcOverTcp() {
        // 固定的 SPI 身份、传输和序列化器组成一个 TCP 协议入口。
        OncRpcProtocol provider = new OncRpcProtocol();
        assertEquals("oncrpc", provider.name());
        assertEquals("tcp", provider.transportType());
        assertEquals("oncrpc", provider.defaultSerialize());
        assertSame(byte[].class, provider.messageType());
    }

    @Test
    public void serviceLoaderDiscoversRootTcpProvider() {
        // 正式 SPI 文件必须指向协议根包中的入口。
        ProtocolProvider<?> provider = ServiceLoader.load(ProtocolProvider.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(OncRpcProtocol.class::isInstance) //
                .findFirst().orElseThrow();
        assertEquals("oncrpc", provider.name());
    }

    @Test
    public void providerCreatesUnconnectedSessionWithoutOwningChannel() {
        // 提供者只组装会话，借用的网络通道尚未收到建连事件。
        ProtocolSessionContext context = mock(ProtocolSessionContext.class);
        NetworkChannel<byte[]> channel = mock(NetworkChannel.class);
        ObjLongConsumer<Payload> messages = (payload, requestId) -> {
        };

        // 创建不抢先发包、关闭通道或宣称会话就绪。
        ProtocolSession<byte[]> session = new OncRpcProtocol().createSession( //
                this.config(Map.of()), context, channel, messages);
        assertTrue(session instanceof OncRpcSession);
        assertFalse(session.ready().isDone());
        verifyNoInteractions(channel);
    }

    @Test
    public void emptyInputWaitsForRecordMarker() {
        // TCP 输入尚未包含片头，后续网络块仍可能补齐。
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), new byte[0]);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void partialRecordMarkerWaitsForRemainingBytes() {
        // 四字节片头本身也允许跨网络块。
        byte[] partialMarker = { (byte) 0x80, 0, 0 };
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), partialMarker);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void recordMarkerWithoutEnvelopeWaitsForBody() {
        // 片长有效并不代表已经能识别其中的 RPC 消息。
        byte[] prefix = ByteBuffer.allocate(4).putInt(0x80000028).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void partialEnvelopeWaitsForRpcVersionWord() {
        // 已收到 XID 与方向，剩余的版本字仍在后续网络块中。
        byte[] prefix = ByteBuffer.allocate(12).putInt(0x80000028).putInt(17).putInt(0).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void completeEnvelopeSelectsBeforeCompleteRecordArrives() {
        // 路由只需要信封头，不能等待完整的参数正文。
        byte[] prefix = this.recordPrefix(40, 17, 0, 2);
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void rpcVersionMismatchStillReachesProtocolDecoder() {
        // 不匹配的 RPC 版本必须由会话回复标准 RPC_MISMATCH。
        byte[] prefix = this.recordPrefix(40, 17, 0, 99);
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void replyEnvelopeDoesNotClaimInboundCallRoute() {
        // 主动连接已固定路由；共享入站监听只根据 CALL 选择此协议。
        byte[] prefix = this.recordPrefix(40, 17, 1, 0);
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void unknownMessageTypeDoesNotClaimInboundRoute() {
        // 任意方向值不能伪装为一个合法 CALL 信封。
        byte[] prefix = this.recordPrefix(40, 17, 8, 2);
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void envelopeCanSpanRecordFragments() {
        // 首片只有 XID；第二片补齐方向和 RPC 版本。
        byte[] prefix = ByteBuffer.allocate(20) //
                .putInt(4).putInt(17) //
                .putInt(0x80000024).putInt(0).putInt(2).array();

        // 片头本身不计入拼接后的信封正文。
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void envelopeWordCanSpanRecordFragments() {
        // XID 的最后两个字节与其余信封放入第二个片段。
        byte[] envelope = ByteBuffer.allocate(12).putInt(17).putInt(0).putInt(2).array();
        byte[] prefix = ByteBuffer.allocate(20) //
                .putInt(2).put(envelope, 0, 2) //
                .putInt(0x80000026).put(envelope, 2, 10).array();

        // 路由应先拼接字节，再解释方向字段。
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void emptyNonfinalFragmentsDoNotEndRecord() {
        // 多个空的非终片之后仍允许跟随实际 RPC 信封。
        byte[] prefix = ByteBuffer.allocate(24) //
                .putInt(0).putInt(0).putInt(0x80000028) //
                .putInt(17).putInt(0).putInt(2).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void finalEmptyFragmentRejectsIncompleteEnvelope() {
        // 终片已经结束却没有信封，不应继续等待新记录。
        byte[] prefix = ByteBuffer.allocate(4).putInt(Integer.MIN_VALUE).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void finalShortEnvelopeRejectsIncompleteCall() {
        // 正文只含 XID 与方向，终片表明 RPC 版本不会再到达。
        byte[] prefix = ByteBuffer.allocate(12).putInt(0x80000008).putInt(17).putInt(0).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void partialNextFragmentMarkerStillNeedsMoreData() {
        // 非终片已经结束，后续片头只到达两个字节。
        byte[] prefix = ByteBuffer.allocate(10).putInt(4).putInt(17).putShort((short) 0x8000).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.NEED_MORE, result);
    }

    @Test
    public void fragmentOverMessageLimitRejectsRoute() {
        // 读取信封之前依据声明片长拒绝超限记录。
        ProtocolConfig config = this.config(Map.of("maxMessageSize", "16"));
        byte[] prefix = this.recordPrefix(17, 17, 0, 2);
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(config, prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void fragmentAtMessageLimitCanSelectRoute() {
        // 上限本身属于合法值，剩余正文由会话继续校验。
        ProtocolConfig config = this.config(Map.of("maxMessageSize", "16"));
        byte[] prefix = this.recordPrefix(16, 17, 0, 2);
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(config, prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void accumulatedFragmentLengthsCannotExceedMessageLimit() {
        // 各片单独未超限，累计长度仍要受同一个记录上限约束。
        ProtocolConfig config = this.config(Map.of("maxMessageSize", "12"));
        byte[] prefix = ByteBuffer.allocate(20) //
                .putInt(4).putInt(17) //
                .putInt(0x80000009).putInt(0).putInt(2).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(config, prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void fragmentCountLimitRejectsAnUnresolvedRecord() {
        // 空片不能无限延长共享入口的协议探测。
        ProtocolConfig config = this.config(Map.of("maxRecordFragments", "2"));
        byte[] prefix = ByteBuffer.allocate(8).putInt(0).putInt(0).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(config, prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void finalAllowedFragmentCanSelectRoute() {
        // 片数恰好达到上限且信封已明确时仍能选择协议。
        ProtocolConfig config = this.config(Map.of("maxRecordFragments", "2"));
        byte[] prefix = ByteBuffer.allocate(20) //
                .putInt(4).putInt(17) //
                .putInt(0x80000024).putInt(0).putInt(2).array();
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(config, prefix);
        assertSame(RouteProbeResult.MATCH, result);
    }

    @Test
    public void rsfPrefixDoesNotClaimOncRpcRoute() {
        // RSF/1 的 0x91 开头不能被误认为合法 ONC RPC 记录长度。
        byte[] prefix = { (byte) 0x91, 0, 0, 40, 0, 0, 0, 17, 0, 0, 0, 0, 0, 0, 0, 2 };
        RouteProbeResult result = new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertSame(RouteProbeResult.REJECT, result);
    }

    @Test
    public void probingLeavesInputBytesUnchanged() {
        // 探测后的原始前缀还要原样交付协议会话。
        byte[] prefix = this.recordPrefix(40, 17, 0, 2);
        byte[] original = Arrays.copyOf(prefix, prefix.length);
        new OncRpcProtocol().probeProtocol(this.config(Map.of()), prefix);
        assertArrayEquals(original, prefix);
    }

    @Test
    public void publicPublisherValidatesAnnotatedOncRpcContract() throws Exception {
        // 初始化协议挂载，但不打开网络监听。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (MetContext context = configuration.buildContext()) {
            // 正式发布入口触发根提供者的同步契约检查。
            MetBindInfo<NativeContract> service = context.publisher().rsfService(NativeContract.class) //
                    .protocol("oncrpc").register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertEquals(service.getBindID(), registered.getBindID());
        }
    }

    @Test
    public void publicPublisherSkipsMissingOncRpcProgram() throws Exception {
        // 普通接口缺少 ONC RPC 契约时只拒绝此协议，服务登记仍然保留。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class) //
                    .protocol("oncrpc").register();

            // 被拒绝的协议没有发布元信息或可达端点。
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertNotNull(registered);
            assertEquals(service.getBindID(), registered.getBindID());
            assertNull(MetBindInfoWrap.unwrap(service).getProtocolMeta("oncrpc"));
        }
    }

    @Test
    public void unrelatedProtocolDeclarationSkipsOncRpcValidation() throws Exception {
        // 相同容器也允许只经 RSF 发布的普通接口。
        MetConfiguration configuration = new MetConfiguration().setDefaultProtocol("oncrpc");
        configuration.connector("tcp").protocol("oncrpc");
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            // 协议筛选不应强迫未选择 ONC RPC 的服务提供其程序定义。
            MetBindInfo<Runnable> service = context.publisher().rsfService(Runnable.class) //
                    .protocol("rsf").register();
            MetBindInfo<?> registered = context.getServiceInfo(service.getBindID());
            assertEquals(service.getBindID(), registered.getBindID());
        }
    }

    private ProtocolConfig config(Map<String, String> options) {
        return ProtocolConfig.newConfig("oncrpc", "oncrpc", options);
    }

    private byte[] recordPrefix(int length, int xid, int type, int rpcVersion) {
        return ByteBuffer.allocate(16).putInt(length | Integer.MIN_VALUE) //
                .putInt(xid).putInt(type).putInt(rpcVersion).array();
    }

    @OncRpcProgram(id = 0x20000077, version = 1)
    public interface NativeContract {
        @OncRpcProcedure(id = 1)
        int increment(int value);
    }
}
