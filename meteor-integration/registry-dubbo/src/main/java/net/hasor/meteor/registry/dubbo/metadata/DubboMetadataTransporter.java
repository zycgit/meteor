/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo.metadata;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.netty4.NettyTransporter;

/** 在 SDK 客户端创建 SSLEngine 时保留远端主机名，服务端仍采用官方实现。 */
public final class DubboMetadataTransporter extends NettyTransporter {
    static final String NAME = "meteor-peer-host-netty";

    @Override
    public Client connect(URL url, ChannelHandler listener) throws RemotingException {
        return new DubboMetadataNettyClient(url, listener);
    }
}
