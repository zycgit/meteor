/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc;
import java.nio.ByteBuffer;
import java.util.function.ObjLongConsumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.udp.UdpProtocolProvider;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcMessageMapper;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceBinding;
import net.hasor.meteor.protocol.oncrpc.session.OncRpcSession;

/** ONC RPC UDP 入口；一个数据报承载一条 RPC，重传与去重归协议会话。 */
public final class OncRpcUdpProtocol implements UdpProtocolProvider {
    @Override
    public String name() {
        return "oncrpc-udp";
    }

    @Override
    public String defaultSerialize() {
        return "oncrpc";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        OncRpcServiceBinding definition = OncRpcMessageMapper.prepareServiceDefinition(config, meta);
        if (definition == null) {
            return null;
        }

        // 从本端点已准备的契约导出描述，再一次创建完整绑定。
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize());
        definition.describeContract(description);
        return meta.build(config, definition, description);
    }

    @Override
    public RouteProbeResult probeProtocol(ProtocolConfig config, byte[] datagram) {
        // UDP 不带 Record Marking；短数据报已经结束，不等待下一包补齐。
        if (datagram.length < 12 || datagram.length > config.integer("maxMessageSize", 8 * 1024 * 1024)) {
            return RouteProbeResult.REJECT;
        }
        int type = ByteBuffer.wrap(datagram, Integer.BYTES, Integer.BYTES).getInt();
        return type == 0 ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
        return new OncRpcSession(config, context, connection, messages, true);
    }
}
