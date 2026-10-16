/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.http1;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.channel.ProtoContext;
import net.hasor.neta.codec.http.*;

/** 让 Neta 的请求生命周期同时持有头部阶段选定的路由。 */
final class Http1RoutedRequestAggregator extends HttpRequestAggregator {
    Http1RoutedRequestAggregator(int maxContentLength) {
        super(maxContentLength);
    }

    @Override
    protected HttpObject newFullMessage(ProtoContext context, HttpRequest request, //
            DefaultHttpHeaders headers, DefaultTrailerHttpHeaders trailers, ByteBuf content) {
        if (!(headers instanceof RoutedHeaders routed)) {
            throw new IllegalStateException("HTTP/1 request has no header route selection");
        }

        RouteSelection route = routed.route;
        FullHttpRequest full = (FullHttpRequest) super.newFullMessage(context, request, headers, trailers, content);
        return new RoutedRequest(full, route);
    }

    /** 不包含内部网络字段；后续真实头由 Neta 合并进这个首个头对象。 */
    static final class RoutedHeaders extends DefaultHttpHeaders {
        private RouteSelection route;

        RoutedHeaders(RouteSelection route) {
            this.route = route;
        }

        @Override
        public void release() {
            this.route = null;
            super.release();
        }
    }

    /** 聚合后才包装，保留 Neta 原始请求行上的自动应答顺序信息。 */
    static final class RoutedRequest extends AbstractHttpObject<RoutedRequest> {
        private FullHttpRequest request;
        private RouteSelection  route;

        RoutedRequest(FullHttpRequest request, RouteSelection route) {
            this.request = request;
            this.route = route;
            this.inheritHttpObjectState(request);
        }

        FullHttpRequest request() {
            return this.request;
        }

        RouteSelection route() {
            return this.route;
        }

        @Override
        protected RoutedRequest self() {
            return this;
        }

        @Override
        public void release() {
            FullHttpRequest request = this.request;
            this.request = null;
            this.route = null;
            this.resetHttpObjectState();
            if (request != null) {
                request.release();
            }
        }
    }
}
