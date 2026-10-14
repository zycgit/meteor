/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;

/**
 * One HTTP exchange, independent of application request IDs and RPC deadlines.
 */
public final class HttpExchange {
    private final HttpRequest               request;
    private final BasicFuture<HttpResponse> response   = new BasicFuture<>();
    private final BasicFuture<Void>         completion = new BasicFuture<>();

    public HttpExchange(HttpRequest request) {
        this.request = request;
        this.trackBody(request.stream());
    }

    public HttpRequest request() {
        return this.request;
    }

    public Future<HttpResponse> response() {
        return this.response;
    }

    public boolean respond(HttpResponse response) {
        if (!this.response.completed(response)) {
            return false;
        } else {
            this.trackBody(response.stream());
            return true;
        }
    }

    public Future<Void> completion() {
        return this.completion;
    }

    public void complete(Throwable cause) {
        if (cause == null) {
            this.completion.completed(null);
        } else {
            this.completion.failed(cause);
            this.response.failed(cause);
        }
    }

    private void trackBody(HttpBody body) {
        if (body != null) {
            // 即使尚未入队或尚未建立物理连接，失败也必须释放正文的生产者/消费者。
            this.completion.onFailed(done -> body.fail(done.getCause()));
            body.completion().onFailed(done -> this.complete(done.getCause()));
        }
    }
}
