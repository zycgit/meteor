/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.websocket;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;

/** 构建 WebSocket 握手，业务消息继续使用 Meteor 的 NetworkChannel 契约。 */
public final class WebSocketHandshakeUtils {
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebSocketHandshakeUtils() {
    }

    public static HttpExchange newExchange(String path, int httpVersion) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String method;
        if (httpVersion == 1) {
            byte[] key = new byte[16];
            RANDOM.nextBytes(key);
            headers.put("Connection", List.of("Upgrade"));
            headers.put("Upgrade", List.of("websocket"));
            headers.put("Sec-WebSocket-Key", List.of(Base64.getEncoder().encodeToString(key)));
            method = "GET";
        } else if (httpVersion == 2) {
            headers.put(":protocol", List.of("websocket"));
            method = "CONNECT";
        } else {
            throw new IllegalArgumentException("WebSocket requires HTTP/1 or HTTP/2");
        }
        headers.put("Sec-WebSocket-Version", List.of("13"));
        HttpRequest request = HttpRequest.newBuffered(method, path, headers, new byte[0], httpVersion, Map.of());
        return new HttpExchange(request);
    }
}
