package net.hasor.meteor.test.nxn;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hasor.cobble.StringUtils;

/** 只读取能力与运行产物声明，在启动任何对端之前生成标准接口矩阵。 */
public final class NxnMatrix {
    private static final String      STANDARD_CLASS      = "net.hasor.realmeteor.matrix.standard.StandardMatrixTest";
    private static final Set<String> EXCLUDED            = Set.of("build", "generated", "node_modules", ".git", ".gradle");
    private static final Set<String> EXECUTION_VARIABLES = Set.of("settings", "result", "requestFile", "target");
    private static final Pattern     VARIABLE            = Pattern.compile("\\$\\{([^}]+)}");

    private NxnMatrix() {
    }

    /** 返回全部能力、实际可执行的组合和未匹配原因；不加载参数工厂或启动服务。 */
    public static Map<String, Object> collect(Path testDirectory, Map<String, Object> runtime) throws IOException {
        Path root = testDirectory.toAbsolutePath().normalize();
        List<Path> manifests = manifests(root);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 1);
        List<Map<String, Object>> endpoints = new ArrayList<>();
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> rejections = new ArrayList<>();
        result.put("endpoints", endpoints);
        result.put("cases", cases);
        result.put("rejections", rejections);
        if (manifests.isEmpty()) {
            return result;
        }

