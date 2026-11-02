/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect;
import java.util.*;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;

/** 保留 Connect 错误码、错误详情和尾部元信息，也可由服务实现抛出。 */
public final class ConnectStatusException extends MetException {
    private static final long                      serialVersionUID = 1L;
    private final        String                    code;
    private final        String                    description;
    private final        List<Map<String, Object>> details;
    private final        Map<String, List<String>> metadata;

    public ConnectStatusException(String code, String message, //
            List<Map<String, Object>> details, Map<String, List<String>> metadata) {
        super(meteorStatus(code), message);
        if (httpStatus(code) == 0) {
            throw new IllegalArgumentException("Unknown Connect error code: " + code);
        }
        this.code = code;
        this.description = message == null ? "" : message;
        List<Map<String, Object>> copiedDetails = new ArrayList<>();
        if (details != null) {
            for (Map<String, Object> detail : details) {
                copiedDetails.add(Collections.unmodifiableMap(new LinkedHashMap<>(detail)));
            }
        }
        this.details = Collections.unmodifiableList(copiedDetails);
        Map<String, List<String>> copiedMetadata = new LinkedHashMap<>();
        if (metadata != null) {
            for (Map.Entry<String, List<String>> entry : metadata.entrySet()) {
                copiedMetadata.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        this.metadata = Collections.unmodifiableMap(copiedMetadata);
    }

    public String getCode() {
        return this.code;
    }

    public String getDescription() {
        return this.description;
    }

    public List<Map<String, Object>> getDetails() {
        return this.details;
    }

    public Map<String, List<String>> getMetadata() {
        return this.metadata;
    }

    public int httpStatus() {
        return httpStatus(this.code);
    }

    /** HTTP 错误正文缺失或无效时，按 Connect 的 HTTP 回退表推导错误码。 */
    public static ConnectStatusException fromHttpStatus(int status) {
        String code = switch (status) {
            case 400 -> "internal";
            case 401 -> "unauthenticated";
            case 403 -> "permission_denied";
            case 404 -> "unimplemented";
            case 429, 502, 503, 504 -> "unavailable";
            default -> "unknown";
        };
        return new ConnectStatusException(code, "HTTP status " + status, null, null);
    }

    public static ConnectStatusException find(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof ConnectStatusException status) {
                return status;
            }
        }
        return null;
    }

    private static int httpStatus(String code) {
        if (code == null) {
            return 0;
        }
        return switch (code) {
            case "canceled" -> 499;
            case "unknown", "internal", "data_loss" -> 500;
            case "invalid_argument", "failed_precondition", "out_of_range" -> 400;
            case "deadline_exceeded" -> 504;
            case "not_found" -> 404;
            case "already_exists", "aborted" -> 409;
            case "permission_denied" -> 403;
            case "resource_exhausted" -> 429;
            case "unimplemented" -> 501;
            case "unavailable" -> 503;
            case "unauthenticated" -> 401;
            default -> 0;
        };
    }

    private static short meteorStatus(String code) {
        if (code == null) {
            return ProtocolStatus.InvokeError;
        }
        return switch (code) {
            case "deadline_exceeded" -> ProtocolStatus.Timeout;
            case "permission_denied" -> ProtocolStatus.Forbidden;
            case "unauthenticated" -> ProtocolStatus.Unauthorized;
            case "unimplemented", "not_found" -> ProtocolStatus.NotFound;
            case "resource_exhausted" -> ProtocolStatus.QueueFull;
            case "unavailable" -> ProtocolStatus.NetworkError;
            default -> ProtocolStatus.InvokeError;
        };
    }
}
