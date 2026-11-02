/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;

/** 编解码 gRPC 五字节帧头，并执行有界的 gzip 压缩与解压。 */
final class GrpcFrameCodec {
    private final int messageLimit;

    GrpcFrameCodec(int messageLimit) {
        this.messageLimit = messageLimit;
    }

    byte[] frame(byte[] message, String encoding) throws IOException {
        this.checkMessageSize(message.length, "gRPC message exceeds configured maxMessageSize");

        byte flag = 0;
        byte[] bytes = message;
        if (StringUtils.equals(encoding, "gzip")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(message);
            }
            bytes = buffer.toByteArray();
            flag = 1;
        }

        this.checkMessageSize(bytes.length, "Compressed gRPC message exceeds configured maxMessageSize");
        return ByteBuffer.allocate(5 + bytes.length).put(flag).putInt(bytes.length).put(bytes).array();
    }

    byte[] unframe(byte[] input, String encoding) throws IOException {
        if (input.length < 5) {
            throw new IOException("Truncated gRPC message prefix");
        }

        ByteBuffer frame = ByteBuffer.wrap(input);
        byte compressed = frame.get();
        long length = Integer.toUnsignedLong(frame.getInt());
        this.checkMessageSize(length, "gRPC message exceeds configured maxMessageSize");
        if (length != frame.remaining()) {
            throw new IOException("Expected exactly one gRPC message");
        }

        byte[] message = new byte[(int) length];
        frame.get(message);
        return this.decompress(message, compressed, encoding);
    }

    byte[] readMessage(InputStream input, String encoding) throws IOException {
        int compressed = input.read();
        if (compressed == -1) {
            return null;
        }

        byte[] size = input.readNBytes(4);
        if (size.length != 4) {
            throw new IOException("Truncated gRPC message prefix");
        }

        long length = Integer.toUnsignedLong(ByteBuffer.wrap(size).getInt());
        this.checkMessageSize(length, "gRPC message exceeds configured maxMessageSize");
        byte[] bytes = input.readNBytes((int) length);
        if (bytes.length != length) {
            throw new IOException("Truncated gRPC message");
        }
        return this.decompress(bytes, compressed, encoding);
    }

    private byte[] decompress(byte[] message, int compressed, String encoding) throws IOException {
        if (compressed == 0) {
            return message;
        }
        if (compressed != 1) {
            throw new IOException("Invalid gRPC compression flag");
        }
        if (!StringUtils.equalsIgnoreCase("gzip", encoding)) {
            throw new GrpcStatusException(12, "Unsupported gRPC message compression: " + encoding, null, null);
        }

        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(message))) {
            byte[] expanded = gzip.readNBytes(this.messageLimit + 1);
            if (expanded.length > this.messageLimit) {
                String messageTooLarge = "Decompressed gRPC message exceeds configured maxMessageSize";
                throw new GrpcStatusException(8, messageTooLarge, null, null);
            }
            return expanded;
        }
    }

    private void checkMessageSize(long length, String message) {
        if (length > this.messageLimit) {
            throw new GrpcStatusException(8, message, null, null);
        }
    }
}
