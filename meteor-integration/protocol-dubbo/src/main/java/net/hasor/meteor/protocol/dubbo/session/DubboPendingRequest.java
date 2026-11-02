/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.session;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 同一出站请求的 RPC 生命周期和已经提交的网络写入。 */
final class DubboPendingRequest {
    final RequestPayload    request;
    final BasicFuture<Void> sent = new BasicFuture<>();
    Future<Void> writing;

    DubboPendingRequest(RequestPayload request) {
        this.request = request;
    }
}
