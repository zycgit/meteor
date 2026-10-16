/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.udp;
import java.util.concurrent.Executor;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.connector.RouteTable;
import net.hasor.meteor.connector.transport.ChannelListenerFactory;
import net.hasor.meteor.connector.transport.NetworkChannel;
import net.hasor.neta.channel.ProtoBuildContext;

/** 无断言的包内访问适配器。 */
public final class UdpChannelTestAccess {
    private UdpChannelTestAccess() {
    }

    public static NetworkChannel<byte[]> attach(ProtoBuildContext stack, InterAddress remote, Executor executor, //
            int maxDatagramSize, RouteTable<byte[]> routePlan, String initialMountId, //
            ChannelListenerFactory<byte[]> factory) throws Exception {
        return UdpChannel.attach(stack, remote, executor, maxDatagramSize, false, null, 0, 0, //
                routePlan, initialMountId, factory);
    }
}
