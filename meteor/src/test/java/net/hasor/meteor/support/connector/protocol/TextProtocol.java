/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ObjLongConsumer;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.*;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.http.channel.HttpExchange;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** HTTP 传输测试使用的协议夹具。 */
public class TextProtocol implements TestHttpCodec, ProtocolFactory<HttpExchange> {
    @Override
    public RouteProbeResult probe(ProtocolConfig config, HttpExchange message) {
        return RouteProbeResult.REJECT;
    }

    public String defaultSerializeType() {
        return "Java";
    }

    public String transportType() {
        return "http";
    }

    @Override
    public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
    }

    public Class<HttpExchange> messageType() {
        return HttpExchange.class;
    }

    public String name() {
        return this.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private final AtomicLong requestIds = new AtomicLong();

    public ProtocolSession<HttpExchange> createSession( //
            ProtocolConfig config, //
            ProtocolSessionContext context, //
            NetworkChannel<HttpExchange> connection, //
            ObjLongConsumer<Payload> messages) {
        return new TestHttpSession(this, context, connection, messages);
    }

    public TestHttpInvocation receive(HttpRequest request) {
        // 文本正文映射到固定的回显服务，供正式入口场景和传输契约共用。
        String requestBody = new String(request.body(), StandardCharsets.UTF_8);
        RequestPayload decoded = request(this.requestIds.incrementAndGet(), requestBody);
        decoded.setReceiveTime(System.currentTimeMillis());
        decoded.setServiceName("http.echo");
        decoded.setServiceGroup("http");
        decoded.setServiceVersion("1");
        decoded.setTargetMethod("echo");
        return TestHttpInvocation.dispatch(decoded, response -> {
            byte[] body = String.valueOf(response.getReturnData()).getBytes(StandardCharsets.UTF_8);
            return HttpResponse.newBuffered(200, Collections.emptyMap(), body, Map.of());
        });
    }

    public HttpRequest encode(InterAddress target, RequestPayload request) {
        String value = String.valueOf(request.getParameterValues().get(0));
        byte[] body = value.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuffered("POST", "/", Collections.emptyMap(), body, 1, Map.of());
    }

    public ResponsePayload decode(long id, HttpResponse response) {
        ResponsePayload decoded = new ResponsePayload();
        decoded.setRequestID(id);
        decoded.setStatus((short) response.status());
        decoded.setReturnData(new String(response.body(), StandardCharsets.UTF_8));
        return decoded;
    }

    public HttpResponse error(Throwable failure) {
        byte[] body = failure.toString().getBytes(StandardCharsets.UTF_8);
        return HttpResponse.newBuffered(500, Collections.emptyMap(), body, Map.of());
    }

    private static RequestPayload request(long id, String value) {
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setSerializeType("Java");
        request.setClientTimeout(3000);
        request.addParameter("java.lang.String", value);
        return request;
    }
}
