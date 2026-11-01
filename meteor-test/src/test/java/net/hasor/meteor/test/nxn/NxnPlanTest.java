package net.hasor.meteor.test.nxn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

public class NxnPlanTest {
    private static final String PYTHON_TEST = "net.hasor.realmeteor.protocol.thrift.python.PythonThriftTypeInteropTest";
    private static final String GO_TEST = "net.hasor.realmeteor.matrix.types.ThriftGoTypeMatrixTest";
    private static final String NATIVE_PACKAGE = "net.hasor.realmeteor.registry.dubbo.";
    private static final String NATIVE_EXAMPLE = "net.hasor.realmeteor.example.sdk.NativeRegistryScenarioTest";
    private static final String NATIVE_FIXTURE = "net.hasor.realmeteor.environment.storage.RegistryServiceReuseTest";
    @TempDir
    public Path temporary;

    @Test
    public void defaultsToSixteenConcurrentGroups() {
        // 默认并发限制 JUnit JVM 数量，不代表创建额外容器。
        NxnPlan.Options options = NxnPlan.parseArguments(new String[] { "--run-id", "run" });
        assertEquals(16, options.groups);
    }

    @Test
    public void testcaseFailuresContinueByDefault() {
        // 默认忽略中途失败，统一在所有分组结束后判定整轮结果。
        NxnPlan.Options options = NxnPlan.parseArguments(new String[] { "--run-id", "run" });
        assertTrue(options.skipError);
    }

    @Test
    public void skipErrorCanBeExplicitlyEnabled() {
        // 显式启用参数沿用默认的完整执行行为。
        String[] arguments = { "--run-id", "run", "--skipError", "true" };
        assertTrue(NxnPlan.parseArguments(arguments).skipError);
    }

    @Test
    public void skipErrorCanBeExplicitlyDisabled() {
        // 等号写法同样可以选择快速失败。
        String[] arguments = { "--run-id", "run", "--skipError=false" };
        assertFalse(NxnPlan.parseArguments(arguments).skipError);
    }

