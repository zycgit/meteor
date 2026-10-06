/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.transport.http;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Fully buffered HTTP request. Headers are case insensitive; no engine-owned buffers escape.
 */
public record HttpRequest(String method, String uri, Map<String, String> headers, byte[] body) {
    public HttpRequest {
        Objects.requireNonNull(method);
        Objects.requireNonNull(uri);
        Map<String, String> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        copy.putAll(headers);
        headers = Collections.unmodifiableMap(copy);
        body = body.clone();
    }
}
