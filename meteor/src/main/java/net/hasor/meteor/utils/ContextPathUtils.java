/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.utils;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;

/** 协议挂载路径的配置编码和规范化，不涉及服务或网络连接。 */
public final class ContextPathUtils {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ContextPathUtils() {
    }

    /** 只接受绝对路径，去掉尾部斜杠，根路径仍为 /。 */
    public static String normalize(String path) {
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Invalid context path: " + path);
        }

        try {
            URI uri = new URI(path);
            if (!StringUtils.equals(path, uri.getRawPath())) {
                String message = "Context must be a path without authority, query or fragment: " + path;
                throw new IllegalArgumentException(message);
            }
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("Invalid context path: " + path, failure);
        }

        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }

    /** 额外挂载独立保存，增删它们不会改变默认 contextPath。 */
    public static void addContextPath(Map<String, String> options, String path) {
        addPath(options, "contextPaths", normalize(path));
    }

    /** 精确地址保留尾部斜杠；它与不带尾部斜杠的地址不同。 */
    public static String exactPath(String path) {
        normalize(path);
        return path;
    }

    private static void addPath(Map<String, String> options, String key, String path) {
        Set<String> additional = new LinkedHashSet<>(paths(options, key));
        additional.add(path);
        try {
            options.put(key, MAPPER.writeValueAsString(additional));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Cannot encode context paths", failure);
        }
    }

    /** 默认路径在首位；没有 HTTP context 配置时返回空列表。 */
    public static List<String> contextPaths(Map<String, String> options) {
        Set<String> paths = new LinkedHashSet<>();
        String defaultPath = options.get("contextPath");
        if (defaultPath != null) {
            paths.add(normalize(defaultPath));
        }
        paths.addAll(paths(options, "contextPaths"));
        return List.copyOf(paths);
    }

    private static List<String> paths(Map<String, String> options, String key) {
        String encoded = options.get(key);
        if (encoded == null) {
            return List.of();
        }

        try {
            JsonNode values = MAPPER.readTree(encoded);
            if (values == null || !values.isArray()) {
                throw new IllegalArgumentException(key + " must be a JSON array of paths");
            }
            List<String> paths = new ArrayList<>();
            for (JsonNode value : values) {
                if (!value.isTextual()) {
                    throw new IllegalArgumentException(key + " must contain only paths");
                }
                paths.add(normalize(value.textValue()));
            }
            return paths;
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid " + key, failure);
        }
    }

}