        Map<String, Map<String, Object>> apis = apis(root.resolve("resource/matrix/apis.json"));
        List<Map<String, Object>> executors = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> api : apis.entrySet()) {
            for (Map<String, Object> scenario : objects(api.getValue().get("scenarios"), "API scenarios")) {
                Map<String, Object> executor = new LinkedHashMap<>();
                executor.put("class", scenario.getOrDefault("class", STANDARD_CLASS));
                executor.put("method", scenario.get("method"));
                executor.put("api", api.getKey());
                executor.put("scenario", scenario.get("id"));
                executors.add(executor);
            }
        }
        executors.sort(Comparator.comparing(executor -> text(executor.get("api")) + "\u0000" + text(executor.get("scenario"))));
        result.put("executors", executors);
        Map<String, Object> properties = object(runtime.getOrDefault("systemProperties", Map.of()), "systemProperties");
        Map<String, Object> artifacts = object(runtime.getOrDefault("artifacts", Map.of()), "artifacts");
        Set<String> declaredIds = new LinkedHashSet<>();
        Set<String> expandedIds = new LinkedHashSet<>();
        Map<Path, Map<String, Object>> runtimeCatalogs = new LinkedHashMap<>();
        for (Path manifest : manifests) {
            Map<String, Object> declaration = read(manifest);
            schema(declaration, manifest);
            for (Map<String, Object> endpoint : objects(declaration.get("endpoints"), manifest + " endpoints")) {
                String id = required(endpoint, "id", manifest.toString());
                if (!declaredIds.add(id)) {
                    throw invalid(manifest, "Duplicate endpoint id: " + id);
                }
                expand(root, manifest, endpoint, properties, artifacts, runtimeCatalogs, apis, expandedIds, endpoints);
            }
        }
        endpoints.sort(Comparator.comparing(endpoint -> text(endpoint.get("id"))));

        // 同一 wire 标识就是明确的线上兼容约定，SDK 或实现版本不同不阻止交叉验证。
        Set<String> caseIds = new LinkedHashSet<>();
        for (Map<String, Object> provider : endpoints) {
            if (!strings(provider.get("roles"), "roles").contains("provider")) {
                continue;
            }
            for (Map<String, Object> consumer : endpoints) {
                if (!strings(consumer.get("roles"), "roles").contains("consumer")) {
                    continue;
                }
                String reason = mismatch(provider, consumer);
                Set<String> discoveries = new TreeSet<>(strings(provider.get("discoveries"), "discoveries"));
                discoveries.retainAll(strings(consumer.get("discoveries"), "discoveries"));
                if (reason.isEmpty() && discoveries.isEmpty()) {
                    reason = "discovery";
                }
                if (!reason.isEmpty()) {
                    Map<String, Object> rejected = new LinkedHashMap<>();
                    rejected.put("provider", provider.get("id"));
                    rejected.put("consumer", consumer.get("id"));
                    rejected.put("reason", reason);
                    rejections.add(rejected);
                    continue;
                }
                Map<String, Object> api = apis.get(text(provider.get("api")));
                for (Map<String, Object> scenario : objects(api.get("scenarios"), "API scenarios")) {
                    for (String discovery : discoveries) {
                        Map<String, Object> testCase = testCase(provider, consumer, scenario, discovery);
                        String caseId = text(testCase.get("id"));
                        if (!caseIds.add(caseId)) {
                            throw new IllegalArgumentException("Duplicate generated matrix case: " + caseId);
                        }
                        cases.add(testCase);
                    }
                }
            }
        }
        cases.sort(Comparator.comparing(testCase -> text(testCase.get("id"))));
        return result;
    }

    /** JUnit 仅消费已生成的计划，不能再次扫描或组合能力。 */
    public static List<Map<String, Object>> cases() {
        String location = System.getProperty("meteor.test.matrix.plan");
        if (location == null || location.isBlank()) {
            throw new IllegalStateException("Missing meteor.test.matrix.plan; export the NXN matrix before execution");
        }
        Path path = Path.of(location);
        try {
            Map<String, Object> plan = read(path);
            return List.copyOf(objects(plan.get("cases"), path + " cases"));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read NXN matrix plan: " + path, exception);
        }
    }

    public static List<Object[]> arguments() {
        return arguments(cases());
    }

    /** 每个标准 API 的执行类只取得自己的参数，避免参数化类执行其它契约。 */
    public static List<Object[]> arguments(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Matrix execution class must not be blank");
        }
        String prefix = className + "#";
        List<Map<String, Object>> selected = cases().stream().filter(testCase -> {
            return required(testCase, "caseId", "matrix case").startsWith(prefix);
        }).toList();
        return arguments(selected);
    }

    private static List<Object[]> arguments(List<Map<String, Object>> cases) {
        List<Object[]> arguments = new ArrayList<>();
        for (Map<String, Object> testCase : cases) {
            // 参数次序对应 StandardMatrixTest(String id, Map testCase) 的构造函数。
            arguments.add(new Object[] { required(testCase, "id", "matrix case"), testCase });
        }
        return arguments;
    }

    private static List<Path> manifests(Path root) throws IOException {
        List<Path> manifests = new ArrayList<>();
        for (String category : List.of("peer", "integration")) {
            Path directory = root.resolve(category);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) {
                    if (EXCLUDED.contains(path.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && StringUtils.equals("nxn.json", path.getFileName().toString())) {
                        manifests.add(path.toAbsolutePath().normalize());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        manifests.sort(Comparator.comparing(Path::toString));
        return manifests;
    }

    private static Map<String, Map<String, Object>> apis(Path path) throws IOException {
        Map<String, Object> catalog = read(path);
        schema(catalog, path);
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> api : objects(catalog.get("apis"), path + " apis")) {
            String id = required(api, "id", path.toString());
            if (result.putIfAbsent(id, api) != null) {
                throw invalid(path, "Duplicate API id: " + id);
            }
            Set<String> scenarios = new LinkedHashSet<>();
            for (Map<String, Object> scenario : objects(api.get("scenarios"), "API " + id + " scenarios")) {
                String scenarioId = required(scenario, "id", "API " + id);
                required(scenario, "method", "API " + id);
                if (scenario.containsKey("class")) {
                    required(scenario, "class", "API " + id);
                }
                object(scenario.get("request"), "API " + id + " request");
                if (!scenarios.add(scenarioId)) {
                    throw invalid(path, "Duplicate scenario: " + id + "/" + scenarioId);
                }
            }
            if (scenarios.isEmpty()) {
                throw invalid(path, "API must declare at least one scenario: " + id);
            }
        }
        return result;
    }

    private static void expand(Path root, Path manifest, Map<String, Object> endpoint, Map<String, Object> properties, Map<String, Object> artifacts, Map<Path, Map<String, Object>> runtimeCatalogs, Map<String, Map<String, Object>> apis, Set<String> expandedIds, List<Map<String, Object>> endpoints) throws IOException {
        String endpointId = required(endpoint, "id", manifest.toString());
        String language = required(endpoint, "language", endpointId);
        String implementation = required(endpoint, "implementation", endpointId);
        Map<String, Object> launch = object(endpoint.get("launch"), endpointId + " launch");
        int runtimeSources = 0;
        for (String source : List.of("runtimeManifestProperty", "runtimeManifest", "classpathProperty")) {
            if (endpoint.containsKey(source)) {
                runtimeSources++;
            }
        }
        if (runtimeSources > 1) {
            throw invalid(manifest, "Endpoint must use one runtime source: " + endpointId);
        }
        List<Map<String, Object>> capabilities = objects(endpoint.get("capabilities"), endpointId + " capabilities");
        if (capabilities.isEmpty()) {
            throw invalid(manifest, "Endpoint must declare at least one capability: " + endpointId);
        }
        for (Map<String, Object> capability : capabilities) {
            String capabilityId = required(capability, "id", endpointId);
            String id = endpointId + "-" + capabilityId;
            if (!expandedIds.add(id)) {
                throw invalid(manifest, "Duplicate expanded endpoint id: " + id);
            }
            String api = required(capability, "api", id);
            if (!apis.containsKey(api)) {
                throw invalid(manifest, "Unknown API contract: " + api + " in " + id);
            }
            String protocol = required(capability, "protocol", id);
            String wire = required(capability, "wire", id);
            List<String> roles = nonEmptyStrings(capability.get("roles"), id + " roles");
            List<String> discoveries = nonEmptyStrings(capability.get("discoveries"), id + " discoveries");
            Map<String, Object> profile = profile(manifest, endpoint, capability, properties, runtimeCatalogs);
            Map<String, String> substitutions = new LinkedHashMap<>();
            substitutions.put("project", manifest.getParent().toString());
            substitutions.put("testDirectory", root.toString());
            substitutions.put("entry", text(capability.getOrDefault("entry", protocol)));
            substitutions.put("api", api);
            substitutions.put("wire", wire);
            substitutions.put("protocol", protocol);
            substitutions.put("runtimeProfile", text(capability.getOrDefault("runtimeProfile", "")));
            if (profile.containsKey("classpath")) {
                String classpath = classpath(profile.get("classpath"), manifest.getParent(), artifacts, id);
                substitutions.put("classpath", classpath);
            }
            if (profile.containsKey("executable")) {
                String executable = required(profile, "executable", id + " runtime profile");
                if (executable.contains("/") || executable.contains("\\")) {
                    Path path = manifest.getParent().resolve(executable).toAbsolutePath().normalize();
                    if (!availableArtifact(path, artifacts)) {
                        throw invalid(manifest, "Missing runtime executable for " + id + ": " + path);
                    }
                    executable = path.toString();
                }
                substitutions.put("executable", executable);
            }
            if (endpoint.containsKey("classpathProperty")) {
                String property = required(endpoint, "classpathProperty", id);
                if (!properties.containsKey(property)) {
                    throw invalid(manifest, "Missing runtime property: " + property + " for " + id);
                }
                substitutions.put("classpath", classpath(properties.get(property), root, artifacts, id));
            }

            Map<String, Object> expanded = new LinkedHashMap<>();
            expanded.put("id", id);
            expanded.put("endpoint", endpointId);
            expanded.put("capability", capabilityId);
            expanded.put("language", language);
            expanded.put("implementation", implementation);
            expanded.put("version", text(profile.getOrDefault("version", endpoint.getOrDefault("version", ""))));
            expanded.put("framework", text(endpoint.getOrDefault("framework", "")));
            expanded.put("api", api);
            expanded.put("protocol", protocol);
            expanded.put("protocolVersion", text(capability.getOrDefault("protocolVersion", "")));
            expanded.put("wire", wire);
            Object serialGroup = capability.getOrDefault("serialGroup", "");
            if (!(serialGroup instanceof String)) {
                throw invalid(manifest, "serialGroup must be a string in " + id);
            }
            expanded.put("serialGroup", serialGroup);
            expanded.put("roles", roles);
            expanded.put("discoveries", discoveries);
            expanded.put("source", manifest.toString());
            expanded.put("runtimeProfile", substitutions.get("runtimeProfile"));
            Map<String, Object> commands = new LinkedHashMap<>();
            for (String role : roles) {
                if (!StringUtils.equals(role, "provider") && !StringUtils.equals(role, "consumer")) {
                    throw invalid(manifest, "Unknown endpoint role: " + role);
                }
                Map<String, Object> command = object(launch.get(role), id + " launch." + role);
                Map<String, Object> effective = new LinkedHashMap<>(command);
                if (launch.containsKey("workingDirectory")) {
                    effective.putIfAbsent("workingDirectory", launch.get("workingDirectory"));
                }
                Object commonEnvironment = launch.getOrDefault("environment", Map.of());
                Map<String, Object> environment = object(commonEnvironment, id + " environment");
                Object roleEnvironment = command.getOrDefault("environment", Map.of());
                environment.putAll(object(roleEnvironment, id + " " + role + " environment"));
                effective.put("environment", environment);
                commands.put(role, launch(manifest, effective, substitutions, id + "/" + role));
            }
            expanded.put("launch", commands);
            endpoints.add(expanded);
        }
    }

    private static Map<String, Object> profile(Path manifest, Map<String, Object> endpoint, Map<String, Object> capability, Map<String, Object> properties, Map<Path, Map<String, Object>> catalogs) throws IOException {
        String location = "";
        if (endpoint.containsKey("runtimeManifestProperty")) {
            String property = required(endpoint, "runtimeManifestProperty", manifest.toString());
            if (!properties.containsKey(property)) {
                throw invalid(manifest, "Missing runtime property: " + property);
            }
            location = text(properties.get(property));
        } else if (endpoint.containsKey("runtimeManifest")) {
            location = required(endpoint, "runtimeManifest", manifest.toString());
        }
        if (location.isBlank()) {
            if (capability.containsKey("runtimeProfile")) {
                throw invalid(manifest, "runtimeProfile requires a runtime manifest");
            }
            return Map.of();
        }
        Path path = manifest.getParent().resolve(location).toAbsolutePath().normalize();
        Map<String, Object> catalog = catalogs.get(path);
        if (catalog == null) {
            catalog = read(path);
            catalogs.put(path, catalog);
        }
        String profile = required(capability, "runtimeProfile", manifest.toString());
        Map<String, Object> profiles = object(catalog.get("profiles"), path + " profiles");
        if (!profiles.containsKey(profile)) {
            throw invalid(manifest, "Missing runtime profile: " + profile + " in " + path);
        }
        return object(profiles.get(profile), path + " profile " + profile);
    }

    private static Map<String, Object> launch(Path manifest, Map<String, Object> launch, Map<String, String> substitutions, String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("container", required(launch, "container", description));
        List<String> command = new ArrayList<>();
        List<String> arguments = strings(launch.get("command"), description + " command");
        if (arguments.isEmpty() || arguments.get(0).isBlank()) {
            throw new IllegalArgumentException(description + " command requires an executable");
        }
        for (String argument : arguments) {
            command.add(substitute(argument, substitutions, description));
        }
        result.put("command", command);
        String workingDirectory = text(launch.getOrDefault("workingDirectory", manifest.getParent().toString()));
        workingDirectory = substitute(workingDirectory, substitutions, description);
        result.put("workingDirectory", manifest.getParent().resolve(workingDirectory).normalize().toString());
        Map<String, Object> environment = new LinkedHashMap<>();
        Object environmentConfig = launch.getOrDefault("environment", Map.of());
        Map<String, Object> declared = object(environmentConfig, description + " environment");
        for (Map.Entry<String, Object> entry : declared.entrySet()) {
            if (!(entry.getValue() instanceof String value)) {
                throw new IllegalArgumentException(description + " environment must contain string values");
            }
            environment.put(entry.getKey(), substitute(value, substitutions, description));
        }
        result.put("environment", environment);
        return result;
    }

    private static String classpath(Object value, Path directory, Map<String, Object> artifacts, String description) {
        List<String> entries;
        if (value instanceof String paths) {
            entries = List.of(paths.split(Pattern.quote(File.pathSeparator), -1));
        } else {
            entries = strings(value, description + " classpath");
        }
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Empty runtime classpath for " + description);
        }
        List<String> resolved = new ArrayList<>();
        for (String entry : entries) {
            Path path = directory.resolve(entry).toAbsolutePath().normalize();
            if (entry.isBlank() || !availableArtifact(path, artifacts)) {
                throw new IllegalArgumentException("Missing runtime artifact for " + description + ": " + path);
            }
            resolved.add(path.toString());
        }
        return String.join(File.pathSeparator, resolved);
    }

    private static boolean availableArtifact(Path path, Map<String, Object> artifacts) {
        if (Files.exists(path)) {
            return true;
        }
        // 运行产物的所有者已经审计独立容器中的文件，runner 不要求挂载对端的依赖缓存。
        Object fingerprint = artifacts.get(path.toString());
        return fingerprint instanceof String sha256 && sha256.matches("[a-fA-F0-9]{64}");
    }

    private static String substitute(String value, Map<String, String> substitutions, String description) {
        Matcher matcher = VARIABLE.matcher(value);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = substitutions.get(key);
            if (replacement == null) {
                if (!EXECUTION_VARIABLES.contains(key)) {
                    String message = "Unknown or unresolved placeholder ${" + key + "} in " + description;
                    throw new IllegalArgumentException(message);
                }
                replacement = matcher.group();
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String mismatch(Map<String, Object> provider, Map<String, Object> consumer) {
        for (String dimension : List.of("api", "protocol", "wire")) {
            if (!StringUtils.equals(text(provider.get(dimension)), text(consumer.get(dimension)))) {
                return dimension;
            }
        }
        return "";
    }

    private static Map<String, Object> testCase(Map<String, Object> provider, Map<String, Object> consumer, Map<String, Object> scenario, String discovery) {
        String api = text(provider.get("api"));
        String protocol = text(provider.get("protocol"));
        String wire = text(provider.get("wire"));
        String scenarioId = text(scenario.get("id"));
        String className = text(scenario.getOrDefault("class", STANDARD_CLASS));
        String method = text(scenario.get("method"));
        String id = segment(api) + "/" + segment(scenarioId) + "/consumer=" + segment(consumer.get("id")) + "/provider=" + segment(provider.get("id")) + "/" + segment(wire) + "/" + segment(discovery);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("caseId", className + "#" + method + "[" + id + "]");
        result.put("class", className);
        result.put("method", method);
        result.put("api", api);
        result.put("scenario", scenarioId);
        result.put("protocol", protocol);
        Set<String> versions = new TreeSet<>();
        for (Map<String, Object> endpoint : List.of(provider, consumer)) {
            String version = text(endpoint.get("protocolVersion"));
            if (!version.isBlank()) {
                versions.add(version);
            }
        }
        result.put("protocolVersions", new ArrayList<>(versions));
        result.put("protocolVersion", String.join("/", versions));
        result.put("wire", wire);
        result.put("discovery", discovery);
        String providerGroup = text(provider.get("serialGroup"));
        String consumerGroup = text(consumer.get("serialGroup"));
        if (!providerGroup.isEmpty() && !consumerGroup.isEmpty() && !StringUtils.equals(providerGroup, consumerGroup)) {
            throw new IllegalArgumentException("Incompatible serialGroup for " + id + ": " + providerGroup + " / " + consumerGroup);
        }
        String serialGroup = providerGroup;
        if (serialGroup.isEmpty()) {
            serialGroup = consumerGroup;
        }
        result.put("serialGroup", serialGroup);
        result.put("provider", role(provider, "provider"));
        result.put("consumer", role(consumer, "consumer"));
        result.put("request", new LinkedHashMap<>(object(scenario.get("request"), "scenario request")));
        return result;
    }

    private static Map<String, Object> role(Map<String, Object> endpoint, String role) {
        Map<String, Object> result = new LinkedHashMap<>(endpoint);
        result.remove("launch");
        result.remove("roles");
        result.remove("discoveries");
        result.put("role", role);
        Map<String, Object> launch = object(endpoint.get("launch"), "endpoint launch");
        result.putAll(object(launch.get(role), "role launch"));
        return result;
    }

    private static String segment(Object value) {
        String raw = text(value);
        StringBuilder result = new StringBuilder();
        // 保留可读身份，转义分隔符，防止不同声明生成同一个 caseId。
        for (byte encoded : raw.getBytes(StandardCharsets.UTF_8)) {
            int character = Byte.toUnsignedInt(encoded);
            boolean letter = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
            boolean digit = character >= '0' && character <= '9';
            if (letter || digit || character == '-' || character == '_' || character == '.') {
                result.append((char) character);
            } else {
                result.append('%');
                result.append(Character.forDigit(character >>> 4, 16));
                result.append(Character.forDigit(character & 15, 16));
            }
        }
        return result.toString();
    }

    private static Map<String, Object> read(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("NXN declaration does not exist: " + path);
        }
        return NxnPlan.readObject(path);
    }

    private static void schema(Map<String, Object> declaration, Path source) {
        if (!(declaration.get("schemaVersion") instanceof Number version) || version.intValue() != 1) {
            throw invalid(source, "Expected schemaVersion 1");
        }
    }

    private static String required(Map<String, Object> object, String key, String source) {
        Object value = object.get(key);
        if (!(value instanceof String string) || string.isBlank()) {
            throw new IllegalArgumentException(source + " requires nonempty string " + key);
        }
        return string;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<String> nonEmptyStrings(Object value, String description) {
        List<String> result = strings(value, description);
        if (result.isEmpty() || result.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException(description + " must be a nonempty string array");
        }
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw new IllegalArgumentException(description + " contains duplicate values");
        }
        return result;
    }

    private static List<String> strings(Object value, String description) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(description + " must be an array");
        }
        List<String> result = new ArrayList<>();
        for (Object element : values) {
            if (!(element instanceof String string)) {
                throw new IllegalArgumentException(description + " must contain strings");
            }
            result.add(string);
        }
        return result;
    }

    private static List<Map<String, Object>> objects(Object value, String description) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(description + " must be an array");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : values) {
            result.add(object(element, description));
        }
        return result;
    }

    private static Map<String, Object> object(Object value, String description) {
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalArgumentException(description + " must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(description + " must contain string keys");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static IllegalArgumentException invalid(Path source, String message) {
        return new IllegalArgumentException(source + ": " + message);
    }
}
