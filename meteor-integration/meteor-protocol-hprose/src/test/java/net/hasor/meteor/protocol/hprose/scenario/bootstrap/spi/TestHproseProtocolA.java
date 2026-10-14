/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.bootstrap.spi;
import java.util.function.BiConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hprose.HproseProtocol;

/** 使用独立 SPI 名称验证共享 HTTP 挂载，编解码仍委托真实 Hprose 实现。 */
public class TestHproseProtocolA implements ProtocolFactory<HttpExchange> {
    @Override
    public String scheme() {
        return this.name();
    }

    @Override
    public RouteMatch probe(ProtocolConfig config, HttpExchange message) {
        // 此别名通过挂载路径选择，不参与内容探测。
        return RouteMatch.REJECT;
    }

    public String name() {
        return "hprosea";
    }

    public String defaultSerializeType() {
        return "Hprose";
    }

    public String transport() {
        return "http";
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        // 测试别名不附加服务约束，沿用真实 Hprose 会话的行为。
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    public ProtocolSession<HttpExchange> create(ProtocolConfig config, //
            ProtocolContext context, //
            NetworkChannel<HttpExchange> channel, //
            BiConsumer<Long, Payload> messages) {
        // 只替换 SPI 身份，消息收发交给插件正式实现。
        return new HproseProtocol().create(config, context, channel, messages);
    }
}
