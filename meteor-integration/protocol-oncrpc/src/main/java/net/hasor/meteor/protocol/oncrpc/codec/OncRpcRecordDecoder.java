/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Incremental RFC 5531 record marking for a single ordered TCP connection. */
public final class OncRpcRecordDecoder {
    private final int                   maxMessageSize;
    private final int                   maxFragments;
    private final ByteArrayOutputStream record;
    private       int                   header;
    private       int                   headerBytes;
    private       int                   fragmentRemaining = -1;
    private       boolean               lastFragment;
    private       int                   fragmentCount;

    public OncRpcRecordDecoder(int maxMessageSize, int maxFragments) {
        if (maxMessageSize < 1 || maxFragments < 1) {
            throw new IllegalArgumentException("ONC RPC message and fragment limits must be positive");
        }
        this.maxMessageSize = maxMessageSize;
        this.maxFragments = maxFragments;
        this.record = new ByteArrayOutputStream(Math.min(maxMessageSize, 4096));
    }

    public List<byte[]> feed(byte[] data) {
        List<byte[]> messages = new ArrayList<>();
        int offset = 0;
        try {
            while (offset < data.length) {
                // 四字节标记可跨任意网络分片，只累计尚缺少的头部字节。
                if (this.fragmentRemaining < 0) {
                    while (this.headerBytes < 4 && offset < data.length) {
                        this.header = (this.header << 8) | (data[offset++] & 0xff);
                        this.headerBytes++;
                    }
                    if (this.headerBytes < 4) {
                        break;
                    }
                    this.beginFragment();
                }

                // 正文只复制一次；完成最后一个 fragment 后交付整个 record。
                int count = Math.min(this.fragmentRemaining, data.length - offset);
                this.record.write(data, offset, count);
                this.fragmentRemaining -= count;
                offset += count;
                if (this.fragmentRemaining == 0) {
                    if (this.lastFragment) {
                        messages.add(this.record.toByteArray());
                        this.record.reset();
                        this.fragmentCount = 0;
                    }
                    this.fragmentRemaining = -1;
                }
            }
            return messages;
        } catch (IllegalArgumentException malformed) {
            this.reset();
            throw malformed;
        }
    }

    private void beginFragment() {
        int length = this.header & 0x7fff_ffff;
        if (length > this.maxMessageSize - this.record.size()) {
            throw new IllegalArgumentException("ONC RPC record exceeds the configured message size");
        }
        this.fragmentCount++;
        if (this.fragmentCount > this.maxFragments) {
            throw new IllegalArgumentException("ONC RPC record exceeds the configured fragment count");
        }
        this.fragmentRemaining = length;
        this.lastFragment = this.header < 0;
        this.header = 0;
        this.headerBytes = 0;
    }

    public void reset() {
        this.record.reset();
        this.header = 0;
        this.headerBytes = 0;
        this.fragmentRemaining = -1;
        this.lastFragment = false;
        this.fragmentCount = 0;
    }

    /** Wrap one RPC message into record fragments; a zero-byte last fragment is valid record marking. */
    public static byte[] encodeRecord(byte[] message, int fragmentSize) {
        if (fragmentSize < 1) {
            throw new IllegalArgumentException("ONC RPC fragment size must be positive");
        }
        int fragments = 1;
        if (message.length > 0) {
            fragments += (message.length - 1) / fragmentSize;
        }
        long encodedSize = message.length + fragments * 4L;
        if (encodedSize > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Encoded ONC RPC record is too large");
        }

        // Record marker 的高位表示最后一片，其余 31 位表示当前正文长度。
        byte[] data = new byte[(int) encodedSize];
        int source = 0;
        int target = 0;
        for (int index = 0; index < fragments; index++) {
            int length = Math.min(fragmentSize, message.length - source);
            int marker = length;
            if (index == fragments - 1) {
                marker |= 0x8000_0000;
            }
            data[target++] = (byte) (marker >>> 24);
            data[target++] = (byte) (marker >>> 16);
            data[target++] = (byte) (marker >>> 8);
            data[target++] = (byte) marker;
            System.arraycopy(message, source, data, target, length);
            source += length;
            target += length;
        }
        return data;
    }
}
