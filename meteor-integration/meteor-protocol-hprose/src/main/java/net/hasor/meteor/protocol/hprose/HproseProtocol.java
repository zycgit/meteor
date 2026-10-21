/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.function.ObjLongConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hprose.codec.HproseHttpCodec;
import net.hasor.meteor.protocol.hprose.session.HproseSession;

/** Hprose SPI provider using the same session contract as every other protocol. */
public final class HproseProtocol implements ProtocolProvider<HttpExchange> {
    private final Map<ProtocolConfig, HproseHttpCodec> codecs = new ConcurrentHashMap<>();

    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    @Override
    public String name() {
        return "hprose";
    }

    @Override
    public String defaultSerialize() {
        return "Hprose";
    }

    @Override
    public String transportType() {
        return "http";
    }

    @Override
    public ProtocolBinding validate(ProtocolConfig config, ProtocolMeta meta, MetContext context) {
        MetBindInfo<?> service = meta.getService();
        // Hprose 按别名、方法名和参数数量定位调用，不支持同数量重载或流式签名。
        if (StringUtils.isBlank(service.getAliasName(this.defaultSerialize()))) {
            return null;
        }

        Set<String> signatures = new HashSet<>();
        for (Method method : MetRuntimeUtils.getServiceMethods(service.getBindType())) {
            String signature = method.getName().toLowerCase(Locale.ROOT) + "/" + method.getParameterCount();
            if (!signatures.add(signature) || Flow.Publisher.class.isAssignableFrom(method.getReturnType())) {
                return null;
            }

            for (Class<?> parameter : method.getParameterTypes()) {
                if (Flow.Publisher.class.isAssignableFrom(parameter)) {
                    return null;
                }
            }
        }

        return config.build(null);
    }

    @Override
    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
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
