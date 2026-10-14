/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.meteor.connector.ProtocolConfig;

/** 解析 gRPC Service Config 的重试部分；策略留在插件，每次 RPC 仍占用原 Caller 的一个调用槽。 */
final class GrpcClientPolicy {
    private static final List<String>                   CODES     = List.of("OK", "CANCELLED", "UNKNOWN", "INVALID_ARGUMENT", //
            "DEADLINE_EXCEEDED", "NOT_FOUND", "ALREADY_EXISTS", "PERMISSION_DENIED", "RESOURCE_EXHAUSTED", //
            "FAILED_PRECONDITION", "ABORTED", "OUT_OF_RANGE", "UNIMPLEMENTED", "INTERNAL", "UNAVAILABLE", //
            "DATA_LOSS", "UNAUTHENTICATED");
    private static final GrpcMethodPolicy               SINGLE    = new GrpcMethodPolicy(1, false, 0, 0, 1, Set.of());
    private final        Map<String, GrpcMethodPolicy>  methods   = new HashMap<>();
    private final        Map<String, GrpcRetryThrottle> throttles = new ConcurrentHashMap<>();
    private final        int                            bufferLimit;
    private final        double                         maximumTokens;
    private final        double                         tokenRatio;

    GrpcClientPolicy(ProtocolConfig config) {
        this.bufferLimit = config.integer("retryBufferSize", 256 * 1024);
        try {
            JsonNode input = new ObjectMapper().readTree(config.option("serviceConfig", "{}"));
            if (input == null || !input.isObject()) {
                throw new IllegalArgumentException("gRPC serviceConfig must be an object");
            }
            JsonNode throttle = input.path("retryThrottling");
            this.maximumTokens = throttle.isMissingNode() ? 0 : throttle.path("maxTokens").asDouble();
            this.tokenRatio = throttle.isMissingNode() ? 0 : throttle.path("tokenRatio").asDouble();
            if (!throttle.isMissingNode() && (this.maximumTokens <= 0 || this.tokenRatio <= 0)) {
                throw new IllegalArgumentException("retryThrottling requires positive maxTokens and tokenRatio");
            }
            for (JsonNode method : input.path("methodConfig")) {
                this.register(method);
            }
            if (!this.methods.isEmpty() && !Boolean.parseBoolean(config.option("streamBody", "false"))) {
                throw new IllegalArgumentException("Configure streamBody=true for gRPC retry or hedging policies");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid gRPC serviceConfig", failure);
        }
    }

    private void register(JsonNode config) {
        boolean retry = config.has("retryPolicy");
        boolean hedge = config.has("hedgingPolicy");
        if (!retry && !hedge) {
            return;
        }
        if (retry && hedge) {
            throw new IllegalArgumentException("retryPolicy and hedgingPolicy are mutually exclusive");
        }
        JsonNode policy = config.get(retry ? "retryPolicy" : "hedgingPolicy");
        JsonNode attemptCount = policy.path("maxAttempts");
        int attempts = attemptCount.asInt();
        if (!attemptCount.isIntegralNumber() || !attemptCount.canConvertToInt() || attempts < 2) {
            throw new IllegalArgumentException("gRPC maxAttempts must be at least 2");
        }
        // 与标准客户端一致，将服务端配置的尝试上限限制为五次。
        attempts = Math.min(attempts, 5);
        long delay = duration(policy.path(hedge ? "hedgingDelay" : "initialBackoff").asText(hedge ? "0s" : ""));
        long maximum = hedge ? delay : duration(policy.path("maxBackoff").asText());
        double multiplier = hedge ? 1 : policy.path("backoffMultiplier").asDouble();
        if ((!hedge && (delay <= 0 || maximum <= 0)) || multiplier <= 0) {
            throw new IllegalArgumentException("gRPC backoff values must be positive");
        }
        Set<Integer> statuses = new HashSet<>();
        String statusField = hedge ? "nonFatalStatusCodes" : "retryableStatusCodes";
        if (policy.has(statusField) && !policy.path(statusField).isArray()) {
            throw new IllegalArgumentException(statusField + " must be an array");
        }
        for (JsonNode status : policy.path(statusField)) {
            int code;
            if (status.isIntegralNumber()) {
                code = status.asInt();
            } else {
                String name = status.asText().toUpperCase(Locale.ROOT);
                code = CODES.indexOf(name);
            }
            if (code <= 0 || code > 16) {
                throw new IllegalArgumentException("Invalid gRPC retry status: " + status);
            }
            statuses.add(code);
        }
        if (!hedge && statuses.isEmpty()) {
            throw new IllegalArgumentException("retryableStatusCodes must not be empty");
        }
        GrpcMethodPolicy parsed = new GrpcMethodPolicy(attempts, hedge, delay, maximum, multiplier, statuses);
        if (!config.path("name").isArray() || config.path("name").isEmpty()) {
            throw new IllegalArgumentException("gRPC methodConfig requires names");
        }
        for (JsonNode name : config.get("name")) {
            String service = name.path("service").asText("");
            String method = name.path("method").asText("");
            if (service.isEmpty() && !method.isEmpty()) {
                throw new IllegalArgumentException("A gRPC method policy requires a service name");
            }
            if (this.methods.putIfAbsent(service + "/" + method, parsed) != null) {
                throw new IllegalArgumentException("Duplicate gRPC method policy: " + service + "/" + method);
            }
        }
    }

    GrpcMethodPolicy method(String uri) {
        String[] path = uri.split("/");
        if (path.length < 2) {
            return null;
        }
        String service = path[path.length - 2];
        String name = path[path.length - 1];
        GrpcMethodPolicy policy = this.methods.get(service + "/" + name);
        if (policy == null) {
            policy = this.methods.get(service + "/");
        }
        if (policy == null) {
            policy = this.methods.get("/");
        }
        // 未启用重试的方法仍需将成功结果记入同一目标的限流令牌。
        return policy == null && this.maximumTokens > 0 ? SINGLE : policy;
    }

    int bufferLimit() {
        return this.bufferLimit;
    }

    GrpcRetryThrottle throttle(String target) {
        if (this.maximumTokens == 0) {
            return null;
        }
        return this.throttles.computeIfAbsent(target, ignored -> {
            return new GrpcRetryThrottle(this.maximumTokens, this.tokenRatio);
        });
    }

    private static long duration(String text) {
        if (!text.matches("[0-9]+(?:\\.[0-9]{1,9})?s")) {
            throw new IllegalArgumentException("Invalid gRPC duration: " + text);
        }
        BigDecimal seconds = new BigDecimal(text.substring(0, text.length() - 1));
        // 定时器以毫秒调度，亚毫秒值向上取整，避免提前重试。
        return seconds.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.CEILING).longValueExact();
    }
}
