/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.session;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 一次出站调用的线上身份、固定重传正文和当前调度。 */
final class OncRpcPendingCall {
    final long              xid;
    final RequestPayload    request;
    final byte[]            bytes;
    final BasicFuture<Void> sent = new BasicFuture<>();
    Future<Void> writing;
    Cancellable  retry;
    long         retryDelay;
    int          retries;

    OncRpcPendingCall(long xid, RequestPayload request, byte[] bytes, long retryDelay) {
        this.xid = xid;
        this.request = request;
        this.bytes = bytes;
        this.retryDelay = retryDelay;
    }

    void cancelRetry() {
        if (this.retry != null) {
            this.retry.cancel();
            this.retry = null;
        }
    }
}
