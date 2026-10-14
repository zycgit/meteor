/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.util.Set;

/** 一个方法的显式重试或对冲策略，两者互斥。 */
final class GrpcMethodPolicy {
    private final int          attempts;
    private final boolean      hedging;
    private final long         initialDelay;
    private final long         maxDelay;
    private final double       multiplier;
    private final Set<Integer> statuses;

    GrpcMethodPolicy(int attempts, boolean hedging, long initialDelay, long maxDelay, double multiplier, Set<Integer> statuses) {
        this.attempts = attempts;
        this.hedging = hedging;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.statuses = Set.copyOf(statuses);
    }

    int attempts() {
        return this.attempts;
    }

    boolean hedging() {
        return this.hedging;
    }

    long initialDelay() {
        return this.initialDelay;
    }

    long maxDelay() {
        return this.maxDelay;
    }

    double multiplier() {
        return this.multiplier;
    }

    boolean retryable(int status) {
        return this.statuses.contains(status);
    }
}
