/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.realmeteor.environment.execution;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.hasor.cobble.StringUtils;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/** 从一次编译产物运行一个独立 JUnit JVM，工作组只拥有自己的输出和报告。 */
public final class JUnitJobMain {
    private JUnitJobMain() {
    }

    public static void main(String[] arguments) {
        int exitCode;
        try {
            Map<String, Object> selection = arguments(arguments);
            Path output = Path.of((String) selection.remove("output"));
            exitCode = execute(output, selection);
        } catch (Exception failure) {
            failure.printStackTrace(System.err);
            exitCode = 2;
        }
        // 所有 JUnit 工作完成后退出独立 JVM；进程终态和 Compose 资源由外层编排核对。
        System.exit(exitCode);
    }

    static int execute(Path output, Map<String, Object> selection) throws Exception {
        List<String> classes = strings(selection.get("classNames"));
        List<String> filters = strings(selection.get("includes"));
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("No JUnit classes selected");
        }
        if (new LinkedHashSet<>(classes).size() != classes.size()) {
            throw new IllegalArgumentException("JUnit class selection contains duplicates");
        }
        if (filters.isEmpty()) {
            filters = new ArrayList<>(classes);
        }
        selection.put("classNames", classes);
        selection.put("includes", filters);
        Object configured = selection.get("skipError");
        if (configured != null && !(configured instanceof Boolean)) {
            throw new IllegalArgumentException("skipError must be true or false");
        }
        boolean skipError = configured == null || (Boolean) configured;
        selection.put("skipError", skipError);

        // Jupiter 在执行期展开参数向量；监听器使用 UniqueId 跟踪原始节点与完整场景名称。
        JUnitJobReport report = new JUnitJobReport(output, selection);
        JUnitJobReport previous = JUnitJobCondition.current();
        try (report) {
            report.captureOutput();
            JUnitJobCondition.current(report);
            try {
                var request = LauncherDiscoveryRequestBuilder.request() //
                        .selectors(classes.stream().map(DiscoverySelectors::selectClass).toList()) //
                        .configurationParameter("junit.jupiter.execution.parallel.enabled", "false") //
                        .configurationParameter("junit.jupiter.extensions.autodetection.enabled", "true") //
                        .build();
                var launcher = LauncherFactory.create();
                TestPlan plan = launcher.discover(request);
                launcher.execute(plan, report);
                String rpc = String.valueOf(selection.getOrDefault("rpc", ""));
                if (!report.hasSelectedCases() && rpc.isBlank()) {
                    throw new IllegalArgumentException("JUnit selection contains no testcase");
                }
            } catch (Throwable failure) {
                report.executionFailed(failure);
            } finally {
                JUnitJobCondition.current(previous);
            }
        }
        return report.exitCode();
    }

    static boolean matchesSelection(List<String> filters, String className, String method) {
        String simpleName = className.substring(className.lastIndexOf('.') + 1);
        for (String value : filters) {
            String expression = Pattern.quote(value).replace("*", "\\E.*\\Q");
            Pattern pattern = Pattern.compile("^" + expression + "$");
            if (pattern.matcher(className).matches() || pattern.matcher(simpleName).matches()) {
                return true;
            }
            if (matchesMethod(pattern, className, simpleName, method)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesMethod(Pattern pattern, String className, String simpleName, String method) {
        if (pattern.matcher(className + "." + method).matches()
                || pattern.matcher(simpleName + "." + method).matches()) {
            return true;
        }
        int parameter = method.indexOf('[');
        if (parameter < 0) {
            return false;
        }
        String declaration = method.substring(0, parameter);
        return pattern.matcher(className + "." + declaration).matches()
                || pattern.matcher(simpleName + "." + declaration).matches();
    }

    private static Map<String, Object> arguments(String[] arguments) throws IOException {
        Path classesFile = null;
        Path filtersFile = null;
        Path output = null;
        Path rpcCasesFile = null;
        String rpc = "";
        String rpcVersion = "";
        boolean skipError = true;
        List<String> extraFilters = new ArrayList<>();
        for (int index = 0; index < arguments.length; index++) {
            String option = arguments[index];
            if (++index >= arguments.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            String value = arguments[index];
            switch (option) {
                case "--output" -> output = Path.of(value).toAbsolutePath();
                case "--classes-file" -> classesFile = Path.of(value);
                case "--filters-file" -> filtersFile = Path.of(value);
                case "--tests" -> extraFilters.add(value);
                case "--rpc" -> rpc = value;
                case "--rpc-version" -> rpcVersion = value;
                case "--rpc-cases-file" -> rpcCasesFile = Path.of(value);
                case "--skipError" -> {
                    boolean enabled = StringUtils.equals("true", value);
                    boolean disabled = StringUtils.equals("false", value);
                    if (!enabled && !disabled) {
                        throw new IllegalArgumentException("--skipError must be true or false: " + value);
                    }
                    skipError = enabled;
                }
                default -> throw new IllegalArgumentException("Unknown JUnit job option: " + option);
            }
        }
        if (output == null || classesFile == null) {
            throw new IllegalArgumentException("Required options: --output DIR --classes-file FILE");
        }

        // 运行身份从编排传入；不存在默认复用的历史目录或扫描“最近结果”的逻辑。
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("output", output.toString());
        selection.put("orchestrationRunId", identity("meteor.test.run.id", "METEOR_TEST_RUN_ID"));
        selection.put("groupId", identity("meteor.test.group.id", "METEOR_TEST_GROUP_ID"));
        selection.put("skipError", skipError);
        selection.put("rpc", rpc);
        selection.put("rpcVersion", rpcVersion);
        Map<String, Object> rpcCases = new LinkedHashMap<>();
        if (!rpc.isBlank()) {
            if (rpcCasesFile == null) {
                throw new IllegalArgumentException("--rpc requires --rpc-cases-file");
            }
            rpcCases = new ObjectMapper().readValue(rpcCasesFile.toFile(),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
        } else if (!rpcVersion.isBlank() || rpcCasesFile != null) {
            throw new IllegalArgumentException("RPC version and declarations require --rpc");
        }
        selection.put("rpcCases", rpcCases);
        selection.put("classNames", lines(classesFile));
        List<String> filters = filtersFile == null ? new ArrayList<>() : lines(filtersFile);
        filters.addAll(extraFilters);
        selection.put("includes", new ArrayList<>(new LinkedHashSet<>(filters)));
        return selection;
    }

    private static String identity(String property, String environment) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("JUnit job identity is required: " + property);
        }
        return value;
    }

    private static List<String> lines(Path file) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?>) {
            for (Object entry : (List<?>) value) {
                result.add((String) entry);
            }
        }
        return result;
    }
}
