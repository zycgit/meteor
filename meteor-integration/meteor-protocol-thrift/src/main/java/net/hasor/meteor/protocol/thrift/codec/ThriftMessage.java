/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.util.Objects;

/** A validated Thrift message and its complete, unframed wire representation. */
public record ThriftMessage(String encoding, String methodName, byte messageType, int sequenceId, byte[] data) {
    public ThriftMessage(String encoding, String methodName, byte messageType, int sequenceId, byte[] data) {
        this.encoding = Objects.requireNonNull(encoding, "encoding");
        this.methodName = Objects.requireNonNull(methodName, "methodName");
        this.messageType = messageType;
        this.sequenceId = sequenceId;
        this.data = Objects.requireNonNull(data, "data").clone();
    }

    @Override
    public byte[] data() {
        return this.data.clone();
    }
}
