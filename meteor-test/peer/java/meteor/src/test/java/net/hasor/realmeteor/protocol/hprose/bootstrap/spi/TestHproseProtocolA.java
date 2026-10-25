/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.hprose.bootstrap.spi;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.HttpProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.protocol.hprose.HproseProtocol;

/** 使用独立 SPI 名称验证共享 HTTP 挂载，编解码仍委托真实 Hprose 实现。 */
public class TestHproseProtocolA implements HttpProtocolProvider {
    public String name() {
        return "hprosea";
    }

    public String defaultSerialize() {
        return "Hprose";
    }

    @Override
    public ProtocolBinding prepareBinding(ProtocolConfig config, MetContext context, ProtocolMeta meta) {
        // 别名入口同样需要准备真实 Hprose 操作表，供正式会话查找方法。
        return new HproseProtocol().prepareBinding(config, context, meta);
    }

    public ProtocolSession<HttpExchange> createSession(ProtocolConfig config, //
            ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> channel, //
            ObjLongConsumer<Payload> messages) {
        // 只替换 SPI 身份，消息收发交给插件正式实现。
        return new HproseProtocol().createSession(config, context, channel, messages);
    }
}
