/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Decodes successive padded Base64 segments without assuming HTTP or gRPC frame boundaries. */
final class GrpcWebBase64InputStream extends InputStream {
    private final InputStream input;
    private final byte[]      encoded = new byte[8192];
    private final byte[]      decoded = new byte[3];
    private       int         encodedOffset;
    private       int         encodedAvailable;
    private       int         offset;
    private       int         available;

    GrpcWebBase64InputStream(InputStream input) {
        this.input = input;
    }

    @Override
    public int read() throws IOException {
        if (this.offset == this.available && !this.quartet()) {
            return -1;
        }
        return this.decoded[this.offset++] & 0xff;
    }

    @Override
    public int read(byte[] bytes, int start, int length) throws IOException {
        Objects.checkFromIndexSize(start, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        if (this.offset == this.available && !this.quartet()) {
            return -1;
        }
        int count = Math.min(length, this.available - this.offset);
        System.arraycopy(this.decoded, this.offset, bytes, start, count);
        this.offset += count;
        return count;
    }

    private boolean quartet() throws IOException {
        int first = this.encodedByte();
        if (first == -1) {
            return false;
        }

        // Padding 只结束当前 Base64 片段，不代表整个 HTTP 正文结束。
        int second = this.encodedByte();
        int third = this.encodedByte();
        int fourth = this.encodedByte();
        if (second == -1 || third == -1 || fourth == -1) {
            throw new IOException("Truncated gRPC-Web Base64 quartet");
        }
        int a = this.digit(first);
        int b = this.digit(second);
        this.decoded[0] = (byte) ((a << 2) | (b >>> 4));
        this.offset = 0;

        // 根据 padding 保留一至三个有效字节，并校验未使用位必须为零。
        if (third == '=') {
            if (fourth != '=' || (b & 15) != 0) {
                throw new IOException("Invalid gRPC-Web Base64 padding");
            }
            this.available = 1;
        } else {
            int c = this.digit(third);
            this.decoded[1] = (byte) ((b << 4) | (c >>> 2));
            if (fourth == '=') {
                if ((c & 3) != 0) {
                    throw new IOException("Invalid gRPC-Web Base64 padding");
                }
                this.available = 2;
            } else {
                int d = this.digit(fourth);
                this.decoded[2] = (byte) ((c << 6) | d);
                this.available = 3;
            }
        }
        return true;
    }

    private int encodedByte() throws IOException {
        // 按块读取底层流，quartet 可以跨越任意网络分片。
        while (this.encodedOffset == this.encodedAvailable) {
            int count = this.input.read(this.encoded);
            if (count == -1) {
                return -1;
            }
            this.encodedOffset = 0;
            this.encodedAvailable = count;
        }
        return this.encoded[this.encodedOffset++] & 0xff;
    }

    private int digit(int character) throws IOException {
        if (character >= 'A' && character <= 'Z') {
            return character - 'A';
        }
        if (character >= 'a' && character <= 'z') {
            return character - 'a' + 26;
        }
        if (character >= '0' && character <= '9') {
            return character - '0' + 52;
        }
        if (character == '+') {
            return 62;
        }
        if (character == '/') {
            return 63;
        }
        throw new IOException("Invalid gRPC-Web Base64 character");
    }
}
