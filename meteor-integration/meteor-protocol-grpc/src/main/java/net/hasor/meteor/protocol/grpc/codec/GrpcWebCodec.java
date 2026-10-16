/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;

/** Converts bounded gRPC-Web envelopes to native gRPC frames; callers own stream lifecycles. */
public final class GrpcWebCodec {
    private final int messageLimit;
    private final int metadataLimit;

    public GrpcWebCodec(ProtocolConfig config) {
        this.messageLimit = option(config, "maxMessageSize", 4 * 1024 * 1024);
        this.metadataLimit = option(config, "maxMetadataSize", 64 * 1024);
    }

    public static boolean accepts(String contentType) {
        String type = mediaType(contentType);
        return matches(type, "application/grpc-web") || matches(type, "application/grpc-web-text");
    }

    public static boolean isText(String contentType) {
        return matches(mediaType(contentType), "application/grpc-web-text");
    }

    /** Preserve the serialization suffix; the native protocol validates registered codecs. */
    public String grpcContentType(String contentType) throws IOException {
        String type = mediaType(contentType);
        if (!accepts(type)) {
            throw new IOException("Invalid gRPC-Web content type: " + contentType);
        }
        String prefix = isText(type) ? "application/grpc-web-text" : "application/grpc-web";
        String suffix = type.substring(prefix.length());
        if (suffix.isEmpty()) {
            suffix = "+proto";
        }
        return "application/grpc" + suffix;
    }

    public String webContentType(String contentType, boolean text) throws IOException {
        String type = mediaType(contentType);
        String nativePrefix = "application/grpc";
        if (!matches(type, nativePrefix)) {
            throw new IOException("Invalid native gRPC content type: " + contentType);
        }
        String suffix = type.substring(nativePrefix.length());
        if (suffix.isEmpty()) {
            suffix = "+proto";
        }
        String prefix = text ? "application/grpc-web-text" : "application/grpc-web";
        return prefix + suffix;
    }

