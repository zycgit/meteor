/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo;
import java.lang.reflect.Method;
import java.util.Map;
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
import net.hasor.meteor.protocol.dubbo.codec.DubboCodec;
import net.hasor.meteor.protocol.dubbo.session.DubboSession;

/** Dubbo 2.x 与 3.x 共用的二进制协议入口，安全连接复用 TCP 传输配置。 */
public final class DubboProtocol implements TcpProtocolProvider {
    @Override
    public String name() {
        return "dubbo";
    }

    @Override
    public String defaultSerialize() {
        return "Hessian2";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        Map<Method, String> methods = DubboCodec.prepareService(meta.getService());
        if (methods == null) {
            return null;
        }
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize(), methods);
        return meta.build(config, methods, description);
    }

    @Override
    public RouteProbeResult probeProtocol(ProtocolConfig config, byte[] prefix) {
        if (prefix.length == 0) {
            return RouteProbeResult.NEED_MORE;
        }
        if ((prefix[0] & 0xff) != 0xda) {
            return RouteProbeResult.REJECT;
        }
        if (prefix.length == 1) {
            return RouteProbeResult.NEED_MORE;
        }
        return (prefix[1] & 0xff) == 0xbb ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
    }

    @Override
    public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<byte[]> channel, ObjLongConsumer<Payload> messages) {
        return new DubboSession(config, context, channel, messages);
    }
}
