package net.hasor.meteor.test.nxn;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import net.hasor.cobble.StringUtils;

/** 从已编译测试声明导出执行清单，不运行测试发现、初始化或参数工厂。 */
final class NxnRuntime {
    private NxnRuntime() {
    }

    static int run(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Expected export SEED_JSON DEST_JSON");
        }
        Map<String, Object> runtime = NxnPlan.readObject(Path.of(arguments[0]));
        List<String> directories = NxnPlan.strings(runtime.remove("classDirectories"));
        if (directories == null || directories.isEmpty()) {
            throw new IllegalArgumentException("Runtime classDirectories must not be empty");
        }

        // 只扫描当前工程的顶层类型，不把依赖或嵌套 fixture 当成独立场景。
        Set<String> candidates = new TreeSet<>();
        for (String directory : directories) {
            Path root = Path.of(directory);
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Test class directory does not exist: " + root);
            }
            try (Stream<Path> entries = Files.walk(root)) {
                entries.filter(Files::isRegularFile).filter(NxnRuntime::isTopLevelClass).forEach(path -> {
                    String relative = root.relativize(path).toString();
                    String name = relative.substring(0, relative.length() - ".class".length());
                    candidates.add(name.replace('/', '.').replace('\\', '.'));
                });
            }
        }

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = NxnRuntime.class.getClassLoader();
        }
        List<String> classes = new ArrayList<>();
        Map<String, Object> rpcCases = new TreeMap<>();
        Map<String, Object> testEntries = new TreeMap<>();
        for (String name : candidates) {
            Class<?> type = Class.forName(name, false, loader);
            List<Method> tests = Arrays.stream(type.getDeclaredMethods()).filter(NxnRuntime::isTest).toList();
            if (tests.isEmpty()) {
                continue;
            }
            classes.add(name);
            testEntries.put(name, tests.stream().map(Method::getName).distinct().sorted().toList());

            // 方法声明覆盖类声明；只读取注解，参数向量仍由实际 JUnit 进程展开。
            NxnRpc[] classDeclarations = type.getDeclaredAnnotationsByType(NxnRpc.class);
            Map<String, Object> methods = new TreeMap<>();
            for (Method test : tests) {
                NxnRpc[] declarations = test.getDeclaredAnnotationsByType(NxnRpc.class);
                if (declarations.length == 0) {
                    declarations = classDeclarations;
                }
                List<Map<String, String>> metadata = new ArrayList<>();
                for (NxnRpc declaration : declarations) {
                    Map<String, String> details = new LinkedHashMap<>();
                    details.put("name", declaration.name());
                    details.put("version", declaration.version());
                    details.put("cases", declaration.cases());
                    metadata.add(details);
                }
                if (!metadata.isEmpty()) {
                    methods.put(test.getName(), metadata);
                }
            }
            if (!methods.isEmpty()) {
                rpcCases.put(name, methods);
            }
        }
        if (classes.isEmpty()) {
            throw new IllegalArgumentException("No JUnit @Test classes found in classDirectories");
        }
        runtime.put("classNames", classes);
        runtime.put("rpcCases", rpcCases);
        Path testDirectory = Path.of(String.valueOf(runtime.getOrDefault("workingDirectory", ".")));
        if (Files.isDirectory(testDirectory.resolve("resource/matrix"))) {
            // 各语言并行构建；等全部运行产物就绪后由计划阶段收集，避免 Java 构建依赖外语构建顺序。
            runtime.put("matrixDirectory", testDirectory.toAbsolutePath().normalize().toString());
            runtime.put("testEntries", testEntries);
        }
        NxnPlan.writeJson(Path.of(arguments[1]), runtime);
        return 0;
    }

    static void declareMatrixProtocols(Map<String, Object> metadata, List<Map<String, Object>> cases) {
        for (Map<String, Object> testCase : cases) {
            String identity = String.valueOf(testCase.get("caseId"));
            int separator = identity.indexOf('#');
            int parameter = identity.indexOf('[', separator);
            String type = identity.substring(0, separator);
            String method = identity.substring(separator + 1, parameter);
            Map<String, Object> methods = NxnPlan.object(metadata.computeIfAbsent(type, ignored -> new TreeMap<>()));
            @SuppressWarnings("unchecked") List<Map<String, Object>> declarations = (List<Map<String, Object>>) methods.computeIfAbsent(method, ignored -> new ArrayList<>());
            List<String> versions = NxnPlan.strings(testCase.getOrDefault("protocolVersions", List.of()));
            if (versions.isEmpty()) {
                versions = List.of(String.valueOf(testCase.getOrDefault("protocolVersion", "")));
            }
            for (String version : versions) {
                declarations.add(Map.of("name", testCase.get("protocol"), "version", version, "cases", testCase.get("id")));
            }
        }
    }

    private static boolean isTopLevelClass(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".class") && !name.contains("$") && !StringUtils.equals("package-info.class", name) && !StringUtils.equals("module-info.class", name);
    }

    private static boolean isTest(Method method) {
        return Arrays.stream(method.getDeclaredAnnotations()).anyMatch(annotation -> StringUtils.equals("org.junit.jupiter.api.Test", annotation.annotationType().getName()));
    }
}
