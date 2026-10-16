/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 编码过程中限制完整 RPC 正文，避免先创建超过上限的缓冲区。 */
final class HessianBoundedOutputStream extends OutputStream {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final int                   limit;

    HessianBoundedOutputStream(int limit) {
        this.limit = limit;
    }

    @Override
    public void write(int value) throws IOException {
        this.check(1);
        this.bytes.write(value);
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
        this.check(length);
        this.bytes.write(source, offset, length);
    }

    byte[] bytes() {
        return this.bytes.toByteArray();
    }

    private void check(int count) throws IOException {
        if ((long) this.bytes.size() + count > this.limit) {
            throw new IOException("Hessian body exceeds maxMessageSize");
        }
    }
}
