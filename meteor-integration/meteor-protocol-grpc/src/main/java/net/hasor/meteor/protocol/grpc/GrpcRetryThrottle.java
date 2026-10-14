/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
/** 按目标地址共享令牌，成功恢复令牌，超过失败阈值暂停后续尝试。 */
final class GrpcRetryThrottle {
    private final double maximum;
    private final double ratio;
    private       double tokens;

    GrpcRetryThrottle(double maximum, double ratio) {
        this.maximum = maximum;
        this.ratio = ratio;
        this.tokens = maximum;
    }

    synchronized boolean failed() {
        this.tokens = Math.max(0, this.tokens - 1);
        return this.tokens > this.maximum / 2;
    }

    synchronized boolean allowsRetry() {
        return this.tokens > this.maximum / 2;
    }

    synchronized void succeeded() {
        this.tokens = Math.min(this.maximum, this.tokens + this.ratio);
    }
}
