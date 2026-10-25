/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.realmeteor.environment.execution;
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
import org.junit.runner.Description;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.RunNotifier;
import org.junit.runner.notification.StoppedByUserException;

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

        // 参数实例由原有 JUnit runner 创建；筛选只移除未分配的叶子，保留完整参数名称。
        RunNotifier notifier = new RunNotifier();
        JUnitJobReport report = new JUnitJobReport(output, selection, notifier);
        try (report) {
            report.captureOutput();
            try {
                Class<?>[] types = new Class<?>[classes.size()];
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                for (int index = 0; index < classes.size(); index++) {
                    types[index] = Class.forName(classes.get(index), false, loader);
                }
                Runner runner = Request.classes(types).getRunner();
                Filter selectionFilter = filter(filters);
                selectionFilter.apply(runner);
                if (runner.testCount() == 0) {
                    throw new IllegalArgumentException("JUnit selection contains no testcase");
                }
                // 停止请求只阻止下一个 testcase，当前方法及类的 finally 清理仍由原 runner 完成。
                Result result = new Result();
                notifier.addFirstListener(result.createListener());
                notifier.addListener(report);
                try {
                    notifier.fireTestRunStarted(runner.getDescription());
                    runner.run(notifier);
                } catch (StoppedByUserException stopped) {
                    if (skipError || result.getFailureCount() == 0) {
                        throw stopped;
                    }
                } finally {
                    notifier.fireTestRunFinished(result);
                }
            } catch (Throwable failure) {
                report.executionFailed(failure);
            }
        }
        return report.exitCode();
    }

    static Filter filter(List<String> filters) {
        List<Pattern> patterns = new ArrayList<>();
        for (String value : filters) {
            String expression = Pattern.quote(value).replace("*", "\\E.*\\Q");
            patterns.add(Pattern.compile("^" + expression + "$"));
        }
        return new Filter() {
            @Override
            public boolean shouldRun(Description description) {
                if (!description.getChildren().isEmpty()) {
                    for (Description child : description.getChildren()) {
                        if (this.shouldRun(child)) {
                            return true;
                        }
                    }
                    return false;
                }
                String className = description.getClassName();
                String method = description.getMethodName();
                if (className == null) {
                    return false;
                }
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                for (Pattern pattern : patterns) {
                    boolean wholeClass = pattern.matcher(className).matches() || pattern.matcher(simpleName).matches();
                    if (wholeClass) {
                        return true;
                    }
                    if (method != null && matchesMethod(pattern, className, simpleName, method)) {
                        return true;
                    }
                }
                // 参数构造或 runner 初始化失败必须报告，不能被方法筛选悄悄隐藏。
                return StringUtils.equals("initializationError", method);
            }

            @Override
            public String describe() {
                return "Assigned JUnit classes and methods: " + filters;
            }
        };
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
