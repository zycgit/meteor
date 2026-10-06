/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpVersion;

class HttpMessages {
    public static boolean keepAlive(HttpVersion version, HttpHeaders headers) {
        boolean keep = version.isKeepAliveDefault();
        for (String value : headers.getValues("Connection")) {
            for (String token : value.split(",")) {
                if (StringUtils.equalsIgnoreCase("close", token.trim())) {
                    return false;
                }
                if (StringUtils.equalsIgnoreCase("keep-alive", token.trim())) {
                    keep = true;
                }
            }
        }
        return keep;
    }

    public static Map<String, String> headers(HttpHeaders source) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : source.headerNames()) {
            headers.put(name, source.getString(name));
        }
        return headers;
    }

    public static byte[] body(ByteBuf source) {
        byte[] bytes = new byte[source.readableBytes()];
        source.getBytes(source.readerIndex(), bytes);
        return bytes;
    }
}
