package net.hasor.meteor.test.nxn;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Compile the selected test work into independent, isolated JUnit JVM groups. */
final class NxnPlan {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String LAUNCHER = "net.hasor.realmeteor.environment.execution.JUnitJobMain";
    static final String NATIVE_REGISTRY_PACKAGE = "net.hasor.realmeteor.registry.dubbo.";
    static final String NATIVE_REGISTRY_EXAMPLE = "net.hasor.realmeteor.example.sdk.NativeRegistryScenarioTest";
    static final String NATIVE_REGISTRY_FIXTURE = "net.hasor.realmeteor.environment.storage.RegistryServiceReuseTest";
    static final Map<String, List<String>> METHOD_PARTITIONS = new LinkedHashMap<>();
    private static final Map<String, String> MUTABLE_PROPERTIES = new LinkedHashMap<>();

    static {
        // The existing direction methods are the only permitted method partition boundaries.
        METHOD_PARTITIONS.put("net.hasor.realmeteor.protocol.thrift.python.PythonThriftTypeInteropTest", Arrays.asList(
                "meteorProviderPreservesPythonNativeType", "meteorConsumerPreservesPythonNativeType"));
        METHOD_PARTITIONS.put("net.hasor.realmeteor.matrix.types.ThriftGoTypeMatrixTest", Arrays.asList(
                "goConsumerPreservesSelectedTypeOnMeteorProvider", "meteorConsumerPreservesSelectedTypeOnGoProvider"));
        MUTABLE_PROPERTIES.put("java.peer.output.directory", "peer-logs/java");
        MUTABLE_PROPERTIES.put("dubbo.peer.output.directory", "peer-logs/dubbo");
        MUTABLE_PROPERTIES.put("hessian.peer.output.directory", "peer-logs/hessian");
        MUTABLE_PROPERTIES.put("soap.peer.output.directory", "peer-logs/soap");
        MUTABLE_PROPERTIES.put("thrift.peer.output.directory", "peer-logs/thrift");
        MUTABLE_PROPERTIES.put("meteor.test.sdk.logDirectory", "peer-logs/sdk");
        MUTABLE_PROPERTIES.put("python.grpc.logs", "peer-logs/python-grpc");
        MUTABLE_PROPERTIES.put("python.thrift.logs", "peer-logs/python-thrift");
        MUTABLE_PROPERTIES.put("python.soap.logs", "peer-logs/python-soap");
        MUTABLE_PROPERTIES.put("oncrpc.peer.usage.file", "oncrpc-native-process-usage.jsonl");
        MUTABLE_PROPERTIES.put("oncrpc.peer.output.directory", "peer-logs/oncrpc");
        MUTABLE_PROPERTIES.put("go.order.peer.work-dir", "peer-logs/go");
        MUTABLE_PROPERTIES.put("meteor.test.container.workDirectory", "container-scenarios");
        MUTABLE_PROPERTIES.put("meteor.test.runDirectory", "interoperability");
        MUTABLE_PROPERTIES.put("meteor.test.output.directory", ".");
        MUTABLE_PROPERTIES.put("meteor.test.evidence.directory", "process-evidence");
        MUTABLE_PROPERTIES.put("java.io.tmpdir", "process/tmp");
    }

    private NxnPlan() {
    }

    static int parseGroupRequest(String value) {
        if (value == null || !value.matches("[1-9]|1[0-6]")) {
            throw new IllegalArgumentException("groups must be an integer between 1 and 16");
        }
        return Integer.parseInt(value);
    }

    static boolean matches(String value, String pattern) {
        // Parameter brackets are literal; '*' is the only wildcard supported by JUnit selection.
        StringBuilder expression = new StringBuilder("^");
        String[] literals = pattern.split("\\*", -1);
        for (int index = 0; index < literals.length; index++) {
            if (index > 0) {
                expression.append(".*");
            }
            expression.append(Pattern.quote(literals[index]));
        }
        return value.matches(expression.append('$').toString());
    }

