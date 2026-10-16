/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 供 unit 测试访问包内 Hprose HTTP 编解码器的薄适配器。 */
public final class HproseHttpCodecTestAccess {
    private HproseHttpCodecTestAccess() {
    }

    public static ProtocolConfig protocolConfig(Map<String, String> options) {
        return ProtocolConfig.newConfig("hprose", "hprose", options);
    }

    public static void writeResponse( //
            long requestId, ResponsePayload response, OutputStream output) throws IOException {
        HproseWireCodec.writeResponse(requestId, response, output);
    }

    public static final class HttpCodec {
        private final HproseHttpCodec delegate;

        public HttpCodec(ProtocolConfig config, MetContext context) {
            this.delegate = new HproseHttpCodec(config, context);
        }

        public DecodedInvocation decodeRequest(HttpRequest request) throws Exception {
            return new DecodedInvocation(this.delegate.decodeRequest(request));
        }

        public HttpRequest encodeRequest(RequestPayload request) throws Exception {
            return this.delegate.encodeRequest(request);
        }

        public ResponsePayload decodeResponse(RequestPayload request, HttpResponse response) throws Exception {
            return this.delegate.decodeResponse(request, response);
        }

        public HttpResponse encodeErrorResponse(Throwable failure) {
            HttpRequest request = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            return this.delegate.encodeErrorResponse(request, failure);
        }

        public byte[] encodeResponse(DecodedCall call, ResponsePayload response) throws Exception {
            return this.delegate.encodeResponse(call.delegate, response);
        }
    }

    public static final class DecodedInvocation {
        private final HproseDecodedInvocation delegate;

        private DecodedInvocation(HproseDecodedInvocation delegate) {
            this.delegate = delegate;
        }

        public List<DecodedCall> calls() {
            List<DecodedCall> calls = new ArrayList<>(this.delegate.calls().size());
            for (HproseDecodedCall call : this.delegate.calls()) {
                calls.add(new DecodedCall(call));
            }
            return calls;
        }

        public HttpResponse immediate() {
            return this.delegate.immediate();
        }
    }

    public static final class DecodedCall {
        private final HproseDecodedCall delegate;

        private DecodedCall(HproseDecodedCall delegate) {
            this.delegate = delegate;
        }

        public RequestPayload request() {
            return this.delegate.request();
        }

        public Throwable failure() {
            return this.delegate.failure();
        }

        public boolean byReference() {
            return this.delegate.byReference();
        }
    }
}
