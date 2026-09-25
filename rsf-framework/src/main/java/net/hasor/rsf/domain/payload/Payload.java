/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain.payload;
import net.hasor.rsf.domain.OptionInfo;

/** Common message contract for connector subscriptions. */
public abstract class Payload extends OptionInfo {
    public enum Type {
        REQUEST,
        RESPONSE,
        THROW
    }

    private final Type type;

    protected Payload(Type type) {
        this.type = type;
    }

    public final Type getType() {
        return this.type;
    }
}
