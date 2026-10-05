/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.transport;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import net.hasor.rsf.address.InterAddress;

public final class SocketAddressUtils {
    private SocketAddressUtils() {
    }

    public static InterAddress local(InterAddress configured, SocketAddress address) {
        if (address == null) {
            return null;
        }

        InetSocketAddress local = (InetSocketAddress) address;
        return new InterAddress(configured.getSchema(), local.getAddress().getHostAddress(), local.getPort(), configured.getFormUnit());
    }
}
