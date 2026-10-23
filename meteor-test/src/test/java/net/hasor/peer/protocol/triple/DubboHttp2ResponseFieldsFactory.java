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
import org.apache.dubbo.remoting.http12.h2.Http2TransportListener;
import org.apache.dubbo.rpc.model.FrameworkModel;
import org.apache.dubbo.rpc.protocol.tri.h12.grpc.GrpcHttp2ServerTransportListenerFactory;

/** 使用 Dubbo 官方传输监听扩展点修正对端 HTTP/2 响应字段。 */
public final class DubboHttp2ResponseFieldsFactory extends GrpcHttp2ServerTransportListenerFactory {
    @Override
    public Http2TransportListener newInstance(H2StreamChannel streamChannel, URL url, FrameworkModel frameworkModel) {
        return new DubboHttp2ResponseFieldsListener(streamChannel, url, frameworkModel);
    }
}
