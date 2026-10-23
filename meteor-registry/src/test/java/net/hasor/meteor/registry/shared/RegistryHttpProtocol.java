/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
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

/** 注册目录场景使用的 HTTP 挂载；只声明协议元信息，不提供业务报文编解码。 */
public final class RegistryHttpProtocol implements HttpProtocolProvider {
    @Override
    public String name() {
        return "registry-http";
    }

    @Override
    public String defaultSerialize() {
        return "Java";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        MetBindInfo<?> service = meta.getService();
        if (StringUtils.equals(service.getBindName(), config.option("rejectService", null))) {
            return null;
        }
        return meta.build(config, null, meta.createServiceDescription(config, this.defaultSerialize()));
    }

    @Override
    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, ObjLongConsumer<Payload> messages) {
        throw new UnsupportedOperationException("Registry metadata fixture does not exchange business messages");
    }
}