    static Map<String, List<String>> selectClasses(List<String> includes, List<String> classes) {
        if (classes.isEmpty() || new LinkedHashSet<>(classes).size() != classes.size()) {
            throw new IllegalArgumentException("Runtime classNames must be nonempty and unique");
        }
        Map<String, List<String>> selected = new TreeMap<>();
        if (includes.isEmpty()) {
            for (String name : classes) {
                selected.put(name, new ArrayList<>());
            }
            return selected;
        }

        // Resolve class ownership here; JUnit still interprets method and parameter suffixes.
        for (String pattern : includes) {
            boolean matched = false;
            for (String name : classes) {
                String simple = name.substring(name.lastIndexOf('.') + 1);
                if (matches(name, pattern) || matches(simple, pattern)) {
                    selected.put(name, new ArrayList<>());
                    matched = true;
                    continue;
                }
                for (String prefix : Arrays.asList(name, simple)) {
                    String tail;
                    if (pattern.startsWith(prefix + ".")) {
                        tail = pattern.substring(prefix.length() + 1);
                    } else {
                        int separator = pattern.lastIndexOf('.');
                        if (separator < 0) {
                            continue;
                        }
                        String head = pattern.substring(0, separator);
                        if (!matches(name, head) && !matches(simple, head)) {
                            continue;
                        }
                        tail = pattern.substring(separator + 1);
                    }
                    String methodFilter = name + "." + tail;
                    if (!selected.containsKey(name)) {
                        List<String> filters = new ArrayList<>();
                        filters.add(methodFilter);
                        selected.put(name, filters);
                    } else if (!selected.get(name).isEmpty() && !selected.get(name).contains(methodFilter)) {
                        selected.get(name).add(methodFilter);
                    }
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                throw new IllegalArgumentException("No JUnit class matches --tests " + pattern);
            }
        }
        selected.values().forEach(filters -> filters.sort(String::compareTo));
        return selected;
    }

    static List<Map<String, Object>> partition(List<String> classes, Map<String, Object> weights, int count,
            Map<String, Object> methodWeights, Map<String, List<String>> classFilters) {
        List<Map<String, Object>> units = new ArrayList<>();
        Map<String, Object> nativeUnit = unit(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0);
        List<String> ordered = new ArrayList<>(classes);
        ordered.sort(String::compareTo);
        for (String name : ordered) {
            double seconds = duration(weights.getOrDefault(name, 1), name);
            List<String> requested = classFilters.getOrDefault(name, new ArrayList<>());
            List<String> methods = requested.isEmpty() ? METHOD_PARTITIONS.get(name) : null;
            List<String> selections = methods == null ? Arrays.asList((String) null) : methods;
            for (String method : selections) {
                String selection = method == null ? name : name + "." + method;
                List<String> filters = requested.isEmpty() ? Arrays.asList(selection) : requested;
                String include = method == null ? selection : selection + "*";
                List<String> includes = requested.isEmpty() ? Arrays.asList(include) : requested;
                double unitSeconds = seconds;
                if (method != null) {
                    Map<String, Object> perMethod = object(methodWeights.get(name));
                    unitSeconds = duration(perMethod.getOrDefault(method, seconds / methods.size()), selection);
                }
                Map<String, Object> unit = unit(Arrays.asList(name), filters, includes, unitSeconds);
                if (nativeRegistryClass(name)) {
                    strings(nativeUnit.get("classes")).add(name);
                    strings(nativeUnit.get("filters")).addAll(filters);
                    strings(nativeUnit.get("includes")).addAll(includes);
                    nativeUnit.put("seconds", number(nativeUnit.get("seconds")) + unitSeconds);
                } else {
                    units.add(unit);
                }
            }
        }
        if (!strings(nativeUnit.get("classes")).isEmpty()) {
            units.add(nativeUnit);
        }

        // Native registry tests share one serial JVM lane; other units use the lightest compatible lane.
        List<Map<String, Object>> groups = new ArrayList<>();
        for (int index = 0; index < Math.min(count, units.size()); index++) {
            Map<String, Object> group = unit(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), 0);
            group.remove("seconds");
            group.put("expectedSeconds", 0.0);
            groups.add(group);
        }
        units.sort(Comparator.<Map<String, Object>>comparingDouble(item -> -number(item.get("seconds")))
                .thenComparing(item -> String.join("\u0000", strings(item.get("filters")))));
        for (Map<String, Object> unit : units) {
            List<Map<String, Object>> candidates = new ArrayList<>();
            for (Map<String, Object> group : groups) {
                Set<String> overlap = new LinkedHashSet<>(strings(group.get("classes")));
                overlap.retainAll(strings(unit.get("classes")));
                if (overlap.isEmpty()) {
                    candidates.add(group);
                }
            }
            if (candidates.isEmpty()) {
                candidates = groups;
            }
            Map<String, Object> target = candidates.stream()
                    .min(Comparator.comparingDouble(item -> number(item.get("expectedSeconds")))).orElseThrow();
            for (String name : strings(unit.get("classes"))) {
                if (!strings(target.get("classes")).contains(name)) {
                    strings(target.get("classes")).add(name);
                }
            }
            strings(target.get("filters")).addAll(strings(unit.get("filters")));
            strings(target.get("includes")).addAll(strings(unit.get("includes")));
            target.put("expectedSeconds", number(target.get("expectedSeconds")) + number(unit.get("seconds")));
        }
        for (Map<String, Object> group : groups) {
            strings(group.get("classes")).sort(String::compareTo);
            strings(group.get("filters")).sort(String::compareTo);
            strings(group.get("includes")).sort(String::compareTo);
        }
        return groups;
    }

