/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain.payload;
import java.util.Objects;

/** A local failure notification, preserving the original exception and its cause chain. */
public final class ThrowPayload extends Payload {
    private final Throwable throwable;

    public ThrowPayload(Throwable throwable) {
        super(Type.THROW);
        this.throwable = Objects.requireNonNull(throwable, "throwable");
    }

    public Throwable getThrowable() {
        return this.throwable;
    }
}
