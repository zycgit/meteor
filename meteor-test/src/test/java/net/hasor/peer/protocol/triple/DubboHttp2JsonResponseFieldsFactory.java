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
import org.apache.dubbo.rpc.protocol.tri.h12.http2.GenericHttp2ServerTransportListenerFactory;

import java.util.Locale;

/** 通过官方 SPI 为 JSON 请求选择符合 HTTP/2 响应字段约束的监听器。 */
public final class DubboHttp2JsonResponseFieldsFactory extends GenericHttp2ServerTransportListenerFactory {
    @Override
    public Http2TransportListener newInstance(H2StreamChannel streamChannel, URL url, FrameworkModel frameworkModel) {
        return new DubboHttp2JsonResponseFieldsListener(streamChannel, url, frameworkModel);
    }

    @Override
    public boolean supportContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (mediaType) {
            case "application/json" -> true;
            default -> false;
        };
    }
}
