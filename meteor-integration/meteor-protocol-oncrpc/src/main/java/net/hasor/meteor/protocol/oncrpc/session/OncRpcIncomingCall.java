/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.session;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.oncrpc.codec.OncRpcMessage;

/** 入站调用与有限重复应答缓存共用身份，活跃调用不会被容量淘汰。 */
final class OncRpcIncomingCall {
    final OncRpcMessage message;
    final byte[]        bytes;
    RequestPayload request;
    byte[]         reply;
    long           expiresAt;
    int            pendingWrites;

    OncRpcIncomingCall(OncRpcMessage message, byte[] bytes) {
        this.message = message;
        this.bytes = bytes.clone();
    }
}
