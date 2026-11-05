/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.List;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.connector.ProtocolProvider;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.domain.ProtocolBinding;

/** HTTP protocol SPI; requests select protocol mounts by their URI path. */
public interface HttpProtocolProvider extends ProtocolProvider<HttpExchange> {
    /** 按协议固定顺序返回已准备绑定的路由身份分量；空列表表示不参与名称冲突校验。 */
    List<String> serviceKey(MetBindInfo<?> service, ProtocolBinding binding);

    @Override
    default String transportType() {
        return "http";
    }

    @Override
    default Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }
}