/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.triple;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.http12.h2.H2StreamChannel;
import org.apache.dubbo.remoting.http12.h2.Http2ServerChannelObserver;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.tri.h12.grpc.GrpcHttp2ServerTransportListener;

/** 仅移除 Dubbo 3.3.6 在响应中携带的请求专用 TE 字段。 */
public final class DubboHttp2ResponseFieldsListener extends GrpcHttp2ServerTransportListener {
    public DubboHttp2ResponseFieldsListener(H2StreamChannel streamChannel, URL url, FrameworkModel frameworkModel) {
        super(streamChannel, url, frameworkModel);
    }

    @Override
    protected Http2ServerChannelObserver prepareResponseObserver(Http2ServerChannelObserver responseObserver) {
        // 保留官方编解码和状态处理，响应头遵循 HTTP/2 对 TE 字段的限制。
        Http2ServerChannelObserver observer = super.prepareResponseObserver(responseObserver);
        observer.addHeadersCustomizer((headers, failure) -> headers.remove("te"));
        return observer;
    }
}
