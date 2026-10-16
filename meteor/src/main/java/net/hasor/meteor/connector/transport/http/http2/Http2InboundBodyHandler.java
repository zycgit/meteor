/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http2;
import net.hasor.meteor.connector.transport.http.channel.*;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.channel.ProtoHandler;
import net.hasor.neta.channel.ProtoStatus;
import net.hasor.neta.channel.data.ProtoRcvQueue;
import net.hasor.neta.channel.data.ProtoSndQueue;
import net.hasor.neta.codec.http.*;

/** 每个 HTTP/2 stream 独立收集头部，正文交付后由应用消费并归还流控额度。 */
final class Http2InboundBodyHandler implements ProtoHandler<HttpObject, HttpObject> {
    private final BiConsumer<Long, HttpRequest>  requests;
    private final BiConsumer<Long, HttpResponse> responses;
    private final BiConsumer<Long, Throwable>    failures;
    private final HttpBodyStream bodyStream     = new HttpBodyStream(65535);
    private final Map<String, List<String>>      headers  = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final Map<String, List<String>>      trailers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private       String                         method;
    private       String                         uri;
    private       int                            status;
    private       boolean                        published;

    Http2InboundBodyHandler(BiConsumer<Long, HttpRequest> requests, BiConsumer<Long, HttpResponse> responses, //
            BiConsumer<Long, Throwable> failures) {
        this.requests = requests;
        this.responses = responses;
        this.failures = failures;
    }

    public ProtoStatus onMessage(ProtoContext context, ProtoRcvQueue<HttpObject> source, //
            ProtoSndQueue<HttpObject> target) {
        while (source.hasMore()) {
            HttpObject message = source.takeMessage();
            boolean retained = false;
            try {
                if (message.isBad()) {
                    throw new IllegalArgumentException(message.badReason());
                }

                if (message instanceof DefaultHttpRequest request) {
                    this.method = request.method().name();
                    this.uri = request.uri();
                } else if (message instanceof DefaultHttpResponse response) {
                    this.status = response.status().code();
                }

                if (message instanceof HttpHeaders fields) {
                    Map<String, List<String>> destination = this.headers;
                    if (message instanceof TrailerHttpHeaders) {
                        destination = this.trailers;
                    }
                    HttpMessageUtils.append(destination, fields);
                }

                // 在完整头部到达时发布交换，不等待正文结束。
                if (message instanceof LastHttpHeaders && !this.published) {
                    if (this.status > 0 && this.status < 200) {
                        this.headers.clear();
                        continue;
                    }

                    this.published = true;
                    if (this.method != null) {
                        HttpRequest request = HttpRequest.newStreaming(this.method, this.uri, //
                                this.headers, this.bodyStream, 2);
                        this.requests.accept(message.streamId(), request);
                    } else {
                        HttpResponse response = HttpResponse.newStreaming(this.status, //
                                this.headers, this.bodyStream, Map.of());
                        this.responses.accept(message.streamId(), response);
                    }
                }

                if (message instanceof HttpContent content && content.content().readableBytes() > 0) {
                    this.bodyStream.offer(HttpMessageUtils.body(content.content()), content::release);
                    retained = true;
                }

                if (message instanceof LastHttpContent && this.published) {
                    this.bodyStream.finish(this.trailers);
                }
            } catch (Exception failure) {
                this.bodyStream.fail(failure);
                this.failures.accept(message.streamId(), failure);
            } finally {
                if (!retained) {
                    message.release();
                }
            }
        }
        return ProtoStatus.Next;
    }
}
