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
import org.apache.dubbo.rpc.protocol.tri.h12.http2.GenericHttp2ServerTransportListener;

/** 保留官方 JSON 处理，仅移除 HTTP/2 响应中的请求专用 TE 字段。 */
public final class DubboHttp2JsonResponseFieldsListener extends GenericHttp2ServerTransportListener {
    public DubboHttp2JsonResponseFieldsListener(H2StreamChannel streamChannel, URL url, FrameworkModel frameworkModel) {
        super(streamChannel, url, frameworkModel);
    }

    @Override
    protected Http2ServerChannelObserver prepareResponseObserver(Http2ServerChannelObserver responseObserver) {
        // JSON 和 gRPC 响应遵守相同的 HTTP/2 字段约束。
        Http2ServerChannelObserver observer = super.prepareResponseObserver(responseObserver);
        observer.addHeadersCustomizer((headers, failure) -> headers.remove("te"));
        return observer;
    }
}
