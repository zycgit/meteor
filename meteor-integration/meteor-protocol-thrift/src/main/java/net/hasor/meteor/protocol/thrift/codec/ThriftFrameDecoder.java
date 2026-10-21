/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TProtocolException;

/** Incremental connection-local decoder for framed or unframed Thrift streams. */
public final class ThriftFrameDecoder {
    private final ThriftWireCodec      wire;
    private final boolean              framed;
    private final int                  maxMessageSize;
    private       ThriftMessageScanner scanner;
    private       byte[]               pending   = new byte[0];
    private       int                  size;
    private       int                  frameSize = -1;

    public ThriftFrameDecoder(ThriftWireCodec wire, boolean framed, int maxMessageSize) {
        this.wire = Objects.requireNonNull(wire, "wire");
        if (maxMessageSize <= 0 || maxMessageSize > Integer.MAX_VALUE - 4) {
            throw new IllegalArgumentException("Invalid Thrift maxMessageSize");
        }
        this.framed = framed;
        this.maxMessageSize = maxMessageSize;
        this.scanner = this.wire.newScanner();
    }

    public List<ThriftMessage> feed(byte[] bytes) throws TException {
        Objects.requireNonNull(bytes, "bytes");
        List<ThriftMessage> messages = new ArrayList<>();
        int offset = 0;
        while (offset < bytes.length) {
            int limit = this.framed && this.frameSize < 0 ? 4 : this.maxMessageSize;
            if (this.framed && this.frameSize >= 0) {
                limit = this.frameSize;
            }
            int count = Math.min(bytes.length - offset, limit - this.size);
            if (count > 0) {
                this.reserve(this.size + count, limit);
                System.arraycopy(bytes, offset, this.pending, this.size, count);
                this.size += count;
                offset += count;
            }
            if (this.framed) {
                this.readFrame(messages);
            } else {
                this.readUnframed(messages);
            }
        }
        return messages;
    }

    public void reset() {
        this.pending = new byte[0];
        this.size = 0;
        this.frameSize = -1;
        this.scanner = this.wire.newScanner();
    }

    private void readFrame(List<ThriftMessage> messages) throws TException {
        if (this.frameSize < 0) {
            if (this.size != 4) {
                return;
            }
            int length = (this.pending[0] & 0xff) << 24 | (this.pending[1] & 0xff) << 16 //
                    | (this.pending[2] & 0xff) << 8 | this.pending[3] & 0xff;
            if (length <= 0 || length > this.maxMessageSize) {
                throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Invalid Thrift frame length");
            }
            this.frameSize = length;
            this.size = 0;
            return;
        }
        if (this.size == this.frameSize) {
            messages.add(this.wire.decode(Arrays.copyOf(this.pending, this.size)));
            this.size = 0;
            this.frameSize = -1;
        }
    }

    private void readUnframed(List<ThriftMessage> messages) throws TException {
        int offset = 0;
        while (offset < this.size) {
            int consumed = this.scanner.scan(this.pending, offset, this.size - offset);
            if (consumed == 0) {
                break;
            }
            byte[] message = Arrays.copyOfRange(this.pending, offset, offset + consumed);
            messages.add(this.wire.decode(message));
            offset += consumed;
            this.scanner = this.wire.newScanner();
        }
        if (offset > 0) {
            System.arraycopy(this.pending, offset, this.pending, 0, this.size - offset);
            this.size -= offset;
        }
        if (this.size == this.maxMessageSize) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Thrift message exceeds maxMessageSize");
        }
    }

    private void reserve(int required, int limit) {
        if (required > this.pending.length) {
            int capacity = (int) Math.min(limit, Math.max(required, Math.max(64L, this.pending.length * 2L)));
            this.pending = Arrays.copyOf(this.pending, capacity);
        }
    }
}
