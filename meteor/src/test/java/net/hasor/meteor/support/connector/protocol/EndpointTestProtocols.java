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
import java.util.function.BiConsumer;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.protocol.ProtocolContext;
import net.hasor.meteor.connector.protocol.ProtocolFactory;
import net.hasor.meteor.connector.protocol.ProtocolSession;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.meteor.connector.transport.RouteMatch;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.Payload;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 用最小报文观察协议选择、共享端口和通道生命周期。 */
public final class EndpointTestProtocols {
    private EndpointTestProtocols() {
    }

    public static class IncompleteProbe extends WireA {
        public RouteMatch probe(ProtocolConfig config, byte[] prefix) {
            return RouteMatch.NEED_MORE;
        }
    }

    private static ResponsePayload response(long id, String value) {
        ResponsePayload response = new ResponsePayload();
        response.setRequestID(id);
        response.setStatus(ProtocolStatus.OK);
        response.setReturnData(value);
        return response;
    }

    public static class HttpA implements ProtocolFactory<HttpExchange>, TestHttpCodec {
        @Override
        public String scheme() {
            return this.name();
        }

        @Override
        public RouteMatch probe(ProtocolConfig config, HttpExchange message) {
            return RouteMatch.REJECT;
        }

        public String defaultSerializeType() {
            return "Java";
        }

        public String transport() {
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

        private String path;
        private long   ids;

        protected String marker() {
            return "A";
        }

        public ProtocolSession<HttpExchange> create( //
                ProtocolConfig config, //
                ProtocolContext context, //
                NetworkChannel<HttpExchange> connection, //
                BiConsumer<Long, Payload> messages) {
            // 每个连接使用独立的请求序号与挂载路径。
            HttpA adapter = this instanceof HttpB ? new HttpB() : new HttpA();
            adapter.path = config.option("contextPath", "/");
            return new TestHttpSession(adapter, context, connection, messages);
        }

        public TestHttpInvocation receive(HttpRequest request) {
            // 将外部请求交给连接器订阅器，响应带上当前协议标识。
            RequestPayload payload = new RequestPayload();
            payload.setRequestID(++this.ids);
            return TestHttpInvocation.dispatch(payload, response -> {
                String value = this.marker() + ":" + response.getReturnData();
                byte[] body = value.getBytes(StandardCharsets.UTF_8);
                return HttpResponse.newBuffered(200, Collections.emptyMap(), body, Map.of());
            });
        }

        public HttpRequest encode(InterAddress target, RequestPayload request) {
            return HttpRequest.newBuffered("POST", this.path, Collections.emptyMap(), new byte[0], 1, Map.of());
        }

        public ResponsePayload decode(long id, HttpResponse response) {
            return response(id, new String(response.body(), StandardCharsets.UTF_8));
        }

        public HttpResponse error(Throwable error) {
            byte[] body = "closing".getBytes(StandardCharsets.UTF_8);
            return HttpResponse.newBuffered(503, Collections.emptyMap(), body, Map.of());
        }
    }

    public static class HttpB extends HttpA {
        protected String marker() {
            return "B";
        }
    }

    public static class WireA implements ProtocolFactory<byte[]> {
        @Override
        public String scheme() {
            return this.name();
        }

        public String defaultSerializeType() {
            return "Java";
        }

        public String transport() {
            return "tcp";
        }

        @Override
        public void validateService(ProtocolConfig config, MetBindInfo<?> service) {
        }

        public Class<byte[]> messageType() {
            return byte[].class;
        }

        public String name() {
            return this.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        }

        protected String marker() {
            return "AA";
        }

        public RouteMatch probe(ProtocolConfig config, byte[] prefix) {
            if (prefix.length < 2) {
                return RouteMatch.NEED_MORE;
            }
            String receivedMarker = new String(prefix, 0, 2, StandardCharsets.US_ASCII);
            boolean matches = StringUtils.equals(this.marker(), receivedMarker);
            return matches ? RouteMatch.MATCH : RouteMatch.REJECT;
        }

        public ProtocolSession<byte[]> create( //
                ProtocolConfig config, //
                ProtocolContext context, //
                NetworkChannel<byte[]> network, //
                BiConsumer<Long, Payload> messages) {
            return new WireSession(this.marker(), network, messages);
        }
    }

    public static class WireB extends WireA {
        protected String marker() {
            return "BB";
        }
    }

    public static class DatagramWireA extends WireA {
        public String transport() {
            return "udp";
        }

    }

    public static class DatagramWireB extends WireB {
        public String transport() {
            return "udp";
        }

    }

    private static final class WireSession implements ProtocolSession<byte[]> {
        private final String                    marker;
        private final NetworkChannel<byte[]>    network;
        private final BiConsumer<Long, Payload> messages;
        private final BasicFuture<Void>         ready = new BasicFuture<>((Void) null);
        private final StringBuilder             text  = new StringBuilder();
        private       long                      ids;

        WireSession(String marker, NetworkChannel<byte[]> network, BiConsumer<Long, Payload> messages) {
            this.marker = marker;
            this.network = network;
            this.messages = messages;
        }

        public Future<Void> ready() {
            return this.ready;
        }

        public void connected() {
        }

        public void receive(byte[] bytes) {
            // TCP 片段在协议选择后继续按行组帧，UDP 报文走同一测试格式。
            this.text.append(new String(bytes, StandardCharsets.UTF_8));
            int end;
            while ((end = this.text.indexOf("\n")) >= 0) {
                RequestPayload payload = new RequestPayload();
                payload.setRequestID(++this.ids);
                this.messages.accept(payload.getRequestID(), payload);
                this.text.delete(0, end + 1);
            }
        }

        public Future<Void> send(Payload payload) {
            ResponsePayload response = (ResponsePayload) payload;
            String message = this.marker + response.getReturnData() + "\n";
            return this.network.write(message.getBytes(StandardCharsets.UTF_8));
        }

        public void prepareDrain() {
        }

        public void closed(Throwable cause) {
        }
    }
}