    @Test
    public void invalidSkipErrorValueIsRejected() {
        // 拼错开关不能被 Boolean.parseBoolean 静默解释为关闭。
        String[] arguments = { "--run-id", "run", "--skipError", "yes" };
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.parseArguments(arguments));
        assertTrue(failure.getMessage().contains("--skipError must be true or false"));
    }

    @Test
    public void manualGroupCountCanBeOne() {
        // 允许只有一个独立工作组。
        assertEquals(1, NxnPlan.parseGroupRequest("1"));
    }

    @Test
    public void manualGroupCountCanBeSixteen() {
        // 允许显式使用并发上限。
        assertEquals(16, NxnPlan.parseGroupRequest("16"));
    }

    @Test
    public void automaticGroupCountIsRejected() {
        // 固定并发配置不再接受自动资源估算选项。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.parseGroupRequest("auto"));
        assertTrue(failure.getMessage().contains("between 1 and 16"));
    }

    @Test
    public void zeroGroupCountIsRejected() {
        // 调度必须至少保留一个工作组。
        assertThrows(IllegalArgumentException.class, () -> NxnPlan.parseGroupRequest("0"));
    }

    @Test
    public void groupCountAboveLimitIsRejected() {
        // 并发上限保持为十六组。
        assertThrows(IllegalArgumentException.class, () -> NxnPlan.parseGroupRequest("17"));
    }

    @Test
    public void unfilteredSelectionPreservesEveryRuntimeClass() {
        // 完整回归按类名排序并保留每个运行类。
        Map<String, List<String>> selected = NxnPlan.selectClasses(List.of(), List.of("test.B", "test.A"));
        assertEquals(List.of("test.A", "test.B"), new ArrayList<>(selected.keySet()));
        assertEquals(Map.of("test.A", List.of(), "test.B", List.of()), selected);
    }

    @Test
    public void classWildcardSelectsWholeClass() {
        // 简短类名允许使用通配符选择完整类。
        List<String> classes = List.of("test.FirstTest", "test.Other");
        Map<String, List<String>> selected = NxnPlan.selectClasses(List.of("First*"), classes);
        assertEquals(Map.of("test.FirstTest", List.of()), selected);
    }

    @Test
    public void methodFilterKeepsParameterNameLiteral() {
        // 参数化名称中的方括号属于名称本身。
        List<String> includes = List.of("FirstTest.call[json]");
        Map<String, List<String>> selected = NxnPlan.selectClasses(includes, List.of("test.FirstTest"));
        assertEquals(Map.of("test.FirstTest", List.of("test.FirstTest.call[json]")), selected);
    }

    @Test
    public void packageWildcardMethodFilterPreservesClassOwnership() {
        // 包通配符选择方法时保留每个方法的真实所属类。
        List<String> classes = List.of("test.OneTest", "test.TwoTest", "other.OneTest");
        Map<String, List<String>> selected = NxnPlan.selectClasses(List.of("test.*Test.call*"), classes);

        // 另一个包中的同名类不进入本次执行。
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("test.OneTest", List.of("test.OneTest.call*"));
        expected.put("test.TwoTest", List.of("test.TwoTest.call*"));
        assertEquals(expected, selected);
    }

    @Test
    public void classSelectionSupersedesItsMethodFilters() {
        // 完整类选择覆盖先前对该类的局部方法筛选。
        List<String> includes = List.of("test.One.call", "test.One");
        Map<String, List<String>> selected = NxnPlan.selectClasses(includes, List.of("test.One"));
        assertEquals(Map.of("test.One", List.of()), selected);
    }

    @Test
    public void repeatedMethodFilterIsExecutedOnlyOnce() {
        // 重复入口参数不会产生重复 testcase。
        List<String> includes = List.of("One.call", "One.call");
        Map<String, List<String>> selected = NxnPlan.selectClasses(includes, List.of("test.One"));
        assertEquals(Map.of("test.One", List.of("test.One.call")), selected);
    }

    @Test
    public void unknownFilterFailsBeforeExecution() {
        // 拼错筛选条件必须失败，不能退化成空运行。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.selectClasses(List.of("MissingTest"), List.of("test.One")));
        assertTrue(failure.getMessage().contains("No JUnit class matches"));
    }

    @Test
    public void duplicateRuntimeClassIsRejected() {
        // 运行类列表中的重复记录会破坏唯一执行保证。
        List<String> classes = List.of("test.One", "test.One");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.selectClasses(List.of(), classes));
        assertTrue(failure.getMessage().contains("unique"));
    }

    @Test
    public void emptyRuntimeClassListIsRejected() {
        // 未发现测试不能宣告完成全量回归。
        assertThrows(IllegalArgumentException.class, () -> NxnPlan.selectClasses(List.of(), List.of()));
    }

    @Test
    public void parameterBracketsAreNotCharacterClassWildcards() {
        // 只把星号解释为通配符，避免参数化名称误选。
        assertFalse(NxnPlan.matches("test.One.calla", "test.One.call[abc]"));
    }

    @Test
    public void matrixPropertiesPreserveOriginalValues() {
        // 兼容入口只传递矩阵属性，不拆分属性值。
        String[] arguments = { "--run-id", "run", ":meteor-test:test", "-Dmeteor.matrix.protocol=grpc,thrift" };
        NxnPlan.Options options = NxnPlan.parseArguments(arguments);
        assertEquals(Map.of("meteor.matrix.protocol", "grpc,thrift"), options.matrix);
    }

    @Test
    public void unrelatedGradleTaskIsRejected() {
        // 错误的 Gradle 任务不能被静默忽略。
        String[] arguments = { "--run-id", "run", ":meteor:test" };
        assertThrows(IllegalArgumentException.class, () -> NxnPlan.parseArguments(arguments));
    }

    @Test
    public void unsafeRunIdentifierIsRejected() {
        // 运行标识不能越过归档目录边界。
        String[] arguments = { "--run-id", "../other" };
        assertThrows(IllegalArgumentException.class, () -> NxnPlan.parseArguments(arguments));
    }

    @Test
    public void historicalWeightsBalanceWholeClasses() {
        // 普通类按历史耗时平衡，类内部 testcase 不拆散。
        List<String> classes = List.of("test.One", "test.Two", "test.Three", "test.Four");
        Map<String, Object> weights = Map.of("test.One", 5, "test.Two", 4, "test.Three", 3, "test.Four", 2);
        List<Map<String, Object>> groups = NxnPlan.partition(classes, weights, 2, Map.of(), Map.of());

        // 两个工作组分别承接七秒的预计负载。
        assertEquals(2, groups.size());
        assertEquals(7.0, ((Number) groups.get(0).get("expectedSeconds")).doubleValue(), 0.0);
        assertEquals(7.0, ((Number) groups.get(1).get("expectedSeconds")).doubleValue(), 0.0);
    }

    @Test
    public void groupsNeverOutnumberAvailableSelections() {
        // 少量选择不会创建没有工作的 JVM。
        List<Map<String, Object>> groups = NxnPlan.partition(List.of("test.One"), Map.of(), 16, Map.of(), Map.of());
        assertEquals(1, groups.size());
    }

    @Test
    public void declaredMatricesSplitOnlyAtExistingDirections() {
        // 只有声明过的矩阵方向可以拆为独立执行选择。
        List<String> classes = List.of(PYTHON_TEST, GO_TEST);
        List<Map<String, Object>> groups = NxnPlan.partition(classes, Map.of(), 16, Map.of(), Map.of());
        List<String> actual = collectedSelections(groups, "filters");

        // 每个原有方向保留一次，不重新生成 testcase。
        List<String> expected = new ArrayList<>();
        expected.add(PYTHON_TEST + ".meteorProviderPreservesPythonNativeType");
        expected.add(PYTHON_TEST + ".meteorConsumerPreservesPythonNativeType");
        expected.add(GO_TEST + ".goConsumerPreservesSelectedTypeOnMeteorProvider");
        expected.add(GO_TEST + ".meteorConsumerPreservesSelectedTypeOnGoProvider");
        expected.sort(String::compareTo);
        actual.sort(String::compareTo);
        assertEquals(expected, actual);
    }

    @Test
    public void directionFiltersIncludeEveryParameterSuffix() {
        // 方向筛选保留该方法下所有参数化数据。
        List<Map<String, Object>> groups = NxnPlan.partition(List.of(PYTHON_TEST), Map.of(), 2, Map.of(), Map.of());
        for (Map<String, Object> group : groups) {
            List<String> filters = strings(group.get("filters"));
            List<String> includes = strings(group.get("includes"));
            assertEquals(List.of(filters.get(0) + "*"), includes);
        }
    }

    @Test
    public void directionMethodWeightOverridesHalfClassEstimate() {
        // 已采集的方法耗时代替简单的二等分估算。
        Map<String, Object> weights = Map.of(PYTHON_TEST, 11, "test.One", 3);
        Map<String, Object> methods = Map.of("meteorProviderPreservesPythonNativeType", 7,
                "meteorConsumerPreservesPythonNativeType", 4);
        Map<String, Object> methodWeights = Map.of(PYTHON_TEST, methods);
        List<String> classes = List.of(PYTHON_TEST, "test.One");
        List<Map<String, Object>> groups = NxnPlan.partition(classes, weights, 2, methodWeights, Map.of());

        // 方法权重和普通类组成平衡的两组。
        assertEquals(7.0, ((Number) groups.get(0).get("expectedSeconds")).doubleValue(), 0.0);
        assertEquals(7.0, ((Number) groups.get(1).get("expectedSeconds")).doubleValue(), 0.0);
    }

    @Test
    public void explicitMethodFilterDoesNotExpandOtherDirections() {
        // 用户指定参数化方法时不扩展同一矩阵的另一个方向。
        String requestedMethod = PYTHON_TEST + ".specific[json]";
        Map<String, List<String>> filters = Map.of(PYTHON_TEST, List.of(requestedMethod));
        List<Map<String, Object>> groups = NxnPlan.partition(List.of(PYTHON_TEST),
                Map.of(PYTHON_TEST, 10), 16, Map.of(), filters);
        assertEquals(List.of(requestedMethod), groups.get(0).get("includes"));
    }

    @Test
    public void nativeZooKeeperClassesShareOneSerialGroup() {
        // 使用相同官方 SDK 注册根目录的场景保持串行。
        List<String> classes = List.of(NATIVE_PACKAGE + "FirstTest", NATIVE_EXAMPLE, "test.Ordinary");
        List<Map<String, Object>> groups = NxnPlan.partition(classes, Map.of(), 16, Map.of(), Map.of());
        Map<String, Object> lane = groupContaining(groups, NATIVE_EXAMPLE);
        assertTrue(strings(lane.get("classes")).contains(NATIVE_PACKAGE + "FirstTest"));
    }

    @Test
    public void registryFixtureUsesNativeZooKeeperSerialGroup() {
        // 注册环境复用夹具和原生 SDK 场景共用同一个串行组。
        List<String> classes = List.of(NATIVE_FIXTURE, NATIVE_EXAMPLE, "test.Other");
        List<Map<String, Object>> groups = NxnPlan.partition(classes, Map.of(), 16, Map.of(), Map.of());
        Map<String, Object> lane = groupContaining(groups, NATIVE_FIXTURE);
        assertTrue(strings(lane.get("classes")).contains(NATIVE_EXAMPLE));
    }

    @Test
    public void negativeDurationIsRejected() {
        // 错误的历史耗时不能进入负载计划。
        Map<String, Object> weights = Map.of("test.One", -1);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.partition(List.of("test.One"), weights, 1, Map.of(), Map.of()));
        assertTrue(failure.getMessage().contains("Invalid duration"));
    }

    @Test
    public void mutablePathsAreScopedToGroupDirectory() {
        // 写入位置归属当前工作组，避免并发共享日志目录。
        Map<String, Object> runtime = Map.of("systemProperties", Map.of("thrift.peer.classpath", "/sdk/thrift.jar"));
        Map<String, Object> group = Map.of("id", "g2", "outputDirectory", "/results/g2");
        Map<String, String> properties = NxnPlan.systemProperties(runtime, group, "run", Map.of());
        assertEquals("/results/g2/container-scenarios", properties.get("meteor.test.container.workDirectory"));
        assertEquals("/results/g2/interoperability", properties.get("meteor.test.runDirectory"));
    }

    @Test
    public void tlsInputResourcesAreNotRewrittenAsOutput() {
        // 输入证书保持导出的只读资源路径。
        Map<String, Object> runtime = Map.of("systemProperties", Map.of("soap.peer.tls.directory", "/compiled/certs"));
        Map<String, Object> group = Map.of("id", "g1", "outputDirectory", "/g1");
        Map<String, String> properties = NxnPlan.systemProperties(runtime, group, "r", Map.of());
        assertEquals("/compiled/certs", properties.get("soap.peer.tls.directory"));
    }

    @Test
    public void javaInvocationUsesExportedClasspathWithoutGradle() {
        // 容器内工作组直接使用导出的类路径启动独立 JUnit JVM。
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes", "/lib/junit.jar"));
        List<String> command = NxnPlan.javaCommand(runtime, commandGroup(), "run", Map.of(), "/workspace/meteor-test");
        assertEquals("/classes:/lib/junit.jar", command.get(command.indexOf("-cp") + 1));
        assertFalse(String.join(" ", command).contains("gradle"));
    }

    @Test
    public void javaInvocationContinuesErrorsWhenNoPolicyWasRecorded() {
        // 独立命令构造也保持默认继续执行，避免未填写组字段时意外快速失败。
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"));
        List<String> command = NxnPlan.javaCommand(runtime, commandGroup(), "run", Map.of(), "/workspace/meteor-test");
        assertEquals("true", command.get(command.indexOf("--skipError") + 1));
    }

    @Test
    public void preparationRecordsAndPassesTheFailurePolicy() throws Exception {
        // 在创建真实分组和启动清单之前选择快速失败。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        options.skipError = false;
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"), "classNames", List.of("test.One"));

        // 清单、分组与 JUnit 参数保存同一份执行策略。
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        assertEquals(Boolean.FALSE, manifest.get("skipError"));
        Map<String, Object> group = objects(manifest.get("groups")).get(0);
        assertEquals(Boolean.FALSE, group.get("skipError"));
        Map<String, Object> launch = NxnPlan.readObject(Path.of((String) group.get("launchFile")));
        List<String> command = strings(launch.get("command"));
        assertEquals("false", command.get(command.indexOf("--skipError") + 1));
    }

    @Test
    public void junitJvmPreservesGradleHeapBudget() {
        // 每个并发 JVM 沿用测试任务的 1536 MiB 堆上限。
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"));
        List<String> command = NxnPlan.javaCommand(runtime, commandGroup(), "run", Map.of(), "/workspace/meteor-test");
        List<String> heapArguments = new ArrayList<>();
        for (String argument : command) {
            if (argument.startsWith("-Xmx")) {
                heapArguments.add(argument);
            }
        }
        assertEquals(List.of("-Xmx1536m"), heapArguments);
    }

    @Test
    public void junitJvmPreservesExportedModuleAccess() {
        // 官方 SDK 所需模块开放参数由导出的运行信息传递。
        String moduleAccess = "--add-opens=java.base/java.io=ALL-UNNAMED";
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"), "jvmArguments", List.of(moduleAccess));
        List<String> command = NxnPlan.javaCommand(runtime, commandGroup(), "run", Map.of(), "/workspace/meteor-test");
        assertTrue(command.contains(moduleAccess));
    }

    @Test
    public void preparePreservesOriginalCaseOwnershipInManifest() throws Exception {
        // 使用独立输出位置，缺失权重时保留每个原有类选择。
        Path directory = this.temporary;
        Path output = directory.resolve("groups");
        NxnPlan.Options options = optionsForOutput(output, directory.resolve("missing.json"));
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"),
                "classNames", List.of("test.One", "test.Two"));

        // 清单中的选择仍直接对应原始 testcase 所属类。
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        assertEquals(List.of("test.One", "test.Two"), manifest.get("selectedSelections"));
    }

    @Test
    public void prepareRejectsPreviousRunOutput() throws Exception {
        // 旧 XML 不能被新一轮执行拾取。
        Path directory = this.temporary;
        Files.writeString(directory.resolve("old.xml"), "old");
        NxnPlan.Options options = optionsForOutput(directory, directory.resolve("missing.json"));
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"), "classNames", List.of("test.One"));

        // 非空输出目录在准备阶段立即报错。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.prepare(options, runtime));
        assertTrue(failure.getMessage().contains("must be empty"));
    }

    @Test
    public void prepareLeavesWrapperTemporaryDirectoryUncreated() throws Exception {
        // 临时目录的创建与清理由进程执行器拥有。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"), "classNames", List.of("test.One"));
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);

        // 计划只声明该路径，不提前抢占执行器的生命周期。
        Map<String, Object> group = objects(manifest.get("groups")).get(0);
        JsonNode launch = new ObjectMapper().readTree(Path.of((String) group.get("launchFile")).toFile());
        assertFalse(Files.exists(Path.of(launch.path("temporaryDirectory").asText())));
    }

    @Test
    public void prepareUsesExistingHistoricalWeightFile() throws Exception {
        // 权重文件保持旧的 classes 与 methods 双层结构。
        Path directory = this.temporary;
        Path weights = directory.resolve("weights.json");
        Files.writeString(weights, "{\"classes\":{\"test.One\":8,\"test.Two\":2}}");
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), weights);
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"),
                "classNames", List.of("test.One", "test.Two"));

        // 准备后的组预计耗时来自持久化权重。
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        List<Map<String, Object>> groups = objects(manifest.get("groups"));
        assertEquals(8.0, ((Number) groups.get(0).get("expectedSeconds")).doubleValue(), 0.0);
        assertEquals(2.0, ((Number) groups.get(1).get("expectedSeconds")).doubleValue(), 0.0);
    }

    @Test
    public void prepareRejectsEmptyRuntimeClasspath() throws Exception {
        // 未导出运行依赖时不能启动一个必然失败的空类路径 JVM。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        Map<String, Object> runtime = Map.of("classpath", List.of(), "classNames", List.of("test.One"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.prepare(options, runtime));
        assertTrue(failure.getMessage().contains("classpath"));
    }

    @Test
    public void rpcArgumentsNormalizeTheProtocolName() {
        // 脚本使用的常见连字符写法归并到同一协议入口。
        String[] arguments = { "--run-id", "run", "--rpc", " GRPC-Web ", "--rpc-version", " 1 " };

        // 版本保留原始协议语义，只清理参数两侧的空白。
        NxnPlan.Options options = NxnPlan.parseArguments(arguments);
        assertEquals("grpc-web", options.rpc);
        assertEquals("1", options.rpcVersion);
    }

    @Test
    public void rpcVersionRequiresAProtocolName() {
        // 版本无法独立决定要执行哪种 RPC。
        String[] arguments = { "--run-id", "run", "--rpc-version", "2" };

        // 缺失协议在创建分组之前报错。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.parseArguments(arguments));
        assertTrue(failure.getMessage().contains("--rpc-version requires --rpc"));
    }

    @Test
    public void fullPreparationPreservesClassesWithoutRpcDeclarations() throws Exception {
        // 全量执行继续接受没有 rpcCases 字段的旧运行清单。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"),
                "classNames", List.of("test.Unannotated", "test.Other"));

        // 不因新增 RPC 筛选能力丢失原有框架或环境测试。
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        assertEquals(List.of("test.Other", "test.Unannotated"), manifest.get("selectedClasses"));
        assertEquals(Map.of(), manifest.get("rpcCatalog"));
    }

    @Test
    public void rpcPreparationPassesTheSameDeclarationsToTheJUnitGroup() throws Exception {
        // 本轮只执行 SOAP 1.2，方法声明随组独立保存。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        options.rpc = "soap";
        options.rpcVersion = "1.2";
        String className = "test.MixedTest";
        Map<String, Object> declaration = Map.of("name", "soap", "version", "1.2", "cases", "soap12/*");
        Map<String, Object> rpcCases = Map.of(className, Map.of("callsPeer", List.of(declaration)));
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"),
                "classNames", List.of(className), "rpcCases", rpcCases);

        // 清单和实际 JVM 参数使用同一协议、版本及声明文件。
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        Map<String, Object> group = objects(manifest.get("groups")).get(0);
        Path casesFile = Path.of((String) group.get("rpcCasesFile"));
        assertEquals(rpcCases, NxnPlan.readObject(casesFile));
        assertEquals(List.of(className + ".callsPeer"), group.get("filters"));
        Map<String, Object> launch = NxnPlan.readObject(Path.of((String) group.get("launchFile")));
        List<String> command = strings(launch.get("command"));
        assertEquals("soap", command.get(command.indexOf("--rpc") + 1));
        assertEquals("1.2", command.get(command.indexOf("--rpc-version") + 1));
        assertEquals(casesFile.toString(), command.get(command.indexOf("--rpc-cases-file") + 1));
    }

    @Test
    public void rpcPreparationIntersectsTheExplicitTestcaseSelection() throws Exception {
        // 两个类均使用 gRPC，但用户只指定其中一个测试方法。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        options.rpc = "grpc";
        options.tests.add("OneTest.call");
        Map<String, Object> declaration = Map.of("name", "grpc", "version", "", "cases", "*");
        Map<String, Object> methods = Map.of("call", List.of(declaration));
        Map<String, Object> rpcCases = Map.of("test.OneTest", methods, "test.TwoTest", methods);
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"),
                "classNames", List.of("test.OneTest", "test.TwoTest"), "rpcCases", rpcCases);

        // 协议筛选不能覆盖用户已经缩小的 testcase 范围。
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        assertEquals(List.of("test.OneTest"), manifest.get("selectedClasses"));
        assertEquals(List.of("test.OneTest.call"), manifest.get("selectedSelections"));
    }

    @Test
    public void rpcPreparationRecordsAvailableProtocolVersions() throws Exception {
        // 目录中的 Dubbo 版本来自显式大版本声明。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        options.rpc = "dubbo";
        options.rpcVersion = "3";
        Map<String, Object> legacy = Map.of("name", "dubbo", "version", "2", "cases", "2.7.23/*");
        Map<String, Object> current = Map.of("name", "dubbo", "version", "3", "cases", "3.3.6/*");
        Map<String, Object> methods = Map.of("legacy", List.of(legacy), "current", List.of(current));
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"),
                "classNames", List.of("test.DubboTest"), "rpcCases", Map.of("test.DubboTest", methods));

        // 清单说明可选版本，同时只准备目标大版本的方法。
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        assertEquals(Map.of("dubbo", List.of("2", "3")), manifest.get("rpcCatalog"));
        assertEquals(List.of("test.DubboTest.current"), manifest.get("selectedSelections"));
    }

    @Test
    public void rpcPreparationRejectsAnEmptyIntersection() throws Exception {
        // 用户选择 SOAP 方法，同时要求执行 gRPC。
        Path directory = this.temporary;
        NxnPlan.Options options = optionsForOutput(directory.resolve("groups"), directory.resolve("missing.json"));
        options.rpc = "grpc";
        options.tests.add("MixedTest.soapCall");
        Map<String, Object> grpc = Map.of("name", "grpc", "version", "", "cases", "*");
        Map<String, Object> soap = Map.of("name", "soap", "version", "1.2", "cases", "*");
        Map<String, Object> methods = Map.of("grpcCall", List.of(grpc), "soapCall", List.of(soap));
        Map<String, Object> runtime = Map.of("classpath", List.of("/classes"),
                "classNames", List.of("test.MixedTest"), "rpcCases", Map.of("test.MixedTest", methods));

        // 无交集时不能生成一个看似成功的空执行计划。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnPlan.prepare(options, runtime));
        assertTrue(failure.getMessage().contains("No @NxnRpc testcase"));
        assertFalse(Files.exists(options.output));
    }

    private static NxnPlan.Options optionsForOutput(Path output, Path weights) {
        String[] arguments = { "--run-id", "run", "--output", output.toString(), "--weights", weights.toString() };
        return NxnPlan.parseArguments(arguments);
    }

    private static Map<String, Object> commandGroup() {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "g1");
        group.put("outputDirectory", "/g1");
        group.put("classesFile", "/g1/classes.txt");
        group.put("filtersFile", "/g1/filters.txt");
        return group;
    }

    private static List<String> collectedSelections(List<Map<String, Object>> groups, String field) {
        List<String> selections = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            selections.addAll(strings(group.get(field)));
        }
        return selections;
    }

    private static Map<String, Object> groupContaining(List<Map<String, Object>> groups, String name) {
        for (Map<String, Object> group : groups) {
            if (strings(group.get("classes")).contains(name)) {
                return group;
            }
        }
        throw new AssertionError("No group contains " + name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
