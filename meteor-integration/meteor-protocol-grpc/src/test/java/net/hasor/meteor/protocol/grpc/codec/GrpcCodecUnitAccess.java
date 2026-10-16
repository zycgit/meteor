/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;

/** 仅用于 unit 包访问包内编解码算法；不进入插件产物。 */
public final class GrpcCodecUnitAccess {
    private GrpcCodecUnitAccess() {
    }

    public static ProtocolConfig config(Map<String, String> options) {
        return ProtocolConfig.newConfig("grpc", "grpc", options);
    }

    public static final class Codec {
        private final GrpcHttpCodec delegate;

        public Codec(ProtocolConfig config, MetContext context) {
            this.delegate = new GrpcHttpCodec(config, context);
        }

        public RequestPayload receive(HttpRequest request) throws Exception {
            RequestPayload payload = this.delegate.open(request);
            this.delegate.readRequest(payload, request);
            return payload;
        }

        public HttpRequest encode(RequestPayload request) throws Exception {
            return this.delegate.encode(request);
        }

        public HttpResponse encode(RequestPayload request, ResponsePayload response) throws Exception {
            return this.delegate.encode(request, response);
        }

        public ResponsePayload decode(RequestPayload request, HttpResponse response) throws Exception {
            return this.delegate.decode(request, response);
        }

        public byte[] frame(byte[] message, String encoding) throws Exception {
            return this.delegate.frame(message, encoding);
        }

        public byte[] readMessage(InputStream input, String encoding) throws Exception {
            return this.delegate.readMessage(input, encoding);
        }

        public Object decodeValue(RequestPayload request, Class<?> type, byte[] bytes, //
                boolean parameter) throws Exception {
            return this.delegate.decodeValue(request, type, bytes, parameter);
        }

        public void validateStatus(HttpResponse response, Map<String, List<String>> trailers) throws Exception {
            this.delegate.validateStatus(response, trailers);
        }

        public String requestCompression() {
            return this.delegate.requestCompression();
        }

        public HttpResponse error(Throwable failure) {
            return this.delegate.error(failure);
        }
    }

    public static final class MethodShape {
        private final GrpcMethodShape delegate;

        public MethodShape(Method method) {
            this.delegate = new GrpcMethodShape(method);
        }

        public boolean streamingRequest() {
            return this.delegate.streamingRequest();
        }

        public boolean streamingResponse() {
            return this.delegate.streamingResponse();
        }

        public Class<?> requestType() {
            return this.delegate.requestType();
        }

        public Class<?> responseType() {
            return this.delegate.responseType();
        }
    }

    public static GrpcMetadata receiveMetadata(Map<String, List<String>> values, boolean trailer) {
        GrpcHttpCodec codec = new GrpcHttpCodec(config(Map.of()), null);
        OptionInfo options = new OptionInfo();
        if (trailer) {
            codec.readTrailers(values, options);
        } else {
            codec.readHeaders(values, options);
        }
        return GrpcMetadata.from(options);
    }
}
