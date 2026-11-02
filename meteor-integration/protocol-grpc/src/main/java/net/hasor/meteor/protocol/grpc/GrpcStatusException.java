/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.IOException;
import com.google.protobuf.CodedInputStream;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;

/** 保留原始 gRPC 状态、说明、错误详情和响应元数据，也可由服务实现抛出。 */
public final class GrpcStatusException extends MetException {
    private static final long         serialVersionUID = 1L;
    private final        int          code;
    private final        String       description;
    private final        byte[]       details;
    private final        GrpcMetadata metadata;

    public GrpcStatusException(int code, String description, byte[] details, GrpcMetadata metadata) {
        super(meteorStatus(code), "gRPC status " + code + ": " + description);
        if (code < 1 || code > 16) {
            throw new IllegalArgumentException("gRPC failure status must be between 1 and 16");
        }
        this.code = code;
        this.description = description;
        this.details = details == null ? new byte[0] : details.clone();
        this.metadata = metadata == null ? GrpcMetadata.create() : metadata;
        this.validateDetails();
    }

    public int getCode() {
        return this.code;
    }

    public String getDescription() {
        return this.description;
    }

    public byte[] getDetails() {
        return this.details.clone();
    }

    public GrpcMetadata getMetadata() {
        return this.metadata;
    }

    /** 超时仍兼容 MetTimeoutException，可从其 cause 中取得原始 gRPC 状态。 */
    public static GrpcStatusException find(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof GrpcStatusException status) {
                return status;
            }
        }
        return null;
    }

    private void validateDetails() {
        // google.rpc.Status 的 code 位于字段 1；保留未知字段和 Any 详情的原始字节。
        try {
            CodedInputStream input = CodedInputStream.newInstance(this.details);
            int tag;
            while ((tag = input.readTag()) != 0) {
                if (tag == 8) {
                    if (input.readInt32() != this.code) {
                        throw new IllegalArgumentException("grpc-status-details-bin contradicts grpc-status");
                    }
                } else if (!input.skipField(tag)) {
                    throw new IllegalArgumentException("Invalid grpc-status-details-bin");
                }
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid grpc-status-details-bin", failure);
        }
    }

    private static short meteorStatus(int code) {
        return switch (code) {
            case 4 -> ProtocolStatus.Timeout;
            case 7 -> ProtocolStatus.Forbidden;
            case 16 -> ProtocolStatus.Unauthorized;
            case 12 -> ProtocolStatus.NotFound;
            case 8 -> ProtocolStatus.QueueFull;
            case 14 -> ProtocolStatus.NetworkError;
            default -> ProtocolStatus.InvokeError;
        };
    }
}