    private static Map<String, Object> unit(Collection<String> classes, Collection<String> filters,
            Collection<String> includes, double seconds) {
        Map<String, Object> unit = new LinkedHashMap<>();
        unit.put("classes", new ArrayList<>(classes));
        unit.put("filters", new ArrayList<>(filters));
        unit.put("includes", new ArrayList<>(includes));
        unit.put("seconds", seconds);
        return unit;
    }

    private static double duration(Object value, String selection) {
        double seconds = Double.parseDouble(String.valueOf(value));
        if (!Double.isFinite(seconds) || seconds < 0) {
            throw new IllegalArgumentException("Invalid duration weight for " + selection);
        }
        return seconds;
    }

    private static boolean nativeRegistryClass(String name) {
        return name.startsWith(NATIVE_REGISTRY_PACKAGE) || StringUtils.equals(name, NATIVE_REGISTRY_EXAMPLE)
                || StringUtils.equals(name, NATIVE_REGISTRY_FIXTURE);
    }

    static Map<String, String> systemProperties(Map<String, Object> runtime, Map<String, Object> group, String runId,
            Map<String, String> matrix) {
        Map<String, String> properties = new TreeMap<>();
        object(runtime.get("systemProperties")).forEach((name, value) -> properties.put(name, String.valueOf(value)));
        Path directory = Path.of((String) group.get("outputDirectory"));
        MUTABLE_PROPERTIES.forEach((name, relative) -> {
            Path scoped = directory.resolve(relative).normalize();
            properties.put(name, scoped.toString());
        });
        properties.putAll(matrix);
        String groupId = (String) group.get("id");
        properties.put("meteor.test.run.id", runId);
        properties.put("meteor.test.group.id", groupId);
        Path runDirectory = directory.resolve("interoperability");
        properties.put("meteor.test.runDirectory", runDirectory.toString());
        return properties;
    }

    static List<String> javaCommand(Map<String, Object> runtime, Map<String, Object> group, String runId,
            Map<String, String> matrix, String workspace) {
        List<String> command = new ArrayList<>();
        // Module access comes from the exported runtime; heap and CPU budgets remain fixed per JUnit JVM.
        command.add((String) runtime.getOrDefault("javaExecutable", "java"));
        command.addAll(strings(runtime.getOrDefault("jvmArguments", new ArrayList<>())));
        command.add("-Xmx1536m");
        command.add("-XX:ActiveProcessorCount=2");
        Map<String, String> properties = systemProperties(runtime, group, runId, matrix);
        properties.forEach((name, value) -> command.add("-D" + name + "=" + value));
        command.add("-Duser.dir=" + workspace);
        command.add("-cp");
        command.add(String.join(":", strings(runtime.get("classpath"))));
        command.add(LAUNCHER);
        command.add("--output");
        command.add((String) group.get("outputDirectory"));
        command.add("--classes-file");
        command.add((String) group.get("classesFile"));
        command.add("--filters-file");
        command.add((String) group.get("filtersFile"));
        command.add("--skipError");
        command.add(String.valueOf(group.getOrDefault("skipError", Boolean.TRUE)));
        return command;
    }

