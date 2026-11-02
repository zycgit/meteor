/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
import java.io.ByteArrayOutputStream;

/** 按协议正文上限写入，避免先生成超限文档再检查。 */
final class DubboBoundedOutputStream extends ByteArrayOutputStream {
    private final int maximum;

    DubboBoundedOutputStream(int maximum) {
        this.maximum = maximum;
    }

    @Override
    public synchronized void write(int value) {
        this.check(1);
        super.write(value);
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        this.check(length);
        super.write(bytes, offset, length);
    }

    private void check(int length) {
        if (length > this.maximum - this.count) {
            throw new IllegalArgumentException("Dubbo body exceeds configured maxFrameSize");
        }
    }
}
