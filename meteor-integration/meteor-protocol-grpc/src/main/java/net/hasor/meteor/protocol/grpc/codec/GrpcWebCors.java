/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.util.*;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;

/** gRPC-Web 浏览器预检与响应字段映射，供协议会话内部使用。 */
public final class GrpcWebCors {
    private final Set<String> origins;
    private final Set<String> allowedHeaders;
    private final boolean     credentials;
    private final int         maxAge;

    public GrpcWebCors(ProtocolConfig config) {
        this.origins = tokens(config.option("corsOrigins", "*"), false);
        this.allowedHeaders = tokens(config.option("corsHeaders", "*"), true);
        this.credentials = Boolean.parseBoolean(config.option("corsCredentials", "false"));
        this.maxAge = Integer.parseInt(config.option("corsMaxAge", "600"));
        if (this.credentials && this.origins.contains("*")) {
            throw new IllegalArgumentException("Credentialed gRPC-Web CORS requires explicit corsOrigins");
        }
        if (this.maxAge < 0) {
            throw new IllegalArgumentException("corsMaxAge must not be negative");
        }
    }

    /** 返回预检或拒绝响应；null 表示继续执行正常 POST。 */
    public HttpResponse intercept(HttpRequest request) {
        String origin = request.header("origin");
        if (origin != null && !this.allowed(origin)) {
            return this.empty(403, Map.of());
        }
        if (StringUtils.equals(request.method(), "POST")) {
            return null;
        }
        if (!StringUtils.equals(request.method(), "OPTIONS")) {
            return this.decorate(request, this.empty(405, Map.of("allow", List.of("POST, OPTIONS"))));
        }

        // 预检只允许 POST，并逐项验证浏览器准备发送的字段。
        String method = request.header("access-control-request-method");
        if (origin == null || !StringUtils.equals(method, "POST")) {
            return this.decorate(request, this.empty(400, Map.of()));
        }
        Set<String> requested = tokens(request.header("access-control-request-headers"), true);
        for (String header : requested) {
            boolean valid = header.matches("[!#$%&'*+.^_`|~0-9a-z-]+");
            boolean permitted = this.allowedHeaders.contains("*") || this.allowedHeaders.contains(header);
            if (!valid || !permitted) {
                return this.decorate(request, this.empty(403, Map.of()));
            }
        }
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("access-control-allow-methods", List.of("POST"));
        if (!requested.isEmpty()) {
            headers.put("access-control-allow-headers", List.of(String.join(", ", requested)));
        }
        headers.put("access-control-max-age", List.of(Integer.toString(this.maxAge)));
        headers.put("vary", List.of("Origin, Access-Control-Request-Method, Access-Control-Request-Headers"));
        return this.decorate(request, this.empty(200, headers));
    }

    /** 保留正文和尾部的所有权，只补浏览器可以读取的响应字段。 */
    public HttpResponse decorate(HttpRequest request, HttpResponse response) {
        String origin = request.header("origin");
        if (origin == null || !this.allowed(origin)) {
            return response;
        }
        Map<String, List<String>> headers = new LinkedHashMap<>(response.headers());
        // 挂载配置是 CORS 策略的唯一来源，覆盖应用留下的大小写变体。
        for (String name : new ArrayList<>(headers.keySet())) {
            boolean allowOrigin = StringUtils.equalsIgnoreCase(name, "access-control-allow-origin");
            boolean allowCredentials = StringUtils.equalsIgnoreCase(name, "access-control-allow-credentials");
            boolean expose = StringUtils.equalsIgnoreCase(name, "access-control-expose-headers");
            if (allowOrigin || allowCredentials || expose) {
                headers.remove(name);
            }
        }
        String permitted = this.origins.contains("*") ? "*" : origin;
        headers.put("access-control-allow-origin", List.of(permitted));
        if (this.credentials) {
            headers.put("access-control-allow-credentials", List.of("true"));
        }

        // 元数据字段也暴露给浏览器，Set-Cookie 仍由浏览器的 Cookie 规则管理。
        Set<String> exposed = new LinkedHashSet<>(List.of("grpc-status", "grpc-message", "grpc-status-details-bin"));
        for (String name : response.headers().keySet()) {
            if (!StringUtils.equalsIgnoreCase(name, "set-cookie")) {
                exposed.add(name.toLowerCase(Locale.ROOT));
            }
        }
        headers.put("access-control-expose-headers", List.of(String.join(", ", exposed)));
        List<String> vary = new ArrayList<>();
        for (String name : new ArrayList<>(headers.keySet())) {
            if (StringUtils.equalsIgnoreCase(name, "vary")) {
                vary.addAll(headers.remove(name));
            }
        }
        vary.add("Origin");
        headers.put("vary", vary);
        if (response.stream() != null) {
            return HttpResponse.newStreaming(response.status(), headers, response.stream(), response.trailers());
        }
        return HttpResponse.newBuffered(response.status(), headers, response.body(), response.trailers());
    }

    private boolean allowed(String origin) {
        return this.origins.contains("*") || this.origins.contains(origin);
    }

    private HttpResponse empty(int status, Map<String, List<String>> headers) {
        return HttpResponse.newBuffered(status, headers, new byte[0], Map.of());
    }

    private static Set<String> tokens(String value, boolean lowerCase) {
        Set<String> tokens = new LinkedHashSet<>();
        if (value == null) {
            return tokens;
        }
        for (String token : value.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(lowerCase ? trimmed.toLowerCase(Locale.ROOT) : trimmed);
            }
        }
        return tokens;
    }
}
