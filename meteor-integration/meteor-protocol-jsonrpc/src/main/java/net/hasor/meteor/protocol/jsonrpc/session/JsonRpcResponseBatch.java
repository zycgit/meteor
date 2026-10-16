/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.jsonrpc.session;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.protocol.jsonrpc.codec.JsonRpcEnvelope;

/** Collects a complete response document while keeping notification positions silent. */
final class JsonRpcResponseBatch {
    private final JsonRpcEnvelope                envelope;
    private final NetworkChannel<?>              channel;
    private final HttpExchange                   exchange;
    private final List<JsonNode>                 responses;
    private final Function<byte[], Future<Void>> writer;
    private final BasicFuture<Void>              sent = new BasicFuture<>();
    private       int                            remaining;

    JsonRpcResponseBatch(JsonRpcEnvelope envelope, NetworkChannel<?> channel, HttpExchange exchange, //
            Function<byte[], Future<Void>> writer) {
        this.envelope = envelope;
        this.channel = channel;
        this.exchange = exchange;
        this.remaining = envelope.calls().size();
        this.responses = new ArrayList<>(Collections.nCopies(this.remaining, null));
        this.writer = writer;
    }

    HttpExchange exchange() {
        return this.exchange;
    }

    NetworkChannel<?> channel() {
        return this.channel;
    }

    JsonRpcEnvelope envelope() {
        return this.envelope;
    }

    List<JsonNode> responses() {
        return this.responses;
    }

    BasicFuture<Void> sent() {
        return this.sent;
    }

    boolean complete(int index, JsonNode response) {
        this.responses.set(index, response);
        return --this.remaining == 0;
    }

    Future<Void> write(byte[] body) {
        return this.writer.apply(body);
    }
}
