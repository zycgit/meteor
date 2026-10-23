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
import net.hasor.meteor.connector.transport.tcp.TcpProtocolProvider;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcMessageMapper;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceBinding;
import net.hasor.meteor.protocol.oncrpc.session.OncRpcSession;

/** ONC RPC TCP 入口；Record Marking 归协议会话，安全传输归端点。 */
public final class OncRpcProtocol implements TcpProtocolProvider {
    @Override
    public String name() {
        return "oncrpc";
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
    public RouteProbeResult probeProtocol(ProtocolConfig config, byte[] prefix) {
        int maxMessageSize = config.integer("maxMessageSize", 8 * 1024 * 1024);
        int maxFragments = config.integer("maxRecordFragments", 1024);
        byte[] header = new byte[12];
        int copied = 0;
        int offset = 0;
        long total = 0;

        // 只收集信封头，不等待完整正文；片头与信封都允许跨任意 TCP 分片。
        for (int fragment = 0; fragment < maxFragments; fragment++) {
            if (prefix.length - offset < Integer.BYTES) {
                return RouteProbeResult.NEED_MORE;
            }
            int marker = ByteBuffer.wrap(prefix, offset, Integer.BYTES).getInt();
            int length = marker & Integer.MAX_VALUE;
            offset += Integer.BYTES;
            total += length;
            if (total > maxMessageSize) {
                return RouteProbeResult.REJECT;
            }

            int available = Math.min(length, prefix.length - offset);
            int count = Math.min(available, header.length - copied);
            System.arraycopy(prefix, offset, header, copied, count);
            copied += count;
            if (copied == header.length) {
                // RPC 版本不匹配仍交协议层产生标准 RPC_MISMATCH，不能在路由层丢弃。
                int type = ByteBuffer.wrap(header, Integer.BYTES, Integer.BYTES).getInt();
                return type == 0 ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
            }
            if (available < length) {
                return RouteProbeResult.NEED_MORE;
            }
            if (marker < 0) {
                return RouteProbeResult.REJECT;
            }
            offset += length;
        }
        return RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
        return new OncRpcSession(config, context, connection, messages, false);
    }
}
