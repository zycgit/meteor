/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** HTTP 头及完整或增量正文；流式正文由消费方负责关闭。 */
public final class HttpRequest {
    private final String              method;
    private final String              uri;
    private final Map<String, String> headers;
    private final byte[]              body;
    private final int                 version;
    private final HttpBody            stream;

    private HttpRequest(String method, String uri, Map<String, String> headers, byte[] body, int version, HttpBody stream) {
        this.method = Objects.requireNonNull(method);
        this.uri = Objects.requireNonNull(uri);
        Map<String, String> headersCopy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headersCopy.putAll(headers);
        this.headers = Collections.unmodifiableMap(headersCopy);
        this.body = body.clone();
        this.version = version;
        this.stream = stream;
    }

    public String method() {
        return this.method;
    }

    public String uri() {
        return this.uri;
    }

    public Map<String, String> headers() {
        return this.headers;
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

    public HttpBody stream() {
        return this.stream;
    }

    /** 创建正文已完整缓冲的请求，version 指定 HTTP 主版本号。 */
    public static HttpRequest newBuffered(String method, String uri, Map<String, String> headers, byte[] body, int version) {
        return new HttpRequest(method, uri, headers, body, version, null);
    }

    /** 创建正文按需消费的请求，version 指定 HTTP 主版本号。 */
    public static HttpRequest newStreaming(String method, String uri, Map<String, String> headers, HttpBody stream, int version) {
        return new HttpRequest(method, uri, headers, new byte[0], version, stream);
    }
}
