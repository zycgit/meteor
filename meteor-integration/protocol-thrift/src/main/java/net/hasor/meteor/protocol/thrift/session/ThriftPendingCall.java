/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.session;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 出站调用保留独立的线上序号、内核请求和已经提交的写入。 */
final class ThriftPendingCall {
    final int               sequenceId;
    final RequestPayload    request;
    final BasicFuture<Void> sent = new BasicFuture<>();
    Future<Void> writing;

    ThriftPendingCall(int sequenceId, RequestPayload request) {
        this.sequenceId = sequenceId;
        this.request = request;
    }
}
