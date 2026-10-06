/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.hasor.neta.bytebuf.ByteBuf;

/** Non-consuming bounded stream probes; datagrams are inspected as complete messages. */
public final class ByteRoutingUtils {
    private ByteRoutingUtils() {
    }

    public static String select(List<ByteBuf> buffers, Map<String, NetworkRoute<byte[]>> routes, int limit, boolean datagram) {
        byte[] prefix = new byte[limit];
        int length = 0;
        for (ByteBuf buffer : buffers) {
            for (int i = buffer.readerIndex(); i < buffer.writerIndex() && length < limit; i++) {
                prefix[length++] = buffer.getByte(i);
            }
        }
        if (length == 0 && !datagram) {
            return null;
        }

        prefix = Arrays.copyOf(prefix, length);
        String selected = null;
        boolean more = false;
        for (Map.Entry<String, NetworkRoute<byte[]>> route : routes.entrySet()) {
            RouteMatch match = route.getValue().match(prefix);
            if (match == RouteMatch.MATCH) {
                if (selected != null) {
                    throw new IllegalArgumentException("Ambiguous network route: " + selected + " and " + route.getKey());
                }
                selected = route.getKey();
            }
            more |= match == RouteMatch.NEED_MORE;
        }

        if (selected != null) {
            return selected;
        }
        if (!datagram && more && length < limit) {
            return null;
        }

        throw new IllegalArgumentException("No route matches the received " + (datagram ? "datagram" : "prefix"));
    }
}
