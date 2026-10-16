/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.websocket;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** 独立于网络缓冲区生命周期的完整 WebSocket 文本或二进制消息。 */
public final class WebSocketPayload {
    private final boolean text;
    private final byte[]  bytes;

    private WebSocketPayload(boolean text, byte[] bytes) {
        this.text = text;
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    public static WebSocketPayload newText(String text) {
        return new WebSocketPayload(true, text.getBytes(StandardCharsets.UTF_8));
    }

    public static WebSocketPayload newBinary(byte[] bytes) {
        return new WebSocketPayload(false, bytes);
    }

    public boolean isText() {
        return this.text;
    }

    public byte[] bytes() {
        return this.bytes.clone();
    }

    public String text() {
        if (!this.text) {
            throw new IllegalStateException("This WebSocket message is binary");
        }
        return new String(this.bytes, StandardCharsets.UTF_8);
    }
}
