/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpResponse;

/** 在连接执行器中按请求顺序聚合响应，整个批次共享一次物理写入。 */
final class HproseBatch {
    private final HttpExchange      exchange;
    private final HproseInvocation  invocation;
    private final byte[][]          responses;
    private final BasicFuture<Void> sent = new BasicFuture<>();
    private       int               remaining;

    public HproseBatch(HttpExchange exchange, HproseInvocation invocation) {
        this.exchange = exchange;
        this.invocation = invocation;
        this.responses = new byte[invocation.calls().size()][];
        this.remaining = this.responses.length;
    }

    public HttpExchange exchange() {
        return this.exchange;
    }

    public BasicFuture<Void> sent() {
        return this.sent;
    }

    public boolean accept(int index, byte[] response) throws IOException {
        if (this.responses[index] != null || this.sent.isDone()) {
            return false;
        }
        this.responses[index] = response;
        if (--this.remaining != 0) {
            return false;
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] item : this.responses) {
            body.write(item);
        }
        body.write('z');
        HttpResponse result = HttpResponse.newBuffered(200, this.invocation.headers(), body.toByteArray(), Map.of());
        return this.exchange.respond(result);
    }
}