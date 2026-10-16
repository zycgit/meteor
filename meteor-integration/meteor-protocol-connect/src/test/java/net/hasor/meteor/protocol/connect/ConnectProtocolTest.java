/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.ProtocolFactory;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ConnectProtocolTest {
    @Test
    public void spiDiscoversTheConnectHttpContract() {
        // 使用生产 SPI 资源发现协议，核对正式组装入口与默认编码。
        ProtocolFactory<?> factory = ServiceLoader.load(ProtocolFactory.class).stream() //
                .map(ServiceLoader.Provider::get) //
                .filter(value -> StringUtils.equals("connect", value.name())) //
                .findFirst() //
                .orElse(null);

        // 传输与消息契约由插件声明，应用只选择已注册的名称。
        assertNotNull(factory);
        assertEquals(ConnectProtocol.class, factory.getClass());
        assertEquals("http", factory.transportType());
        assertEquals("Protobuf", factory.defaultSerializeType());
        assertEquals(HttpExchange.class, factory.messageType());
    }

    @Test
    public void protocolSelectionUsesTheConfiguredMount() {
        // HTTP 路径已决定挂载，不通过正文抢占其它协议的请求。
        ProtocolConfig config = ProtocolConfig.newConfig("connect", "connect", Map.of());
        HttpRequest request = HttpRequest.newBuffered("POST", "/example.Echo/Call", //
                Map.of("content-type", List.of("application/json")), new byte[0], 1, Map.of());

        // 尚未选择挂载时，入口不会把一般 JSON 请求自动识别成 Connect。
        assertEquals(RouteProbeResult.REJECT, new ConnectProtocol().probe(config, new HttpExchange(request)));
    }
}
