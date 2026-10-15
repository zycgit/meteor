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
import net.hasor.meteor.connector.RouteDefinition;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.neta.bytebuf.ByteBuf;

/** Non-consuming bounded stream probes; datagrams are inspected as complete messages. */
public final class RouteByteUtils {
    private RouteByteUtils() {
    }

    public static String select(List<ByteBuf> buffers, Map<String, RouteDefinition<byte[]>> routes, //
            int limit, boolean datagram) {
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
        for (Map.Entry<String, RouteDefinition<byte[]>> route : routes.entrySet()) {
            RouteProbeResult probe = route.getValue().probe(prefix);
            if (probe == RouteProbeResult.MATCH) {
                if (selected != null) {
                    String message = "Ambiguous network route: " + selected + " and " + route.getKey();
                    throw new IllegalArgumentException(message);
                }
                selected = route.getKey();
            }
            more |= probe == RouteProbeResult.NEED_MORE;
        }

        if (!datagram && more) {
            if (length < limit) {
                return null;
            }
            throw new IllegalArgumentException("Network route remains unresolved at probe limit");
        }
        if (selected != null) {
            return selected;
        }

        throw new IllegalArgumentException("No route matches the received " + (datagram ? "datagram" : "prefix"));
    }
}
