/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;

/** Encodes one RFC 4506 value; compose nested values through input.read/output.write to apply depth limits. */
public interface XdrCodec<T> {
    T read(XdrInput input);

    void write(XdrOutput output, T value);
}
