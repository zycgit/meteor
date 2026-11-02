/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;

/** Triple 消息的五字节帧头与有界压缩，借用的输入流由调用方关闭。 */
final class TripleFrameCodec {
    private final int maximum;

    TripleFrameCodec(int maximum) {
        if (maximum < 1) {
            throw new IllegalArgumentException("Triple maxMessageSize must be positive");
        }
        this.maximum = maximum;
    }

    byte[] frame(byte[] message, String encoding) throws IOException {
        // 消息正文与压缩后的线协议正文均受同一大小限制。
        byte[] encoded = this.compress(message, encoding);
        byte compressed = (byte) (this.identity(encoding) ? 0 : 1);
        long frameLength = (long) encoded.length + 5;
        if (frameLength > Integer.MAX_VALUE) {
            throw this.failure(8, "Triple frame exceeds supported array size");
        }
        return ByteBuffer.allocate((int) frameLength).put(compressed).putInt(encoded.length).put(encoded).array();
    }

    byte[] readMessage(InputStream input, String encoding) throws IOException {
        // 只在帧边界将 EOF 视为流结束；半帧必须报告协议错误。
        int flag = input.read();
        if (flag == -1) {
            return null;
        }
        if (flag != 0 && flag != 1) {
            throw this.failure(13, "Invalid Triple compression flag");
        }
        if (!this.identity(encoding)) {
            this.requireGzip(encoding);
        }
        byte[] prefix = input.readNBytes(4);
        if (prefix.length != 4) {
            throw this.failure(13, "Truncated Triple message prefix");
        }

        // 无符号长度先校验，再转为数组长度，避免越界分配。
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(prefix).getInt());
        this.checkLength(length);
        byte[] message = input.readNBytes((int) length);
        if (message.length != length) {
            throw this.failure(13, "Truncated Triple message");
        }
        if (flag == 0) {
            return message;
        }
        if (this.identity(encoding)) {
            throw this.failure(13, "Compressed Triple message requires grpc-encoding");
        }
        return this.decompress(message, encoding);
    }

    byte[] unframe(byte[] input, String encoding) throws IOException {
        ByteArrayInputStream stream = new ByteArrayInputStream(input);
        byte[] message = this.readMessage(stream, encoding);
        if (message == null || stream.available() != 0) {
            throw this.failure(13, "Expected exactly one Triple message");
        }
        return message;
    }

    byte[] compress(byte[] message, String encoding) throws IOException {
        this.check(message);
        if (this.identity(encoding)) {
            return message;
        }
        this.requireGzip(encoding);

        // 仅关闭当前方法创建的 gzip 与缓冲区。
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(message);
        }
        byte[] compressed = buffer.toByteArray();
        this.check(compressed);
        return compressed;
    }

    byte[] decompress(byte[] message, String encoding) throws IOException {
        this.check(message);
        if (this.identity(encoding)) {
            return message;
        }
        this.requireGzip(encoding);

        // 逐块累计解压后的大小，不能依据压缩包声明预先分配正文。
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(message))) {
            ByteArrayOutputStream expanded = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            long length = 0;
            while ((count = gzip.read(chunk)) != -1) {
                length += count;
                this.checkLength(length);
                expanded.write(chunk, 0, count);
            }
            return expanded.toByteArray();
        } catch (IOException failure) {
            GrpcStatusException malformed = this.failure(13, "Invalid Triple gzip message");
            malformed.initCause(failure);
            throw malformed;
        }
    }

    void check(byte[] message) {
        this.checkLength(message.length);
    }

    private void checkLength(long length) {
        if (length > this.maximum) {
            throw this.failure(8, "Triple message exceeds configured maxMessageSize");
        }
    }

    private boolean identity(String encoding) {
        return encoding == null || encoding.isEmpty() || StringUtils.equalsIgnoreCase("identity", encoding);
    }

    private void requireGzip(String encoding) {
        if (!StringUtils.equalsIgnoreCase("gzip", encoding)) {
            throw this.failure(12, "Unsupported Triple message compression: " + encoding);
        }
    }

    private GrpcStatusException failure(int code, String description) {
        return new GrpcStatusException(code, description, null, null);
    }
}
