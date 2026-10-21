/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift;
import java.nio.ByteBuffer;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.thrift.codec.ThriftCodec;
import net.hasor.meteor.protocol.thrift.session.ThriftSession;

/** Thrift TCP 入口；分帧及编码由本插件处理，TLS 由传输配置提供。 */
public final class ThriftProtocol implements ProtocolProvider<byte[]> {
    @Override
    public String name() {
        return "thrift";
    }

    @Override
    public String transportType() {
        return "tcp";
    }

    @Override
    public String defaultSerialize() {
        return "thrift";
    }

    @Override
    public Class<byte[]> messageType() {
        return byte[].class;
    }

    @Override
    public ProtocolBinding validate(ProtocolConfig config, ProtocolMeta meta, MetContext context) {
        ThriftServiceResource definition = ThriftCodec.validateService(config, meta.getService());
        return definition == null ? null : config.build(definition);
    }

    @Override
    public RouteProbeResult probe(ProtocolConfig config, byte[] prefix) {
        boolean framed = Boolean.parseBoolean(config.option("framed", "true"));
        int offset = 0;
        if (framed) {
            // 长度头尚未到齐时保留路由判断，不将后续协议正文当成新连接。
            if (prefix.length < Integer.BYTES) {
                return RouteProbeResult.NEED_MORE;
            }
            int length = ByteBuffer.wrap(prefix).getInt();
            if (length <= 0 || length > config.integer("maxMessageSize", 8 * 1024 * 1024)) {
                return RouteProbeResult.REJECT;
            }
            offset = Integer.BYTES;
        }
        if (prefix.length == offset) {
            return RouteProbeResult.NEED_MORE;
        }

        // 严格 Binary、Compact、JSON 以及非版本化 Binary 的消息名前缀。
        int first = prefix[offset] & 0xff;
        boolean thrift = first == 0x80 || first == 0x82 || first == '[' || first == 0;
        return thrift ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> channel, ObjLongConsumer<Payload> messages) {
        return new ThriftSession(config, context, channel, messages);
    }
}
