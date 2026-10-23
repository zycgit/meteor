/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ObjLongConsumer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolSession;
import net.hasor.meteor.connector.ProtocolSessionContext;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodec;
import net.hasor.meteor.protocol.hprose.session.HproseSession;

/** Hprose SPI provider using the same session contract as every other protocol. */
public final class HproseProtocol implements HttpProtocolProvider {
    private final Map<ProtocolConfig, HproseHttpCodec> codecs = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "hprose";
    }

    @Override
    public String defaultSerialize() {
        return "Hprose";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        Map<Method, String> methods = HproseHttpCodec.prepareService(meta.getService());
        if (methods == null) {
            return null;
        }
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize(), methods);
        return meta.build(config, methods, description);
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        HproseHttpCodec codec = this.codecs.computeIfAbsent(config, current -> {
            return new HproseHttpCodec(current, context.routes(), context.metContext());
        });
        return new HproseSession(context, channel, messages, codec);
    }
}
