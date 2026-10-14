/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import net.hasor.cobble.StringUtils;

/** 业务 ID 的可逆编码；句点只用于分隔节点，不参与实体散列。 */
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
