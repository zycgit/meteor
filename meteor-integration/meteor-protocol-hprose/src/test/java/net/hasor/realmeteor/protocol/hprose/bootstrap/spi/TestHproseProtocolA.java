/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap.spi;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hprose.HproseProtocol;

/** 使用独立 SPI 名称验证共享 HTTP 挂载，编解码仍委托真实 Hprose 实现。 */
public class TestHproseProtocolA implements ProtocolFactory<HttpExchange> {
    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        // 此别名通过挂载路径选择，不参与内容探测。
        return RouteProbeResult.REJECT;
    }

    public String name() {
        return "hprosea";
    }

    public String defaultSerializeType() {
        return "Hprose";
    }

    public String transportType() {
        return "http";
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        // 测试别名不附加服务约束，沿用真实 Hprose 会话的行为。
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, //
            ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, //
            ObjLongConsumer<Payload> messages) {
        // 只替换 SPI 身份，消息收发交给插件正式实现。
        return new HproseProtocol().createSession(config, context, channel, messages);
    }
}
