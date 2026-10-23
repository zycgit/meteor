/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.dubbo;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.netty4.NettyTransporter;

/** 官方 3.3.6 TLS 对端通过正式传输 SPI 为引擎补齐目标主机。 */
public final class DubboPeerHostTransporter extends NettyTransporter {
    @Override
    public Client connect(URL url, ChannelHandler listener) throws RemotingException {
        return new DubboPeerHostNettyClient(url, listener);
    }
}
