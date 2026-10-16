/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.hasor.cobble.StringUtils;

/** 业务键使用可逆编码；Naming 集群名只使用 Nacos 接受的安全字符。 */
final class NacosKeyUtils {
    private NacosKeyUtils() {
    }

    static String encode(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Registry identity must not be blank");
        }
        StringBuilder encoded = new StringBuilder();
        for (byte value : id.getBytes(StandardCharsets.UTF_8)) {
            int ch = value & 0xff;
            boolean letter = ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z';
            boolean digit = ch >= '0' && ch <= '9';
            if (letter || digit || ch == '-') {
                encoded.append((char) ch);
            } else {
                encoded.append('_').append(HexFormat.of().toHexDigits(value));
            }
        }
        return encoded.toString();
    }

    static String cluster(String id) {
        if (StringUtils.isBlank(id)) {
            throw new IllegalArgumentException("Registry identity must not be blank");
        }

        boolean escaped = false;
        StringBuilder encoded = new StringBuilder();
        for (byte value : id.getBytes(StandardCharsets.UTF_8)) {
            int ch = value & 0xff;
            boolean letter = ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z';
            boolean digit = ch >= '0' && ch <= '9';
            if (letter || digit) {
                encoded.append((char) ch);
            } else {
                escaped = true;
                encoded.append('-').append(HexFormat.of().toHexDigits(value));
            }
        }
        if (escaped) {
            encoded.insert(0, 'x');
        }
        if (encoded.length() <= 64) {
            return encoded.toString();
        }

        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    static String application(String id) {
        return "applications." + encode(id);
    }

    static String instance(String id) {
        return "instances." + encode(id);
    }

    static String service(String id) {
        return "services." + encode(id);
    }

    static String governance(String instanceId, String serviceId) {
        if (instanceId == null) {
            return serviceId == null ? "governance.global" : "governance." + service(serviceId);
        }
        if (serviceId == null) {
            return "governance." + instance(instanceId);
        }
        return "governance." + service(serviceId) + "." + instance(instanceId);
    }
}