    static Options parseArguments(String[] arguments) {
        Options options = new Options();
        String requestedGroups = System.getenv().getOrDefault("METEOR_TEST_GROUPS", "16");
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (argument.startsWith("-Dmeteor.matrix.") && argument.contains("=")) {
                int separator = argument.indexOf('=');
                options.matrix.put(argument.substring(2, separator), argument.substring(separator + 1));
                continue;
            }
            if (Arrays.asList(":meteor-test:test", "--no-daemon", "--console=plain", "--stacktrace", "--rerun-tasks")
                    .contains(argument)) {
                continue;
            }
            int separator = argument.indexOf('=');
            String name = separator < 0 ? argument : argument.substring(0, separator);
            String value;
            if (separator >= 0) {
                value = argument.substring(separator + 1);
            } else if (index + 1 < arguments.length) {
                value = arguments[++index];
            } else {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
            switch (name) {
                case "--runtime": options.runtime = Path.of(value); break;
                case "--output": options.output = Path.of(value); break;
                case "--build-directory": options.buildDirectory = Path.of(value); break;
                case "--weights": options.weights = Path.of(value); break;
                case "--baseline": options.baseline = Path.of(value); break;
                case "--groups": requestedGroups = value; break;
                case "--container": options.container = value; break;
                case "--workspace": options.workspace = value; break;
                case "--process-wrapper": options.processWrapper = value; break;
                case "--run-id": options.runId = value; break;
                case "--tests": options.tests.add(value); break;
                case "--skipError":
                    boolean skip = StringUtils.equals("true", value);
                    if (!skip && !StringUtils.equals("false", value)) {
                        throw new IllegalArgumentException("--skipError must be true or false");
                    }
                    options.skipError = skip;
                    break;
                default: throw new IllegalArgumentException("Unsupported test argument: " + argument);
            }
        }
        options.groups = parseGroupRequest(requestedGroups);
        if (options.runId == null || !options.runId.matches("[A-Za-z0-9][A-Za-z0-9_.-]*")) {
            throw new IllegalArgumentException("--run-id must be a nonempty filesystem-safe identifier");
        }
        return options;
    }

