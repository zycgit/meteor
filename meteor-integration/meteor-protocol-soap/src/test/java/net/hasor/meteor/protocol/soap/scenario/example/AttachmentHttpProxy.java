/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.hasor.cobble.StringUtils;

/** 只转发 HTTP 字节并记录媒体类型，不解析或改写任一端的 SOAP 和 MIME 报文。 */
public class AttachmentHttpProxy implements HttpHandler {
    private final URI                     targetEndpoint;
    private final HttpClient              http;
    private final AtomicReference<String> requestContentType  = new AtomicReference<>();
    private final AtomicReference<String> responseContentType = new AtomicReference<>();
    private final AtomicReference<byte[]> requestBody         = new AtomicReference<>();
    private final AtomicReference<byte[]> responseBody        = new AtomicReference<>();
    private final AtomicReference<String> requestMethod       = new AtomicReference<>();

    public AttachmentHttpProxy(URI targetEndpoint) {
        this.targetEndpoint = targetEndpoint;
        this.http = HttpClient.newBuilder() //
                .connectTimeout(Duration.ofSeconds(15)) //
                .version(HttpClient.Version.HTTP_1_1) //
                .build();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // 记录原始实体，并把同一份字节和 SOAP 相关 HTTP 头交给独立服务端。
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            String requestType = exchange.getRequestHeaders().getFirst("Content-Type");
            this.requestContentType.set(requestType);
            this.requestBody.set(requestBytes);
            this.requestMethod.set(exchange.getRequestMethod());
            String query = exchange.getRequestURI().getRawQuery();
            URI target = this.targetEndpoint;
            if (query != null) {
                target = URI.create(target + "?" + query);
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(target) //
                    .timeout(Duration.ofSeconds(15)) //
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(requestBytes));
            if (StringUtils.isNotBlank(requestType)) {
                request.header("Content-Type", requestType);
            }
            String action = exchange.getRequestHeaders().getFirst("SOAPAction");
            if (StringUtils.isNotBlank(action)) {
                request.header("SOAPAction", action);
            }
            HttpResponse<byte[]> response = this.http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());

            // 记录服务端实际产生的实体，不在适配器内生成附件、引用或 SOAP 响应。
            String responseType = response.headers().firstValue("Content-Type").orElse("");
            this.responseContentType.set(responseType);
            this.responseBody.set(response.body());
            if (StringUtils.isNotBlank(responseType)) {
                exchange.getResponseHeaders().set("Content-Type", responseType);
            }
            exchange.sendResponseHeaders(response.statusCode(), response.body().length);
            exchange.getResponseBody().write(response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("SOAP HTTP forwarding was interrupted", failure);
        }
    }

    public String getRequestContentType() {
        return this.requestContentType.get();
    }

    public String getRequestMethod() {
        return this.requestMethod.get();
    }

    public String getResponseContentType() {
        return this.responseContentType.get();
    }

    public byte[] getRequestBody() {
        byte[] body = this.requestBody.get();
        return body == null ? null : body.clone();
    }

    public byte[] getResponseBody() {
        byte[] body = this.responseBody.get();
        return body == null ? null : body.clone();
    }
}
