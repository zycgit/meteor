/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.hasor.cobble.ObjectUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.connector.ContextRoutes;

/** HTTP 头及完整或增量正文；流式正文由消费方负责关闭。 */
public final class HttpRequest {
    private final String                    method;
    private final String                    uri;
    private final Map<String, List<String>> headers;
    private final byte[]                    body;
    private final int                       version;
    private final Map<String, List<String>> trailers;
    private final HttpBody                  stream;
    private       ContextRoutes.Route       route;

    private HttpRequest(String method, String uri, Map<String, List<String>> headers, byte[] body, int version, HttpBody stream, Map<String, List<String>> trailers) {
        this.method = Objects.requireNonNull(method);
        this.uri = Objects.requireNonNull(uri);
        this.headers = HttpMessages.copy(headers);
        this.body = body.clone();
        this.version = version;
        this.trailers = HttpMessages.copy(trailers);
        this.stream = stream;
    }

    public String method() {
        return this.method;
    }

    public String uri() {
        return this.uri;
    }

    /** 字段名忽略大小写，同名值按原顺序保留，不按逗号拆分或拼接。 */
    public Map<String, List<String>> headers() {
        return this.headers;
    }

    /** 返回首个字段值；需要全部值时使用 headers()。 */
    public String header(String name) {
        return HttpMessages.first(this.headers, name);
    }

    public byte[] body() {
        if (this.stream != null) {
            throw new IllegalStateException("Use stream() to consume an incremental HTTP body");
        }
        return this.body;
    }

    public int version() {
        return this.version;
    }

    /** 已知尾部字段；接收增量正文时，在 EOF 后从 stream().trailers() 读取。 */
    public Map<String, List<String>> trailers() {
        return this.trailers;
    }

    public HttpBody stream() {
        return this.stream;
    }

    /** 已选中的协议路由；尚未匹配或没有路由时返回 null。 */
    public ContextRoutes.Route route() {
        return this.route;
    }

    void route(ContextRoutes.Route route) {
        this.route = route;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof HttpRequest other)) {
            return false;
        }
        boolean sameTarget = StringUtils.equals(this.method, other.method) && StringUtils.equals(this.uri, other.uri);
        boolean sameHeaders = ObjectUtils.equals(this.headers, other.headers);
        boolean sameTrailers = ObjectUtils.equals(this.trailers, other.trailers);
        boolean sameBody = this.body == other.body && ObjectUtils.equals(this.stream, other.stream);
        return sameTarget && sameHeaders && sameTrailers && sameBody && this.version == other.version;
    }

    @Override
    public int hashCode() {
        int result = ObjectUtils.hashCode(this.method);
        result = 31 * result + ObjectUtils.hashCode(this.uri);
        result = 31 * result + ObjectUtils.hashCode(this.headers);
        result = 31 * result + ObjectUtils.hashCode(this.body);
        result = 31 * result + Integer.hashCode(this.version);
        result = 31 * result + ObjectUtils.hashCode(this.trailers);
        return 31 * result + ObjectUtils.hashCode(this.stream);
    }

    @Override
    public String toString() {
        return "HttpRequest[method=" + this.method + ", uri=" + this.uri + ", headers=" + this.headers //
                + ", body=" + this.body + ", version=" + this.version + ", trailers=" + this.trailers //
                + ", stream=" + this.stream + "]";
    }

    /** 创建正文已完整缓冲的请求，version 指定 HTTP 主版本号。 */
    public static HttpRequest newBuffered(String method, String uri, Map<String, List<String>> headers, byte[] body, int version, Map<String, List<String>> trailers) {
        return new HttpRequest(method, uri, headers, body, version, null, trailers);
    }

    /** 创建正文按需消费的请求，version 指定 HTTP 主版本号。 */
    public static HttpRequest newStreaming(String method, String uri, Map<String, List<String>> headers, HttpBody stream, int version) {
        return new HttpRequest(method, uri, headers, new byte[0], version, stream, Map.of());
    }
}
