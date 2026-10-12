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
import java.util.TreeMap;

/** HTTP 头及完整或增量正文；流式正文由消费方负责关闭。 */
public final class HttpResponse {
    private final int                 status;
    private final Map<String, String> headers;
    private final byte[]              body;
    private final Map<String, String> trailers;
    private final HttpBody            stream;

    private HttpResponse(int status, Map<String, String> headers, byte[] body, Map<String, String> trailers, HttpBody stream) {
        this.status = status;
        Map<String, String> headersCopy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headersCopy.putAll(headers);
        this.headers = Collections.unmodifiableMap(headersCopy);
        this.body = body.clone();
        Map<String, String> trailersCopy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        trailersCopy.putAll(trailers);
        this.trailers = Collections.unmodifiableMap(trailersCopy);
        this.stream = stream;
    }

    public int status() {
        return this.status;
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

    public Map<String, String> trailers() {
        return this.trailers;
    }

    public HttpBody stream() {
        return this.stream;
    }

    /** 创建正文已完整缓冲的响应，无尾部字段时传入空 trailers。 */
    public static HttpResponse newBuffered(int status, Map<String, String> headers, byte[] body, Map<String, String> trailers) {
        return new HttpResponse(status, headers, body, trailers, null);
    }

    /** 创建正文按需消费的响应，后续尾部字段由 stream 交付。 */
    public static HttpResponse newStreaming(int status, Map<String, String> headers, HttpBody stream, Map<String, String> trailers) {
        return new HttpResponse(status, headers, new byte[0], trailers, stream);
    }
}
