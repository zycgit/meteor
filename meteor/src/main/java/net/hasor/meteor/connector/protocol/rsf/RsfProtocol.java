/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
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
import net.hasor.meteor.domain.meta.MethodMeta;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;

/**
 * RSF/1 message adapter, including its own request/response correlation.
 */
public final class RsfProtocol implements TcpProtocolProvider {
    public String name() {
        return "rsf";
    }

    @Override
    public String defaultSerialize() {
        return "Json";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        // RSF 线上操作名沿用 Java 方法名，描述与本次接受的服务保持一致。
        Map<Method, String> methods = new LinkedHashMap<>();
        for (MethodMeta method : meta.getServiceMeta().getMethods()) {
            if (!method.getJavaMethod().isBridge() && !method.getJavaMethod().isSynthetic()) {
                methods.put(method.getJavaMethod(), method.getJavaName());
            }
        }
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize(), methods);
        return meta.build(config, null, description);
    }

    @Override
    public RouteProbeResult probeProtocol(ProtocolConfig config, byte[] prefix) {
        if (prefix.length == 0) {
            return RouteProbeResult.NEED_MORE;
        } else {
            int frameType = prefix[0] & 0xff;
            boolean request = frameType == 0x91;
            boolean message = frameType == 0xa1;
            boolean response = frameType == 0xb1;
            return request || message || response ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
        }
    }

    @Override
    public ProtocolSession<byte[]> createSession(ProtocolConfig config, ProtocolSessionContext manager, //
            NetworkChannel<byte[]> connection, ObjLongConsumer<Payload> messages) {
        return new RsfSession(config, manager, connection, messages);
    }
}
