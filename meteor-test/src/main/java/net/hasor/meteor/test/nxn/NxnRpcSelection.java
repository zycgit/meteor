package net.hasor.meteor.test.nxn;
import net.hasor.cobble.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** 从测试声明选择 RPC 场景，不从类名或 SDK 版本推测协议。 */
public final class NxnRpcSelection {
    private NxnRpcSelection() {
    }

    public static String protocolName(String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "grpcweb" -> "grpc-web";
            case "json-rpc" -> "jsonrpc";
            case "xml-rpc" -> "xmlrpc";
            case "onc-rpc", "onc rpc" -> "oncrpc";
            default -> normalized;
        };
    }

    /** 每个协议的版本只来自注解；空字符串表示场景未声明版本。 */
    public static Map<String, List<String>> catalog(Map<String, Object> metadata) {
        Map<String, Set<String>> declared = new TreeMap<>();
        for (Object methods : metadata.values()) {
            for (Object declarations : object(methods).values()) {
                for (Map<String, Object> declaration : declarations(declarations)) {
                    String name = protocolName(required(declaration, "name"));
                    String version = text(declaration.get("version"));
                    declared.computeIfAbsent(name, ignored -> new TreeSet<>()).add(version);
                }
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        declared.forEach((name, versions) -> result.put(name, new ArrayList<>(versions)));
        return result;
    }

    static Map<String, List<String>> select(Map<String, List<String>> classes, Map<String, Object> metadata,
            String rpc, String version) {
        Map<String, List<String>> available = catalog(metadata);
        String name = protocolName(rpc);
        if (available.isEmpty()) {
            throw new IllegalArgumentException("RPC selection requires exported @NxnRpc metadata");
        }
        if (!available.containsKey(name)) {
            throw new IllegalArgumentException("Unknown RPC protocol: " + rpc + "; available: " + available.keySet());
        }
        if (!version.isEmpty() && !available.get(name).contains(version)) {
            throw new IllegalArgumentException("Unknown version for RPC " + name + ": " + version
                    + "; declared versions: " + available.get(name));
        }

        // 类先按声明的方法缩小范围，实际参数由 Jupiter 展开后使用同一份声明筛选。
        Map<String, List<String>> selected = new TreeMap<>();
        for (Map.Entry<String, List<String>> entry : classes.entrySet()) {
            String className = entry.getKey();
            Map<String, Object> methods = object(metadata.get(className));
            Set<String> filters = new LinkedHashSet<>();
            for (Map.Entry<String, Object> method : methods.entrySet()) {
                if (!matchesProtocol(method.getValue(), name, version)) {
                    continue;
                }
                String qualified = className + "." + method.getKey();
                if (entry.getValue().isEmpty()) {
                    filters.add(qualified);
                    continue;
                }
                for (String filter : entry.getValue()) {
                    int parameter = filter.indexOf('[');
                    String methodFilter = parameter < 0 ? filter : filter.substring(0, parameter);
                    if (matches(qualified, methodFilter)) {
                        filters.add(filter);
                    }
                }
            }
            if (!filters.isEmpty()) {
                selected.put(className, new ArrayList<>(filters));
            }
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No @NxnRpc testcase matches the requested RPC and --tests selection");
        }
        return selected;
    }

    /** 参数名保持 Jupiter 的原始显示内容，普通测试用空字符串匹配。 */
    public static boolean selected(Map<String, Object> metadata, String rpc, String version,
            String className, String methodName, List<String> parameterNames) {
        if (rpc == null || rpc.isBlank()) {
            return true;
        }
        String name = protocolName(rpc);
        String requestedVersion = version == null ? "" : version;
        Map<String, Object> methods = object(metadata.get(className));
        List<Map<String, Object>> declarations = declarations(methods.get(methodName));
        Collection<String> names = parameterNames.isEmpty() ? List.of("") : parameterNames;
        for (Map<String, Object> declaration : declarations) {
            if (!matchesProtocol(declaration, name, requestedVersion)) {
                continue;
            }
            String pattern = text(declaration.getOrDefault("cases", "*"));
            for (String parameterName : names) {
                if (matches(parameterName, pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean matches(String value, String pattern) {
        // 方括号属于参数名称；只有星号表示通配符。
        String[] literals = pattern.split("\\*", -1);
        StringBuilder expression = new StringBuilder("^");
        for (int index = 0; index < literals.length; index++) {
            if (index > 0) {
                expression.append(".*");
            }
            expression.append(Pattern.quote(literals[index]));
        }
        return value.matches(expression.append('$').toString());
    }

    private static boolean matchesProtocol(Object values, String name, String version) {
        for (Map<String, Object> declaration : declarations(values)) {
            if (matchesProtocol(declaration, name, version)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesProtocol(Map<String, Object> declaration, String name, String version) {
        String declaredName = protocolName(required(declaration, "name"));
        if (!StringUtils.equals(name, declaredName)) {
            return false;
        }
        return version.isEmpty() || StringUtils.equals(version, text(declaration.get("version")));
    }

    private static String required(Map<String, Object> declaration, String field) {
        String value = text(declaration.get(field));
        if (value.isBlank()) {
            throw new IllegalArgumentException("@NxnRpc " + field + " must not be empty");
        }
        return value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Exported RPC metadata must contain method maps");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> declarations(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Exported RPC declarations must be arrays");
        }
        for (Object declaration : (List<?>) value) {
            if (!(declaration instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Each exported RPC declaration must be an object");
            }
        }
        return (List<Map<String, Object>>) value;
    }
}
