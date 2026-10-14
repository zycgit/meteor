/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;

import java.util.List;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 仅在 unit 包访问包内编解码模型；所有行为直接委托生产实现。 */
public final class HproseUnitAccess {
    private HproseUnitAccess() {
    }

    public static final class Codec {
        private final HproseCodec delegate;

        public Codec(ProtocolConfig config, MetContext context) {
            this.delegate = new HproseCodec(config, context);
        }

        public Invocation receive(HttpRequest request) throws Exception {
            return new Invocation(this.delegate.receive(request));
        }

        public HttpRequest encode(RequestPayload request) throws Exception {
            return this.delegate.encode(request);
        }

        public ResponsePayload decode(RequestPayload request, HttpResponse response) throws Exception {
            return this.delegate.decode(request, response);
        }

        public HttpResponse error(Throwable failure) {
            return this.delegate.error(failure);
        }

        public byte[] encodeResult(Call call, ResponsePayload response) throws Exception {
            return this.delegate.encodeResult(call.delegate, response);
        }
    }

    public static final class Invocation {
        private final HproseInvocation delegate;

        private Invocation(HproseInvocation delegate) {
            this.delegate = delegate;
        }

        public List<Call> calls() {
            // 仅包装测试可见类型，不复制或替换生产编解码逻辑。
            return this.delegate.calls().stream().map(Call::new).toList();
        }

        public HttpResponse immediate() {
            return this.delegate.immediate();
        }
    }

    public static final class Call {
        private final HproseCall delegate;

        private Call(HproseCall delegate) {
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
