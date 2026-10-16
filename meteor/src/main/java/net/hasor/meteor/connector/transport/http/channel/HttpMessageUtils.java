/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http.channel;
import java.util.*;
import java.util.concurrent.CancellationException;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.meteor.connector.RouteSelection;
import net.hasor.neta.bytebuf.ByteBuf;
import net.hasor.neta.codec.http.HttpHeaders;
import net.hasor.neta.codec.http.HttpVersion;

public final class HttpMessageUtils {
    private HttpMessageUtils() {
    }

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

    public static Map<String, List<String>> headers(HttpHeaders source) {
        Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : source.headerNames()) {
            headers.put(name, List.copyOf(source.getValues(name)));
        }
        return headers;
    }

    static Map<String, List<String>> copy(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        source.forEach((name, values) -> {
            copy.computeIfAbsent(name, ignored -> new ArrayList<>()).addAll(values);
        });

        copy.replaceAll((name, values) -> List.copyOf(values));
        return Collections.unmodifiableMap(copy);
    }

    public static void append(Map<String, List<String>> target, HttpHeaders source) {
        for (String name : source.headerNames()) {
            target.computeIfAbsent(name, ignored -> new ArrayList<>()).addAll(source.getValues(name));
        }
    }

    public static void write(Map<String, List<String>> source, HttpHeaders target) {
        source.forEach((name, values) -> {
            for (String value : values) {
                target.addHeader(name, value);
            }
        });
    }

    static String first(Map<String, List<String>> source, String name) {
        List<String> values = source.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public static int expectation(HttpRequest request) {
        if (request.stream() == null) {
            return 0;
        }

        int status = 0;
        for (String value : request.headers().getOrDefault("Expect", List.of())) {
            for (String token : value.split(",")) {
                if (!StringUtils.equalsIgnoreCase("100-continue", token.trim())) {
                    return 417;
                }
                status = 100;
            }
        }
        return status;
    }

    public static byte[] body(ByteBuf source) {
        byte[] bytes = new byte[source.readableBytes()];
        source.getBytes(source.readerIndex(), bytes);
        return bytes;
    }

    public static Throwable writeFailure(Future<?> future) {
        return future.isCancelled() ? new CancellationException("HTTP write cancelled") : future.getCause();
    }

    public static void route(HttpRequest request, RouteSelection route) {
        request.route(route);
    }
}
