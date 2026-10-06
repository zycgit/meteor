/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import java.nio.charset.StandardCharsets;

/**
 * RSF string fields use UTF-8 consistently across hosts.
 */
public final class WireStrings {
    private WireStrings() {
    }

    public static byte[] fromCache(String text) {
        return text == null ? null : text.getBytes(StandardCharsets.UTF_8);
    }

    public static String fromCache(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}