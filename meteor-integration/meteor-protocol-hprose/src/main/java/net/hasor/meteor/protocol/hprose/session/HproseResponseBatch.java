/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.session;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;

/** 在连接执行器中按请求顺序聚合响应，整个批次共享一次物理写入。 */
final class HproseResponseBatch {
    private final HttpExchange      exchange;
    private final byte[][]          responses;
    private final BasicFuture<Void> sent = new BasicFuture<>();
    private       int               remaining;

    HproseResponseBatch(HttpExchange exchange, int responseCount) {
        this.exchange = exchange;
        this.responses = new byte[responseCount][];
        this.remaining = responseCount;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    BasicFuture<Void> sent() {
        return this.sent;
    }

    byte[][] responses() {
        return this.responses;
    }

    boolean complete(int index, byte[] response) {
        if (this.responses[index] != null || this.sent.isDone()) {
            return false;
        }
        this.responses[index] = response;
        return --this.remaining == 0;
    }
}
