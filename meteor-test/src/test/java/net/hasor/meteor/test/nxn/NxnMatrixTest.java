package net.hasor.meteor.test.nxn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.hasor.cobble.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class NxnMatrixTest {
    @TempDir
    public Path temporary;

    @Test
    public void enumeratesEveryCompatibleDirectionAndScenarioBeforeExecution() throws Exception {
        // 两端支持相同契约和线上协议，各自声明直连及注册发现。
        catalog(List.of(scenario("save-find"), scenario("unicode")));
        Map<String, Object> java = endpoint("java", "java", "2", "orders-wire", List.of("direct", "nacos"));
        Map<String, Object> python = endpoint("python", "python", "3", "orders-wire", List.of("direct"));
        manifest("peer/java/nxn.json", List.of(java));
        manifest("peer/python/nxn.json", List.of(python));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of());
        List<Map<String, Object>> cases = objects(matrix.get("cases"));

        // 共同发现方式按方向展开，版本差异交给 wire 的兼容约定，不能静默丢掉跨版本用例。
        assertEquals(10, cases.size());
        List<Map<String, Object>> crossing = cases.stream().filter(testCase -> {
            Map<String, Object> provider = object(testCase.get("provider"));
            Map<String, Object> consumer = object(testCase.get("consumer"));
            return !StringUtils.equals(provider.get("language").toString(), consumer.get("language").toString());
        }).toList();
        assertEquals(4, crossing.size());
        assertEquals(List.of("2", "3"), crossing.get(0).get("protocolVersions"));
        assertEquals(0, objects(matrix.get("rejections")).size());
        assertTrue(cases.stream().allMatch(testCase -> testCase.get("caseId").toString().startsWith("net.hasor.realmeteor.matrix.standard.StandardMatrixTest#savesAndFindsOrder[")));
        assertEquals(Map.of("id", 1001, "description", "统一订单", "attachment", "AAEAf/8="), cases.get(0).get("request"));
    }

    @Test
    public void recordsUnmatchedWireAndDiscoveryCombinations() throws Exception {
        // 三个端点分别具有不同 wire 或发现方式，只有自身方向可以匹配。
        catalog(List.of(scenario("save-find")));
        List<Map<String, Object>> endpoints = new ArrayList<>();
        endpoints.add(endpoint("first", "java", "", "wire-one", List.of("direct")));
        endpoints.add(endpoint("second", "go", "", "wire-two", List.of("direct")));
        endpoints.add(endpoint("third", "python", "", "wire-one", List.of("nacos")));
        manifest("peer/java/nxn.json", endpoints);

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of());

        // 不匹配原因也进入计划，避免把未覆盖误报为成功或隐藏在运行时跳过。
        assertEquals(3, objects(matrix.get("cases")).size());
        List<Map<String, Object>> rejected = objects(matrix.get("rejections"));
        assertEquals(6, rejected.size());
        long wireRejections = rejected.stream().filter(row -> {
            return StringUtils.equals("wire", row.get("reason").toString());
        }).count();
        long discoveryRejections = rejected.stream().filter(row -> {
            return StringUtils.equals("discovery", row.get("reason").toString());
        }).count();
        assertEquals(4, wireRejections);
        assertEquals(2, discoveryRejections);
    }

    @Test
    public void preservesSerialGroupFromEitherRoleWithoutChangingMatching() throws Exception {
        // 依赖独占环境的端点声明串行组，另一端没有声明时仍沿用该组。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> grouped = endpoint("grouped", "java", "", "wire", List.of("direct"));
        object(grouped.get("capabilities"), 0).put("serialGroup", "native-registry");
        Map<String, Object> ordinary = endpoint("ordinary", "java", "", "wire", List.of("direct"));
        manifest("peer/java/nxn.json", List.of(grouped, ordinary));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of());

        // 仍枚举全部四个方向，双方同组或任意一侧带组时元数据一致。
        List<Map<String, Object>> endpoints = objects(matrix.get("endpoints"));
        assertEquals("native-registry", endpoints.get(0).get("serialGroup"));
        List<Map<String, Object>> cases = objects(matrix.get("cases"));
        assertEquals(4, cases.size());
        long groupedCases = cases.stream().filter(testCase -> {
            return StringUtils.equals("native-registry", testCase.get("serialGroup").toString());
        }).count();
        long independentCases = cases.stream().filter(testCase -> {
            return StringUtils.equals("", testCase.get("serialGroup").toString());
        }).count();
        assertEquals(3, groupedCases);
        assertEquals(1, independentCases);
    }

    @Test
    public void rejectsDifferentSerialGroupsForAnOtherwiseCompatiblePair() throws Exception {
        // 线上兼容的双方不能要求两个互相不同的独占执行组。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> first = endpoint("first", "java", "", "wire", List.of("direct"));
        Map<String, Object> second = endpoint("second", "java", "", "wire", List.of("direct"));
        object(first.get("capabilities"), 0).put("serialGroup", "first-registry");
        object(second.get("capabilities"), 0).put("serialGroup", "second-registry");
        manifest("peer/java/nxn.json", List.of(first, second));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of()));

        assertTrue(failure.getMessage().contains("Incompatible serialGroup"));
        assertTrue(failure.getMessage().contains("first-registry"));
        assertTrue(failure.getMessage().contains("second-registry"));
    }

    @Test
    public void resolvesOwnedRuntimeProfileWithoutExecutingCommands() throws Exception {
        // SDK 运行图由所属工程生成，命令采用 argv，运行时参数仍由执行器填写。
        catalog(List.of(scenario("save-find")));
        Path classes = Files.createDirectories(this.temporary.resolve("runtime/classes with spaces"));
        Path runtime = this.temporary.resolve("runtime/profiles.json");
        NxnPlan.writeJson(runtime, Map.of("profiles", Map.of("current", Map.of("version", "4.5.6", "classpath", List.of(classes.toString()), "executable", "java"))));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "orders-wire", List.of("direct"));
        endpoint.put("runtimeManifestProperty", "owned.sdk.manifest");
        object(endpoint.get("capabilities"), 0).put("runtimeProfile", "current");
        List<String> providerCommand = new ArrayList<>();
        // argv 顺序不可改变，且相同的参数值允许重复出现。
        providerCommand.add("${executable}");
        providerCommand.add("-cp");
        providerCommand.add("${classpath}");
        providerCommand.add("${entry}");
        providerCommand.add("${settings}");
        providerCommand.add("${settings}");
        endpoint.put("launch", Map.of("provider", launch(providerCommand), "consumer", launch(List.of("${executable}", "${target}", "${requestFile}"))));
        manifest("peer/java/sdk/nxn.json", List.of(endpoint));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of("systemProperties", Map.of("owned.sdk.manifest", runtime.toString())));

        Map<String, Object> testCase = objects(matrix.get("cases")).get(0);
        Map<String, Object> provider = object(testCase.get("provider"));
        assertEquals("4.5.6", provider.get("version"));
        assertEquals(List.of("java", "-cp", classes.toString(), "dubbo", "${settings}", "${settings}"), provider.get("command"));
        assertEquals(this.temporary.resolve("peer/java/sdk").toString(), provider.get("workingDirectory"));
        assertEquals("${requestFile}", object(provider.get("environment")).get("REQUEST"));
    }

    @Test
    public void failsWhenDeclaredRuntimeProfileIsMissing() throws Exception {
        // 已声明能力不能因为构建产物缺失而消失。
        catalog(List.of(scenario("save-find")));
        Path runtime = this.temporary.resolve("runtime.json");
        NxnPlan.writeJson(runtime, Map.of("profiles", Map.of()));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        endpoint.put("runtimeManifest", runtime.toString());
        object(endpoint.get("capabilities"), 0).put("runtimeProfile", "not-built");
        manifest("peer/java/nxn.json", List.of(endpoint));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of()));

        assertTrue(failure.getMessage().contains("Missing runtime profile: not-built"));
    }

    @Test
    public void failsWhenDeclaredClasspathArtifactIsMissing() throws Exception {
        // 即便命令尚未启动，能力引用的运行产物也必须存在。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        endpoint.put("classpathProperty", "sdk.classpath");
        manifest("peer/java/nxn.json", List.of(endpoint));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of("systemProperties", Map.of("sdk.classpath", "missing.jar"))));

        assertTrue(failure.getMessage().contains("Missing runtime artifact"));
    }

    @Test
    public void acceptsAuditedClasspathFromItsOwningContainer() throws Exception {
        // runner 没有挂载 Java 容器的 Gradle 缓存，但产物已由 Java 构建审计。
        catalog(List.of(scenario("save-find")));
        Path remoteJar = this.temporary.resolve("java-container/gradle-cache/sdk.jar");
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        endpoint.put("classpathProperty", "sdk.classpath");
        endpoint.put("launch", Map.of("provider", launch(List.of("java", "-cp", "${classpath}", "Provider")), "consumer", launch(List.of("java", "-cp", "${classpath}", "Consumer"))));
        manifest("peer/java/nxn.json", List.of(endpoint));
        Map<String, Object> runtime = Map.of("systemProperties", Map.of("sdk.classpath", remoteJar.toString()), "artifacts", Map.of(remoteJar.toString(), "a".repeat(64)));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, runtime);

        Map<String, Object> provider = object(objects(matrix.get("cases")).get(0).get("provider"));
        assertEquals(List.of("java", "-cp", remoteJar.toString(), "Provider"), provider.get("command"));
        assertFalse(Files.exists(remoteJar));
    }

    @Test
    public void acceptsAuditedProfileClasspathAndExecutableFromItsOwningContainer() throws Exception {
        // 独立 SDK 的运行图及可执行文件可以由所属容器提供可核验的 SHA256 事实。
        catalog(List.of(scenario("save-find")));
        Path remoteJar = this.temporary.resolve("sdk-container/cache/sdk.jar");
        Path executable = this.temporary.resolve("sdk-container/bin/java");
        Path runtimeFile = this.temporary.resolve("runtime.json");
        NxnPlan.writeJson(runtimeFile, Map.of("profiles", Map.of("current", Map.of("classpath", List.of(remoteJar.toString()), "executable", executable.toString()))));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        endpoint.put("runtimeManifest", runtimeFile.toString());
        object(endpoint.get("capabilities"), 0).put("runtimeProfile", "current");
        endpoint.put("launch", Map.of("provider", launch(List.of("${executable}", "-cp", "${classpath}", "Provider")), "consumer", launch(List.of("${executable}", "-cp", "${classpath}", "Consumer"))));
        manifest("peer/java/nxn.json", List.of(endpoint));
        Map<String, Object> artifacts = Map.of(remoteJar.toString(), "a".repeat(64), executable.toString(), "b".repeat(64));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of("artifacts", artifacts));

        Map<String, Object> provider = object(objects(matrix.get("cases")).get(0).get("provider"));
        assertEquals(List.of(executable.toString(), "-cp", remoteJar.toString(), "Provider"), provider.get("command"));
        assertFalse(Files.exists(remoteJar));
        assertFalse(Files.exists(executable));
    }

    @Test
    public void rejectsAnUnauditedRuntimeProfileExecutable() throws Exception {
        // 声明运行图不能让任意不存在的可执行文件绕过产物校验。
        catalog(List.of(scenario("save-find")));
        Path executable = this.temporary.resolve("missing-runtime/bin/peer");
        Path runtimeFile = this.temporary.resolve("runtime.json");
        NxnPlan.writeJson(runtimeFile, Map.of("profiles", Map.of("current", Map.of("executable", executable.toString()))));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        endpoint.put("runtimeManifest", runtimeFile.toString());
        object(endpoint.get("capabilities"), 0).put("runtimeProfile", "current");
        manifest("peer/java/nxn.json", List.of(endpoint));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of()));

        assertTrue(failure.getMessage().contains("Missing runtime executable"));
    }

    @Test
    public void failsWhenCapabilityReferencesAnUnknownContract() throws Exception {
        // 契约拼写错误属于声明错误，不能被当作没有匹配对端。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        object(endpoint.get("capabilities"), 0).put("api", "unknown-v1");
        manifest("peer/java/nxn.json", List.of(endpoint));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of()));

        assertTrue(failure.getMessage().contains("Unknown API contract: unknown-v1"));
    }

    @Test
    public void failsOnDuplicateEndpointIdentityAcrossProjects() throws Exception {
        // 身份必须在发现范围内唯一，否则 caseId 无法稳定定位实际运行产物。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> endpoint = endpoint("duplicate", "java", "", "wire", List.of("direct"));
        manifest("peer/java/nxn.json", List.of(endpoint));
        manifest("integration/spring/nxn.json", List.of(endpoint));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of()));

        assertTrue(failure.getMessage().contains("Duplicate endpoint id: duplicate"));
    }

    @Test
    public void excludesGeneratedAndBuildTreesAndKeepsPlanStable() throws Exception {
        // 构建复制出的旧能力文件不参与发现；目录创建顺序不影响计划。
        catalog(List.of(scenario("save-find")));
        manifest("peer/z/nxn.json", List.of(endpoint("z", "java", "", "wire", List.of("direct"))));
        manifest("peer/a/nxn.json", List.of(endpoint("a", "go", "", "wire", List.of("direct"))));
        Files.createDirectories(this.temporary.resolve("peer/z/build/generated"));
        Files.writeString(this.temporary.resolve("peer/z/build/generated/nxn.json"), "invalid");
        Files.createDirectories(this.temporary.resolve("integration/node_modules/unused"));
        Files.writeString(this.temporary.resolve("integration/node_modules/unused/nxn.json"), "invalid");

        Map<String, Object> first = NxnMatrix.collect(this.temporary, Map.of());
        Map<String, Object> second = NxnMatrix.collect(this.temporary, Map.of());

        assertEquals(first, second);
        assertEquals(2, objects(first.get("endpoints")).size());
        assertEquals(4, objects(first.get("cases")).size());
        List<String> ids = objects(first.get("cases")).stream().map(row -> row.get("id").toString()).toList();
        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    public void consumesExistingGroupPlanWithoutDiscoveringCapabilities() throws Exception {
        // 分组工作进程只读取自己的计划，即使计划旁边没有任何工程也应能够展开。
        Path plan = this.temporary.resolve("group/matrix.json");
        NxnPlan.writeJson(plan, Map.of("cases", List.of(Map.of("id", "fixed-case", "api", "order-v1"))));
        String previous = System.getProperty("meteor.test.matrix.plan");
        System.setProperty("meteor.test.matrix.plan", plan.toString());
        try {
            List<Object[]> arguments = NxnMatrix.arguments();

            assertEquals(1, arguments.size());
            assertEquals("fixed-case", arguments.get(0)[0]);
            assertEquals(Map.of("id", "fixed-case", "api", "order-v1"), arguments.get(0)[1]);
        } finally {
            restore("meteor.test.matrix.plan", previous);
        }
    }

    @Test
    public void isolatesDifferentApiExecutionClassesAndResolvesContractPlaceholders() throws Exception {
        // 同一个端点可以声明不同契约，各契约分别绑定自己的执行类和方法。
        String orderClass = "net.hasor.realmeteor.matrix.standard.StandardMatrixTest";
        String scalarClass = "net.hasor.realmeteor.matrix.standard.ScalarMatrixTest";
        Map<String, Object> scalarScenario = new LinkedHashMap<>(scenario("roundtrip"));
        scalarScenario.put("class", scalarClass);
        scalarScenario.put("method", "roundTripsScalar");
        NxnPlan.writeJson(this.temporary.resolve("resource/matrix/apis.json"), Map.of("schemaVersion", 1, "apis", List.of(Map.of("id", "order-v1", "scenarios", List.of(scenario("save-find"))), Map.of("id", "scalar-v1", "scenarios", List.of(scalarScenario)))));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        Map<String, Object> scalarCapability = new LinkedHashMap<>(object(endpoint.get("capabilities"), 0));
        scalarCapability.put("id", "scalar");
        scalarCapability.put("api", "scalar-v1");
        endpoint.put("capabilities", List.of(object(endpoint.get("capabilities"), 0), scalarCapability));
        endpoint.put("launch", Map.of("provider", launch(List.of("program", "${api}", "${wire}")), "consumer", launch(List.of("program", "${api}", "${wire}"))));
        manifest("peer/java/nxn.json", List.of(endpoint));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of());
        Path plan = this.temporary.resolve("group/matrix.json");
        NxnPlan.writeJson(plan, matrix);
        String previous = System.getProperty("meteor.test.matrix.plan");
        System.setProperty("meteor.test.matrix.plan", plan.toString());
        try {
            // 参数只属于指定执行类，不匹配的 API 仍保留在原计划及完整参数列表中。
            List<Object[]> orderArguments = NxnMatrix.arguments(orderClass);
            List<Object[]> scalarArguments = NxnMatrix.arguments(scalarClass);
            assertEquals(2, NxnMatrix.arguments().size());
            assertEquals(1, orderArguments.size());
            assertEquals(1, scalarArguments.size());
            Map<String, Object> scalarCase = object(scalarArguments.get(0)[1]);
            assertEquals(scalarClass, scalarCase.get("class"));
            assertEquals("roundTripsScalar", scalarCase.get("method"));
            assertTrue(scalarCase.get("caseId").toString().startsWith(scalarClass + "#roundTripsScalar["));
            Map<String, Object> provider = object(scalarCase.get("provider"));
            assertEquals(List.of("program", "scalar-v1", "wire"), provider.get("command"));
            assertTrue(NxnMatrix.arguments(orderClass + "Extra").isEmpty());
        } finally {
            restore("meteor.test.matrix.plan", previous);
        }
    }

    @Test
    public void rejectsUnknownPlanningPlaceholder() throws Exception {
        // 拼写错误不能被留到 Docker 内执行才变成难以定位的启动失败。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        endpoint.put("launch", Map.of("provider", launch(List.of("java", "${claspath}")), "consumer", launch(List.of("java"))));
        manifest("peer/java/nxn.json", List.of(endpoint));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of()));

        assertTrue(failure.getMessage().contains("${claspath}"));
    }

    @Test
    public void appliesSharedLaunchEnvironmentAndRoleOverrides() throws Exception {
        // 公共运行环境属于端点，角色仅覆盖自己的环境值和工作目录。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        Map<String, Object> launch = new LinkedHashMap<>(object(endpoint.get("launch")));
        launch.put("workingDirectory", "${testDirectory}/shared-work");
        launch.put("environment", Map.of("COMMON", "${protocol}", "ROLE", "shared"));
        Map<String, Object> consumer = new LinkedHashMap<>(object(launch.get("consumer")));
        consumer.put("workingDirectory", "${project}/consumer-work");
        consumer.put("environment", Map.of("ROLE", "consumer"));
        launch.put("consumer", consumer);
        endpoint.put("launch", launch);
        manifest("peer/java/nxn.json", List.of(endpoint));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of());

        Map<String, Object> testCase = objects(matrix.get("cases")).get(0);
        Map<String, Object> provider = object(testCase.get("provider"));
        Map<String, Object> actualConsumer = object(testCase.get("consumer"));
        assertEquals(this.temporary.resolve("shared-work").toString(), provider.get("workingDirectory"));
        assertEquals("dubbo", object(provider.get("environment")).get("COMMON"));
        assertEquals("shared", object(provider.get("environment")).get("ROLE"));
        String consumerDirectory = this.temporary.resolve("peer/java/consumer-work").toString();
        assertEquals(consumerDirectory, actualConsumer.get("workingDirectory"));
        assertEquals(Map.of("COMMON", "dubbo", "ROLE", "consumer"), actualConsumer.get("environment"));
    }

    @Test
    public void rejectsEmptyCapabilityDeclaration() throws Exception {
        // 已经被发现的项目不能通过空声明静默退出覆盖清单。
        catalog(List.of(scenario("save-find")));
        Map<String, Object> endpoint = endpoint("sdk", "java", "", "wire", List.of("direct"));
        endpoint.put("capabilities", List.of());
        manifest("peer/java/nxn.json", List.of(endpoint));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMatrix.collect(this.temporary, Map.of()));

        assertTrue(failure.getMessage().contains("at least one capability"));
    }

    @Test
    public void retainsDeclaredExecutorsWhenNoConsumerCanFormACase() throws Exception {
        // 只有提供者时没有可执行组合，API 的执行类归属仍由契约声明明确保留。
        String orderClass = "net.hasor.realmeteor.matrix.standard.StandardMatrixTest";
        String scalarClass = "net.hasor.realmeteor.matrix.standard.ScalarMatrixTest";
        Map<String, Object> scalarScenario = new LinkedHashMap<>(scenario("roundtrip"));
        scalarScenario.put("class", scalarClass);
        scalarScenario.put("method", "roundTripsScalar");
        NxnPlan.writeJson(this.temporary.resolve("resource/matrix/apis.json"), Map.of("schemaVersion", 1, "apis", List.of(Map.of("id", "order-v1", "scenarios", List.of(scenario("save-find"))), Map.of("id", "scalar-v1", "scenarios", List.of(scalarScenario)))));
        Map<String, Object> endpoint = endpoint("provider", "java", "", "wire", List.of("direct"));
        object(endpoint.get("capabilities"), 0).put("roles", List.of("provider"));
        manifest("peer/java/nxn.json", List.of(endpoint));

        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of());

        // 未声明端点能力的 scalar API 也保留执行入口，防止编排器把它当作普通测试执行。
        List<Map<String, Object>> executors = objects(matrix.get("executors"));
        Map<String, Object> orderExecutor = Map.of("class", orderClass, "method", "savesAndFindsOrder", //
                "api", "order-v1", "scenario", "save-find");
        Map<String, Object> scalarExecutor = Map.of("class", scalarClass, "method", "roundTripsScalar", //
                "api", "scalar-v1", "scenario", "roundtrip");
        assertEquals(List.of(orderExecutor, scalarExecutor), executors);
        assertEquals(1, objects(matrix.get("endpoints")).size());
        assertTrue(objects(matrix.get("cases")).isEmpty());
        assertTrue(objects(matrix.get("rejections")).isEmpty());
    }

    @Test
    public void permitsNoCapabilitiesForAnUnrelatedRuntimeFixture() throws Exception {
        // 原有编排单测夹具不包含对端工程，不能强制它们持有业务契约目录。
        Map<String, Object> matrix = NxnMatrix.collect(this.temporary, Map.of());

        assertTrue(objects(matrix.get("cases")).isEmpty());
        assertTrue(objects(matrix.get("endpoints")).isEmpty());
        assertFalse(matrix.containsKey("executors"));
        assertFalse(Files.exists(this.temporary.resolve("resource")));
    }

    private void catalog(List<Map<String, Object>> scenarios) throws Exception {
        NxnPlan.writeJson(this.temporary.resolve("resource/matrix/apis.json"), Map.of("schemaVersion", 1, "apis", List.of(Map.of("id", "order-v1", "scenarios", scenarios))));
    }

    private void manifest(String path, List<Map<String, Object>> endpoints) throws Exception {
        NxnPlan.writeJson(this.temporary.resolve(path), Map.of("schemaVersion", 1, "endpoints", endpoints));
    }

    private static Map<String, Object> scenario(String id) {
        return Map.of("id", id, "method", "savesAndFindsOrder", "request", Map.of("id", 1001, "description", "统一订单", "attachment", "AAEAf/8="));
    }

    private static Map<String, Object> endpoint(String id, String language, String version, String wire, List<String> discoveries) {
        Map<String, Object> capability = new LinkedHashMap<>();
        capability.put("id", "current");
        capability.put("api", "order-v1");
        capability.put("protocol", "dubbo");
        capability.put("protocolVersion", version);
        capability.put("wire", wire);
        capability.put("roles", List.of("provider", "consumer"));
        capability.put("discoveries", discoveries);
        Map<String, Object> endpoint = new LinkedHashMap<>();
        endpoint.put("id", id);
        endpoint.put("language", language);
        endpoint.put("implementation", id);
        endpoint.put("launch", Map.of("provider", launch(List.of("program", "provider")), "consumer", launch(List.of("program", "consumer"))));
        endpoint.put("capabilities", List.of(capability));
        return endpoint;
    }

    private static Map<String, Object> launch(List<String> command) {
        return Map.of("container", "java", "command", command, "environment", Map.of("REQUEST", "${requestFile}"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> object(Object value, int index) {
        return objects(value).get(index);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static void restore(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
