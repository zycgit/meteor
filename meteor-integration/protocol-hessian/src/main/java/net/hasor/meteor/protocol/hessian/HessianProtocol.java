/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian;
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
import net.hasor.meteor.protocol.hessian.codec.HessianCodec;
import net.hasor.meteor.protocol.hessian.codec.HessianServiceBinding;
import net.hasor.meteor.protocol.hessian.session.HessianSession;

/** Hessian RPC 协议入口，按 HTTP 挂载点发布普通 Java 服务。 */
public final class HessianProtocol implements HttpProtocolProvider {
    @Override
    public String name() {
        return "hessian";
    }

    @Override
    public String defaultSerialize() {
        return "Hessian";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        HessianServiceBinding definition = HessianCodec.prepareService(meta.getService());
        if (definition == null) {
            return null;
        }
        ObjectNode description = meta.createServiceDescription(config, this.defaultSerialize());
        definition.describeContract(description);
        return meta.build(config, definition, description);
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context,//
            NetworkChannel<HttpExchange> channel, ObjLongConsumer<Payload> messages) {
        return new HessianSession(config, context, channel, messages);
    }
}
