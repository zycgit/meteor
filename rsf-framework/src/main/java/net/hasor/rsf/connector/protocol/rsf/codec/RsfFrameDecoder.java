/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf.codec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Reassembles bounded RSF frames from arbitrary byte chunks. */
public final class RsfFrameDecoder {
    private static final int    HEADER_SIZE = 13;
    private final        int    maxFrameSize;
    private              byte[] frame       = new byte[HEADER_SIZE];
    private              int    used;

    public RsfFrameDecoder(int maxFrameSize) {
        this.maxFrameSize = maxFrameSize;
    }

    /** Returns one complete frame, or null when more bytes are needed. */
    public byte[] read(ByteBuffer input) throws IOException {
        while (input.hasRemaining()) {
            int count = Math.min(this.frame.length - this.used, input.remaining());
            input.get(this.frame, this.used, count);
            this.used += count;
            if (this.used < this.frame.length) {
                continue;
            }

            if (this.frame.length == HEADER_SIZE) {
                int length = ((this.frame[10] & 255) << 16) | ((this.frame[11] & 255) << 8) | (this.frame[12] & 255);
                if (length == 0 || length > this.maxFrameSize - HEADER_SIZE) {
                    throw new IOException("Invalid RSF frame length: " + length);
                }
                this.frame = Arrays.copyOf(this.frame, length + HEADER_SIZE);
            } else {
                byte[] complete = this.frame;
                this.reset();
                return complete;
            }
        }
        return null;
    }

    public void reset() {
        this.frame = new byte[HEADER_SIZE];
        this.used = 0;
    }
}