    static Map<String, Object> prepare(Options options, Map<String, Object> runtime) throws IOException {
        if (Files.exists(options.output)) {
            try (Stream<Path> entries = Files.list(options.output)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Group output directory must be empty: " + options.output);
                }
            }
        }
        List<String> classes = new ArrayList<>(strings(runtime.get("classNames")));
        classes.sort(String::compareTo);
        Map<String, List<String>> selected = selectClasses(options.tests, classes);
        Object rawClasspath = runtime.get("classpath");
        if (!(rawClasspath instanceof List) || strings(rawClasspath).isEmpty()) {
            throw new IllegalArgumentException("Runtime classpath must be a nonempty array of paths");
        }
        for (Object path : (List<?>) rawClasspath) {
            if (!(path instanceof String) || ((String) path).isEmpty()) {
                throw new IllegalArgumentException("Runtime classpath must be a nonempty array of paths");
            }
        }
        Map<String, Object> durations = new LinkedHashMap<>();
        if (Files.exists(options.weights)) {
            durations = readObject(options.weights);
        }
        Map<String, Object> classWeights = durations;
        if (durations.containsKey("classes")) {
            classWeights = object(durations.get("classes"));
        }
        List<Map<String, Object>> groups = partition(new ArrayList<>(selected.keySet()), classWeights, options.groups,
                object(durations.get("methods")), selected);
        for (int index = 0; index < groups.size(); index++) {
            Map<String, Object> group = groups.get(index);
            String groupId = "g" + (index + 1);
            Path directory = options.output.toAbsolutePath().normalize().resolve(groupId);
            Files.createDirectories(directory);
            group.put("id", groupId);
            group.put("outputDirectory", directory.toString());
            group.put("classesFile", directory.resolve("classes.txt").toString());
            group.put("filtersFile", directory.resolve("filters.txt").toString());
            group.put("launchFile", directory.resolve("process/launch.json").toString());
            group.put("container", options.container);
            group.put("skipError", options.skipError);
            Files.write(directory.resolve("classes.txt"), strings(group.get("classes")), StandardCharsets.UTF_8);
            Files.write(directory.resolve("filters.txt"), strings(group.get("includes")), StandardCharsets.UTF_8);
            Files.createDirectory(directory.resolve("process-evidence"));
            Files.createDirectory(directory.resolve("container-scenarios"));

            // Native supervision owns the temporary HOME and process tree, not the JUnit group planner.
            Map<String, Object> launch = new LinkedHashMap<>();
            launch.put("processId", options.runId + "-" + groupId + "-junit");
            launch.put("runId", options.runId);
            launch.put("groupId", groupId);
            launch.put("command", javaCommand(runtime, group, options.runId, options.matrix, options.workspace));
            launch.put("workingDirectory", options.workspace);
            launch.put("environment", new LinkedHashMap<>());
            launch.put("unsetEnvironment", new ArrayList<>());
            launch.put("temporaryDirectory", directory.resolve("process/tmp").toString());
            Path launchFile = Path.of((String) group.get("launchFile"));
            writeJson(launchFile, launch);
            NxnProcess.prepareLaunch(launchFile, launch);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 3);
        manifest.put("runId", options.runId);
        manifest.put("skipError", options.skipError);
        manifest.put("runtimeFile", options.runtime.toAbsolutePath().normalize().toString());
        manifest.put("userIncludes", options.tests);
        manifest.put("matrixSelection", options.matrix);
        Map<String, List<String>> methodPartitions = new LinkedHashMap<>();
        METHOD_PARTITIONS.forEach((name, methods) -> {
            if (selected.containsKey(name) && selected.get(name).isEmpty()) {
                methodPartitions.put(name, methods);
            }
        });
        manifest.put("methodPartitions", methodPartitions);
        manifest.put("discoveredClasses", classes);
        manifest.put("selectedClasses", new ArrayList<>(selected.keySet()));
        List<String> selections = new ArrayList<>();
        groups.forEach(group -> selections.addAll(strings(group.get("filters"))));
        selections.sort(String::compareTo);
        manifest.put("selectedSelections", selections);
        manifest.put("requestedGroups", options.groups);
        manifest.put("selectedGroups", groups.size());
        manifest.put("groups", groups);
        manifest.put("isolation", "Independent JVMs and case directories in Compose-owned persistent services");
        return manifest;
    }

    static Map<String, Object> readObject(Path path) throws IOException {
        return JSON.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, Object>>() { });
    }

    static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n";
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return value == null ? new LinkedHashMap<>() : (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Object value) {
        return (List<String>) value;
    }

    static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    static String now() {
        return Instant.now().toString();
    }

    static final class Options {
        Path runtime = Path.of("peer/java/meteor/build/execution/runtime.json");
        Path output = Path.of("build/groups");
        Path buildDirectory = Path.of("build");
        Path weights = Path.of("src/test/resources/matrix/class-durations.json");
        Path baseline;
        int groups = 16;
        boolean skipError = true;
        String container = System.getenv().getOrDefault("METEOR_TEST_JAVA_CONTAINER", "java-meteor");
        String workspace = "/workspace/meteor/meteor-test";
        String processWrapper = "/opt/meteor-test/peer-process";
        String dockerExecutable = "docker";
        String runId = System.getenv("METEOR_TEST_RUN_ID");
        List<String> tests = new ArrayList<>();
        Map<String, String> matrix = new LinkedHashMap<>();
    }
}
