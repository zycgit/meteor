package net.hasor.meteor.test.nxn;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;

/** Compile the selected test work into independent, isolated JUnit JVM groups. */
final class NxnPlan {
    static final         ObjectMapper              JSON                    = new ObjectMapper();
    static final         String                    LAUNCHER                = "net.hasor.realmeteor.environment.execution.JUnitJobMain";
    static final         String                    NATIVE_REGISTRY_PACKAGE = "net.hasor.realmeteor.registry.dubbo.";
    static final         String                    NATIVE_REGISTRY_EXAMPLE = "net.hasor.realmeteor.example.sdk.NativeRegistryScenarioTest";
    static final         String                    NATIVE_REGISTRY_FIXTURE = "net.hasor.realmeteor.environment.storage.RegistryServiceReuseTest";
    static final         Map<String, List<String>> METHOD_PARTITIONS       = new LinkedHashMap<>();
    private static final Map<String, String>       MUTABLE_PROPERTIES      = new LinkedHashMap<>();

    static {
        // The existing direction methods are the only permitted method partition boundaries.
        METHOD_PARTITIONS.put("net.hasor.realmeteor.protocol.thrift.python.PythonThriftTypeInteropTest", Arrays.asList("meteorProviderPreservesPythonNativeType", "meteorConsumerPreservesPythonNativeType"));
        METHOD_PARTITIONS.put("net.hasor.realmeteor.matrix.types.ThriftGoTypeMatrixTest", Arrays.asList("goConsumerPreservesSelectedTypeOnMeteorProvider", "meteorConsumerPreservesSelectedTypeOnGoProvider"));
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
        return NxnRpcSelection.matches(value, pattern);
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
                        int parameter = pattern.indexOf('[');
                        int boundary = parameter < 0 ? pattern.length() : parameter;
                        int separator = pattern.lastIndexOf('.', boundary);
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

    static List<Map<String, Object>> partition(List<String> classes, Map<String, Object> weights, int count, Map<String, Object> methodWeights, Map<String, List<String>> classFilters) {
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
        units.sort(Comparator.<Map<String, Object>>comparingDouble(item -> -number(item.get("seconds"))).thenComparing(item -> String.join("\u0000", strings(item.get("filters")))));
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
            Map<String, Object> target = candidates.stream().min(Comparator.comparingDouble(item -> number(item.get("expectedSeconds")))).orElseThrow();
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

    private static Map<String, Object> unit(Collection<String> classes, Collection<String> filters, Collection<String> includes, double seconds) {
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
        return name.startsWith(NATIVE_REGISTRY_PACKAGE) || StringUtils.equals(name, NATIVE_REGISTRY_EXAMPLE) || StringUtils.equals(name, NATIVE_REGISTRY_FIXTURE);
    }

    static Map<String, String> systemProperties(Map<String, Object> runtime, Map<String, Object> group, String runId, Map<String, String> matrix) {
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
        if (group.containsKey("matrixFile")) {
            properties.put("meteor.test.matrix.plan", String.valueOf(group.get("matrixFile")));
        }
        return properties;
    }

    static List<String> javaCommand(Map<String, Object> runtime, Map<String, Object> group, String runId, Map<String, String> matrix, String workspace) {
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
        if (group.containsKey("rpc")) {
            command.add("--rpc");
            command.add((String) group.get("rpc"));
            command.add("--rpc-version");
            command.add((String) group.get("rpcVersion"));
            command.add("--rpc-cases-file");
            command.add((String) group.get("rpcCasesFile"));
        }
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
            if (Arrays.asList(":meteor-test:test", "--no-daemon", "--console=plain", "--stacktrace", "--rerun-tasks").contains(argument)) {
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
                case "--runtime":
                    options.runtime = Path.of(value);
                    break;
                case "--output":
                    options.output = Path.of(value);
                    break;
                case "--build-directory":
                    options.buildDirectory = Path.of(value);
                    break;
                case "--weights":
                    options.weights = Path.of(value);
                    break;
                case "--baseline":
                    options.baseline = Path.of(value);
                    break;
                case "--groups":
                    requestedGroups = value;
                    break;
                case "--container":
                    options.container = value;
                    break;
                case "--workspace":
                    options.workspace = value;
                    break;
                case "--process-wrapper":
                    options.processWrapper = value;
                    break;
                case "--run-id":
                    options.runId = value;
                    break;
                case "--tests":
                    options.tests.add(value);
                    break;
                case "--case":
                    options.cases.add(value);
                    break;
                case "--rpc":
                    options.rpc = NxnRpcSelection.protocolName(value);
                    break;
                case "--rpc-version":
                    options.rpcVersion = value.trim();
                    break;
                case "--skipError":
                    boolean skip = StringUtils.equals("true", value);
                    if (!skip && !StringUtils.equals("false", value)) {
                        throw new IllegalArgumentException("--skipError must be true or false");
                    }
                    options.skipError = skip;
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported test argument: " + argument);
            }
        }
        options.groups = parseGroupRequest(requestedGroups);
        if (options.rpc.isEmpty() && !options.rpcVersion.isEmpty()) {
            throw new IllegalArgumentException("--rpc-version requires --rpc");
        }
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
        if (runtime.containsKey("matrixDirectory")) {
            Path matrixDirectory = Path.of(String.valueOf(runtime.get("matrixDirectory")));
            Map<String, Object> matrix = NxnMatrix.collect(matrixDirectory, runtime);
            runtime = new LinkedHashMap<>(runtime);
            runtime.put("matrix", matrix);
            Map<String, Object> declarations = new TreeMap<>(object(runtime.get("rpcCases")));
            NxnRuntime.declareMatrixProtocols(declarations, objects(matrix.get("cases")));
            runtime.put("rpcCases", declarations);
        }
        List<String> classes = new ArrayList<>(strings(runtime.get("classNames")));
        classes.sort(String::compareTo);
        Map<String, Object> matrix = object(runtime.get("matrix"));
        List<Map<String, Object>> discoveredMatrix = objects(matrix.get("cases"));
        Set<String> matrixClasses = new LinkedHashSet<>();
        for (Map<String, Object> executor : objects(matrix.get("executors"))) {
            String className = String.valueOf(executor.get("class"));
            matrixClasses.add(className);
            validateMatrixEntry(runtime, classes, className, String.valueOf(executor.get("method")));
        }
        for (Map<String, Object> testCase : discoveredMatrix) {
            String className = caseClass(testCase);
            matrixClasses.add(className);
            String identity = String.valueOf(testCase.get("caseId"));
            String method = identity.substring(identity.indexOf('#') + 1, identity.indexOf('['));
            validateMatrixEntry(runtime, classes, className, method);
        }
        Map<String, List<String>> requested = selectClasses(options.tests, classes);
        Map<String, String> matrixSelection = new LinkedHashMap<>();
        object(runtime.get("systemProperties")).forEach((name, value) -> {
            if (name.startsWith("meteor.matrix.")) {
                matrixSelection.put(name, String.valueOf(value));
            }
        });
        matrixSelection.putAll(options.matrix);
        validateMatrixSelection(matrixSelection, matrix);
        Map<String, Object> rpcCases = object(runtime.get("rpcCases"));
        Map<String, List<String>> selected;
        if (!options.rpc.isEmpty()) {
            selected = NxnRpcSelection.select(requested, rpcCases, options.rpc, options.rpcVersion);
        } else {
            selected = requested;
        }
        List<Map<String, Object>> matrixCases = selectMatrixCases(discoveredMatrix, selected, options, matrixSelection);
        Map<String, List<String>> legacy = new TreeMap<>(selected);
        matrixClasses.forEach(legacy::remove);
        if (!options.cases.isEmpty()) {
            legacy.clear();
        }
        selected = new TreeMap<>(legacy);
        for (Map<String, Object> testCase : matrixCases) {
            selected.putIfAbsent(caseClass(testCase), List.of());
        }
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No testcase matches the requested matrix and JUnit selection");
        }
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
        List<Map<String, Object>> groups = partition(new ArrayList<>(legacy.keySet()), classWeights, options.groups, object(durations.get("methods")), legacy);
        assignMatrixCases(groups, matrixCases, options.groups, object(durations.get("cases")));
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
            group.putIfAbsent("matrixCases", new ArrayList<>());
            Path matrixFile = directory.resolve("matrix.json");
            group.put("matrixFile", matrixFile.toString());
            writeJson(matrixFile, Map.of("schemaVersion", 1, "cases", group.get("matrixCases")));
            if (!options.rpc.isEmpty()) {
                // 分组持有自己的声明副本；运行时不扫描其它组或读取历史结果。
                Map<String, Object> groupRpcCases = new LinkedHashMap<>();
                for (String className : strings(group.get("classes"))) {
                    groupRpcCases.put(className, rpcCases.get(className));
                }
                group.put("rpc", options.rpc);
                group.put("rpcVersion", options.rpcVersion);
                group.put("rpcCases", groupRpcCases);
                Path rpcCasesFile = directory.resolve("rpc-cases.json");
                group.put("rpcCasesFile", rpcCasesFile.toString());
                writeJson(rpcCasesFile, groupRpcCases);
            }
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
            NxnProcess.prepareLaunch(launchFile, launch);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 3);
        manifest.put("runId", options.runId);
        manifest.put("skipError", options.skipError);
        manifest.put("runtimeFile", options.runtime.toAbsolutePath().normalize().toString());
        manifest.put("userIncludes", options.tests);
        manifest.put("matrixSelection", options.matrix);
        manifest.put("rpc", options.rpc);
        manifest.put("rpcVersion", options.rpcVersion);
        manifest.put("rpcCases", rpcCases);
        manifest.put("rpcCatalog", NxnRpcSelection.catalog(rpcCases));
        manifest.put("matrixCases", matrixCases);
        manifest.put("matrixDiscovered", discoveredMatrix.size());
        manifest.put("matrixExecutors", matrix.getOrDefault("executors", List.of()));
        manifest.put("matrixEndpoints", object(runtime.get("matrix")).getOrDefault("endpoints", List.of()));
        manifest.put("matrixRejections", object(runtime.get("matrix")).getOrDefault("rejections", List.of()));
        manifest.put("caseIncludes", options.cases);
        manifest.put("legacyClasses", new ArrayList<>(legacy.keySet()));
        Map<String, List<String>> methodPartitions = new LinkedHashMap<>();
        Map<String, List<String>> finalSelected = selected;
        METHOD_PARTITIONS.forEach((name, methods) -> {
            if (finalSelected.containsKey(name) && finalSelected.get(name).isEmpty()) {
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

    private static void validateMatrixEntry(Map<String, Object> runtime, List<String> classes, String className, String method) {
        if (!classes.contains(className)) {
            throw new IllegalArgumentException("Matrix refers to a test class that was not compiled: " + className);
        }
        Map<String, Object> entries = object(runtime.get("testEntries"));
        if (!entries.isEmpty()) {
            List<String> methods = strings(entries.getOrDefault(className, List.of()));
            if (!methods.contains(method)) {
                String message = "Matrix refers to an unknown test method: " + className + "#" + method;
                throw new IllegalArgumentException(message);
            }
        }
    }

    private static List<Map<String, Object>> selectMatrixCases(List<Map<String, Object>> candidates, Map<String, List<String>> selected, Options options, Map<String, String> matrixSelection) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> testCase : candidates) {
            String className = caseClass(testCase);
            if (!selected.containsKey(className)) {
                continue;
            }
            String identity = String.valueOf(testCase.get("caseId"));
            String selection = identity.replace('#', '.');
            String method = selection.substring(0, selection.indexOf('['));
            List<String> filters = selected.get(className);
            boolean included = filters.isEmpty() || filters.stream().anyMatch(filter -> matches(selection, filter) || matches(method, filter));
            if (!included || !options.cases.isEmpty() && options.cases.stream().noneMatch(pattern -> matches(String.valueOf(testCase.get("id")), pattern) || matches(identity, pattern))) {
                continue;
            }
            if (!options.rpc.isEmpty() && !StringUtils.equals(options.rpc, String.valueOf(testCase.get("protocol")))) {
                continue;
            }
            if (!options.rpcVersion.isEmpty()) {
                List<String> versions = strings(testCase.getOrDefault("protocolVersions", List.of()));
                if (!versions.contains(options.rpcVersion) && !StringUtils.equals(options.rpcVersion, String.valueOf(testCase.get("protocolVersion")))) {
                    continue;
                }
            }
            if (!selectedDimension(String.valueOf(testCase.get("protocol")), matrixSelection.getOrDefault("meteor.matrix.protocols", "*"), true) || !selectedDimension(String.valueOf(testCase.get("discovery")), matrixSelection.getOrDefault("meteor.matrix.discoveries", "*"), false)) {
                continue;
            }
            result.add(testCase);
        }
        if (!options.cases.isEmpty() && result.isEmpty()) {
            throw new IllegalArgumentException("No standard matrix case matches --case " + options.cases);
        }
        return result;
    }

    private static boolean selectedDimension(String value, String selection, boolean protocol) {
        for (String token : selection.split(",", -1)) {
            String name = token.trim().toLowerCase(Locale.ROOT);
            if (StringUtils.equals(name, "*") || StringUtils.equals(name, "all")) {
                return true;
            }
            if (protocol) {
                name = NxnRpcSelection.protocolName(name);
            }
            if (StringUtils.equals(value, name)) {
                return true;
            }
        }
        return false;
    }

    private static void validateMatrixSelection(Map<String, String> selection, Map<String, Object> matrix) {
        Set<String> protocols = new LinkedHashSet<>();
        Set<String> discoveries = new LinkedHashSet<>();
        for (Map<String, Object> endpoint : objects(matrix.get("endpoints"))) {
            protocols.add(String.valueOf(endpoint.get("protocol")));
            discoveries.addAll(strings(endpoint.getOrDefault("discoveries", List.of())));
        }
        for (Map<String, Object> testCase : objects(matrix.get("cases"))) {
            protocols.add(String.valueOf(testCase.get("protocol")));
            discoveries.add(String.valueOf(testCase.get("discovery")));
        }
        Map<String, Set<String>> dimensions = new LinkedHashMap<>();
        dimensions.put("meteor.matrix.protocols", protocols);
        dimensions.put("meteor.matrix.discoveries", discoveries);
        dimensions.forEach((key, available) -> {
            if (available.isEmpty() || !selection.containsKey(key)) {
                return;
            }
            for (String token : selection.get(key).split(",", -1)) {
                String name = token.trim().toLowerCase(Locale.ROOT);
                if (StringUtils.equals(name, "*") || StringUtils.equals(name, "all")) {
                    continue;
                }
                if (key.endsWith("protocols")) {
                    name = NxnRpcSelection.protocolName(name);
                }
                if (!available.contains(name)) {
                    throw new IllegalArgumentException("Unknown selection in " + key + ": " + token);
                }
            }
        });
    }

    private static String caseClass(Map<String, Object> testCase) {
        String identity = String.valueOf(testCase.get("caseId"));
        return identity.substring(0, identity.indexOf('#'));
    }

    private static void assignMatrixCases(List<Map<String, Object>> groups, List<Map<String, Object>> cases, int count, Map<String, Object> weights) {
        int desired = Math.min(count, groups.size() + cases.size());
        while (groups.size() < desired) {
            Map<String, Object> group = unit(List.of(), List.of(), List.of(), 0);
            group.remove("seconds");
            group.put("expectedSeconds", 0.0);
            groups.add(group);
        }
        List<Map<String, Object>> ordered = new ArrayList<>(cases);
        ordered.sort(Comparator.<Map<String, Object>>comparingDouble(testCase -> -duration(weights.getOrDefault(testCase.get("caseId"), 1), String.valueOf(testCase.get("caseId")))).thenComparing(testCase -> String.valueOf(testCase.get("id"))));
        Map<String, Map<String, Object>> serialGroups = new LinkedHashMap<>();
        for (Map<String, Object> group : groups) {
            if (strings(group.get("classes")).stream().anyMatch(NxnPlan::nativeRegistryClass)) {
                serialGroups.put("native-registry", group);
            }
        }
        for (Map<String, Object> testCase : ordered) {
            String serial = String.valueOf(testCase.getOrDefault("serialGroup", ""));
            Map<String, Object> group = serialGroups.get(serial);
            if (group == null) {
                group = groups.stream().min(Comparator.comparingDouble(item -> number(item.get("expectedSeconds")))).orElseThrow();
                if (!serial.isBlank()) {
                    serialGroups.put(serial, group);
                }
            }
            String className = caseClass(testCase);
            if (!strings(group.get("classes")).contains(className)) {
                strings(group.get("classes")).add(className);
            }
            String selection = String.valueOf(testCase.get("caseId")).replace('#', '.');
            strings(group.get("filters")).add(selection);
            strings(group.get("includes")).add(selection);
            objects(group.computeIfAbsent("matrixCases", ignored -> new ArrayList<>())).add(testCase);
            double seconds = duration(weights.getOrDefault(testCase.get("caseId"), 1), selection);
            group.put("expectedSeconds", number(group.get("expectedSeconds")) + seconds);
        }
        for (Map<String, Object> group : groups) {
            strings(group.get("classes")).sort(String::compareTo);
            strings(group.get("filters")).sort(String::compareTo);
            strings(group.get("includes")).sort(String::compareTo);
        }
    }

    static void printSummary(Map<String, Object> manifest, PrintStream output) {
        List<Map<String, Object>> cases = objects(manifest.get("matrixCases"));
        String totals = "[discovery] standard cases %s, selected %d; legacy classes %d (parameters expand at execution)%n";
        output.printf(totals, manifest.getOrDefault("matrixDiscovered", 0), cases.size(), strings(manifest.getOrDefault("legacyClasses", List.of())).size());
        Map<String, Integer> protocols = new TreeMap<>();
        Map<String, Integer> languages = new TreeMap<>();
        Map<String, Integer> apis = new TreeMap<>();
        for (Map<String, Object> testCase : cases) {
            String protocol = String.valueOf(testCase.get("protocol"));
            String version = String.valueOf(testCase.getOrDefault("protocolVersion", ""));
            if (!version.isEmpty()) {
                protocol += "/" + version;
            }
            protocols.merge(protocol, 1, Integer::sum);
            String consumer = String.valueOf(object(testCase.get("consumer")).get("language"));
            String provider = String.valueOf(object(testCase.get("provider")).get("language"));
            languages.merge(consumer + " -> " + provider, 1, Integer::sum);
            apis.merge(String.valueOf(testCase.get("api")), 1, Integer::sum);
        }
        protocols.forEach((name, count) -> output.printf("[discovery][rpc] %s: %d%n", name, count));
        languages.forEach((name, count) -> output.printf("[discovery][language] %s: %d%n", name, count));
        apis.forEach((name, count) -> output.printf("[discovery][api] %s: %d%n", name, count));
        output.printf("[discovery] groups %s; plan contains exact standard case identities before peer startup%n", manifest.get("selectedGroups"));
    }

    static Map<String, Object> readObject(Path path) throws IOException {
        return JSON.readValue(path.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {
        });
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

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objects(Object value) {
        return value == null ? new ArrayList<>() : (List<Map<String, Object>>) value;
    }

    static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    static String now() {
        return Instant.now().toString();
    }

    static final class Options {
        Path                runtime          = Path.of("peer/java/meteor/build/execution/runtime.json");
        Path                output           = Path.of("build/groups");
        Path                buildDirectory   = Path.of("build");
        Path                weights          = Path.of("src/main/resources/matrix/class-durations.json");
        Path                baseline;
        int                 groups           = 16;
        boolean             skipError        = true;
        String              container        = System.getenv().getOrDefault("METEOR_TEST_JAVA_CONTAINER", "java-meteor");
        String              workspace        = "/workspace/meteor/meteor-test";
        String              processWrapper   = "/opt/meteor-test/peer-process";
        String              dockerExecutable = "docker";
        String              rpc              = "";
        String              rpcVersion       = "";
        String              runId            = System.getenv("METEOR_TEST_RUN_ID");
        List<String>        tests            = new ArrayList<>();
        List<String>        cases            = new ArrayList<>();
        Map<String, String> matrix           = new LinkedHashMap<>();
    }
}