    public byte[] encodeRequest(byte[] frames, boolean text) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        this.encodeRequest(new ByteArrayInputStream(frames), output, text);
        return output.toByteArray();
    }

    public byte[] decodeRequest(byte[] body, boolean text) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        this.decodeRequest(new ByteArrayInputStream(body), output, text);
        return output.toByteArray();
    }

    public byte[] encodeResponse(byte[] frames, Map<String, List<String>> trailers, boolean text) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        this.encodeResponse(new ByteArrayInputStream(frames), output, text, () -> trailers);
        return output.toByteArray();
    }

    /** gRPC-Web unary input must finish successfully before the caller dispatches the native request. */
    public void decodeRequest(InputStream input, OutputStream output, boolean text) throws IOException {
        // 重建原生 data 帧，同时要求 Web 请求正文完整结束。
        InputStream wire = text ? new GrpcWebBase64InputStream(input) : input;
        byte[] frame = this.frame(wire, false);
        if (frame == null) {
            throw new IOException("gRPC-Web request must contain one message");
        }
        if (wire.read() != -1) {
            throw new IOException("gRPC-Web request must contain exactly one message");
        }

        // 完整校验通过后再向原生会话交付，避免无效尾随数据触发业务调用。
        output.write(frame);
    }

    public void encodeRequest(InputStream input, OutputStream output, boolean text) throws IOException {
        // Web 请求只接受一个完整的原生消息。
        byte[] frame = this.frame(input, false);
        if (frame == null) {
            throw new IOException("gRPC-Web request must contain one message");
        }
        if (input.read() != -1) {
            throw new IOException("gRPC-Web request must contain exactly one message");
        }

        // 文本模式仅增加 Base64 外层，保留既有消息压缩标记。
        this.write(output, frame, text);
    }

    /** Each data frame is flushed independently; trailers are obtained only after native EOF. */
    public void encodeResponse(InputStream input, OutputStream output, boolean text, //
            Supplier<Map<String, List<String>>> trailers) throws IOException {
        // 逐帧交付正文，让服务端流无需等待整个 RPC 结束。
        byte[] frame;
        while ((frame = this.frame(input, false)) != null) {
            this.write(output, frame, text);
        }

        // 原生正文 EOF 后取得最终元数据，并追加唯一终止帧。
        byte[] metadata = this.trailerBytes(trailers.get());
        ByteBuffer ending = ByteBuffer.allocate(5 + metadata.length);
        ending.put((byte) 0x80).putInt(metadata.length).put(metadata);
        this.write(output, ending.array(), text);
    }

    /** Decode data incrementally and return the final trailer block after checking body EOF. */
    public Map<String, List<String>> decodeResponse(InputStream input, OutputStream output, boolean text, //
            String grpcEncoding) throws IOException {
        InputStream wire = text ? new GrpcWebBase64InputStream(input) : input;
        byte[] frame;
        while ((frame = this.frame(wire, true)) != null) {
            int flag = frame[0] & 0xff;
            if (flag < 0x80) {
                output.write(frame);
                output.flush();
                continue;
            }

            // 尾帧是终止帧；无 data 的错误响应同样走这一分支。
            byte[] metadata = this.metadata(frame, grpcEncoding);
            Map<String, List<String>> trailers = this.parseTrailers(metadata);
            if (wire.read() != -1) {
                throw new IOException("gRPC-Web trailers must be the final frame");
            }
            return trailers;
        }
        throw new IOException("Missing gRPC-Web trailer frame");
    }

    private byte[] frame(InputStream input, boolean response) throws IOException {
        int flag = input.read();
        if (flag == -1) {
            return null;
        }
        boolean data = flag == 0 || flag == 1;
        boolean trailer = response && (flag == 0x80 || flag == 0x81);
        if (!data && !trailer) {
            throw new IOException("Invalid gRPC-Web frame flag: " + flag);
        }

        // 先校验无符号帧长度，再分配正文缓冲区。
        byte[] prefix = input.readNBytes(4);
        if (prefix.length != 4) {
            throw new IOException("Truncated gRPC-Web frame prefix");
        }
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(prefix).getInt());
        int limit = trailer ? this.metadataLimit : this.messageLimit;
        this.limit(length, limit);
        byte[] payload = input.readNBytes((int) length);
        if (payload.length != length) {
            throw new IOException("Truncated gRPC-Web frame payload");
        }
        return ByteBuffer.allocate(5 + payload.length).put((byte) flag).put(prefix).put(payload).array();
    }

    private byte[] metadata(byte[] frame, String encoding) throws IOException {
        ByteArrayInputStream bytes = new ByteArrayInputStream(frame, 5, frame.length - 5);
        if (frame[0] == (byte) 0x80) {
            return bytes.readAllBytes();
        }
        if (!StringUtils.equalsIgnoreCase("gzip", encoding)) {
            throw new GrpcStatusException(12, "Unsupported gRPC-Web trailer compression: " + encoding, null, null);
        }

        // 压缩正文与解压结果都遵守元数据上限。
        try (GZIPInputStream gzip = new GZIPInputStream(bytes)) {
            byte[] expanded = gzip.readNBytes(this.metadataLimit + 1);
            this.limit(expanded.length, this.metadataLimit);
            return expanded;
        }
    }

    private Map<String, List<String>> parseTrailers(byte[] bytes) throws IOException {
        // 按 HTTP 字段行解析，保留重复应用元数据的原有顺序。
        String block = new String(bytes, StandardCharsets.US_ASCII);
        String[] lines = block.split("\\r\\n", -1);
        Map<String, List<String>> trailers = new LinkedHashMap<>();
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isEmpty() && index == lines.length - 1) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 1) {
                throw new IOException("Invalid gRPC-Web trailer field");
            }
            String name = line.substring(0, colon);
            int start = colon + 1;
            while (start < line.length() && this.ows(line.charAt(start))) {
                start++;
            }
            int end = line.length();
            while (end > start && this.ows(line.charAt(end - 1))) {
                end--;
            }
            String value = line.substring(start, end);
            this.field(name, value);
            trailers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }

        // 状态字段必须唯一，交出的字段列表与解析中的可变列表分离。
        this.status(trailers);
        Map<String, List<String>> result = new LinkedHashMap<>();
        trailers.forEach((name, values) -> result.put(name, List.copyOf(values)));
        return result;
    }

    private byte[] trailerBytes(Map<String, List<String>> input) throws IOException {
        // 尾帧字段名统一小写，每增加一项就检查整个字段块的大小。
        Map<String, List<String>> trailers = new LinkedHashMap<>();
        StringBuilder block = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : input.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            for (String value : entry.getValue()) {
                this.field(name, value);
                trailers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
                long size = (long) block.length() + name.length() + value.length() + 4;
                this.limit(size, this.metadataLimit);
                block.append(name).append(": ").append(value).append("\r\n");
            }
        }

        // 大小写归一化后再验证，避免不同拼写掩盖重复状态字段。
        this.status(trailers);
        return block.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private void field(String name, String value) throws IOException {
        if (!name.matches("[0-9a-z_.-]+")) {
            throw new IOException("Invalid gRPC-Web trailer name: " + name);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 || character > 126) {
                throw new IOException("gRPC-Web trailer values must contain printable ASCII");
            }
        }
    }

    private boolean ows(char character) {
        return character == ' ' || character == '\t';
    }

    private void status(Map<String, List<String>> trailers) throws IOException {
        // grpc-status 必须是唯一、有效的标准数字状态。
        List<String> status = trailers.get("grpc-status");
        if (status == null || status.size() != 1 || !status.get(0).matches("[0-9]+")) {
            throw new IOException("gRPC-Web trailers require one grpc-status");
        }
        try {
            int code = Integer.parseInt(status.get(0));
            if (code > 16) {
                throw new IOException("Invalid gRPC-Web status code");
            }
        } catch (NumberFormatException failure) {
            throw new IOException("Invalid gRPC-Web status code", failure);
        }

        // 状态说明与错误详情也是单值字段，应用元数据仍可重复。
        for (String name : List.of("grpc-message", "grpc-status-details-bin")) {
            List<String> values = trailers.get(name);
            if (values != null && values.size() != 1) {
                throw new IOException("Duplicate gRPC-Web status field: " + name);
            }
        }
    }

    private void write(OutputStream output, byte[] frame, boolean text) throws IOException {
        byte[] bytes = text ? Base64.getEncoder().encode(frame) : frame;
        output.write(bytes);
        output.flush();
    }

    private void limit(long size, int maximum) {
        if (size > maximum) {
            throw new GrpcStatusException(8, "gRPC-Web frame exceeds its configured size limit", null, null);
        }
    }

    private static String mediaType(String contentType) {
        if (contentType == null) {
            return "";
        }
        int separator = contentType.indexOf(';');
        String type = separator == -1 ? contentType : contentType.substring(0, separator);
        return type.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(String type, String prefix) {
        if (StringUtils.equals(type, prefix)) {
            return true;
        }
        if (!type.startsWith(prefix + "+")) {
            return false;
        }
        String suffix = type.substring(prefix.length() + 1);
        return suffix.matches("[a-z0-9][a-z0-9._-]*");
    }

    private static int option(ProtocolConfig config, String name, int fallback) {
        String configured = config.options().get(name);
        if (configured == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(configured);
            if (value > 0 && value < Integer.MAX_VALUE - 5) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 统一使用包含选项名的参数错误。
        }
        throw new IllegalArgumentException(name + " must be a positive bounded integer");
    }
}
