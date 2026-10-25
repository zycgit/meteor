/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.jsonrpc;
import java.io.IOException;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 独立 JDK WebSocket 对端只收集完整文本，不复制插件的 JSON-RPC 编解码逻辑。 */
final class JsonRpcWebSocketPeer implements WebSocket.Listener {
    private final BlockingQueue<Object> received = new LinkedBlockingQueue<>();
    private final StringBuilder         text     = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
        webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        // JDK 可能分段交付一条文本消息，只有完整消息才进入场景的接收队列。
        this.text.append(data);
        if (last) {
            this.received.add(this.text.toString());
            this.text.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        this.received.add(error);
    }

    String nextMessage() throws Exception {
        Object message = this.received.poll(5, TimeUnit.SECONDS);
        if (message instanceof Throwable) {
            throw new IOException("WebSocket peer failed", (Throwable) message);
        }
        if (message == null) {
            throw new IOException("WebSocket peer did not receive a message");
        }
        return (String) message;
    }
}
