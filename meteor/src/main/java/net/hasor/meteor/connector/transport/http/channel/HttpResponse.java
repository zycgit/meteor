/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import java.util.List;
import java.util.Map;

/** HTTP 头及完整或增量正文；流式正文由消费方负责关闭。 */
public final class HttpResponse {
    private final int                       status;
    private final Map<String, List<String>> headers;
    private final byte[]                    body;
    private final Map<String, List<String>> trailers;
    private final HttpBodyStream            stream;

    private HttpResponse(int status, Map<String, List<String>> headers, byte[] body, //
            Map<String, List<String>> trailers, HttpBodyStream stream) {
        this.status = status;
        this.headers = HttpMessageUtils.copy(headers);
        this.body = body.clone();
        this.trailers = HttpMessageUtils.copy(trailers);
        this.stream = stream;
    }

    public int status() {
        return this.status;
    }

    /** 字段名忽略大小写，同名值按原顺序保留，不按逗号拆分或拼接。 */
    public Map<String, List<String>> headers() {
        return this.headers;
    }

    /** 返回首个字段值；需要全部值时使用 headers()。 */
    public String header(String name) {
        return HttpMessageUtils.first(this.headers, name);
    }

    public byte[] body() {
        if (this.stream != null) {
            throw new IllegalStateException("Use stream() to consume an incremental HTTP body");
        }
        return this.body;
    }

    /** 已知尾部字段；接收增量正文时，在 EOF 后从 stream().trailers() 读取。 */
    public Map<String, List<String>> trailers() {
        return this.trailers;
    }

    public HttpBodyStream stream() {
        return this.stream;
    }

    /** 创建正文已完整缓冲的响应，无尾部字段时传入空 trailers。 */
    public static HttpResponse newBuffered(int status, Map<String, List<String>> headers, //
            byte[] body, Map<String, List<String>> trailers) {
        return new HttpResponse(status, headers, body, trailers, null);
    }

    /** 创建正文按需消费的响应，后续尾部字段由 stream 交付。 */
    public static HttpResponse newStreaming(int status, Map<String, List<String>> headers, //
            HttpBodyStream stream, Map<String, List<String>> trailers) {
        return new HttpResponse(status, headers, new byte[0], trailers, stream);
    }
}
