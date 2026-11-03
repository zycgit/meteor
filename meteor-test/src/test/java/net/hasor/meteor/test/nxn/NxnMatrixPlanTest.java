package net.hasor.meteor.test.nxn;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 从内存运行清单规划工作组，不加载测试类、展开参数或启动任何对端。 */
public class NxnMatrixPlanTest {
    private static final String STANDARD = "net.hasor.realmeteor.matrix.standard.StandardMatrixTest";
    private static final String METHOD   = "savesAndFindsOrder";
    @TempDir
    public               Path   temporary;

    @Test
    public void partitionsOneMatrixClassByExactCaseWithoutDuplicates() throws Exception {
        // 同一个 JUnit 入口包含多条向量，工作组应该按用例而不是按类分摊。
        List<Map<String, Object>> cases = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            cases.add(matrixCase("grpc-" + index, "grpc", "", "java", "python"));
        }
        NxnPlan.Options options = options("groups");

        Map<String, Object> manifest = NxnPlan.prepare(options, runtime(cases, List.of()));

        // 所有向量恰好出现一次，每组保留同一入口及自己参数的精确过滤器。
        List<Map<String, Object>> groups = NxnPlan.objects(manifest.get("groups"));
        assertEquals(3, groups.size());
        Set<String> assigned = new LinkedHashSet<>();
        int count = 0;
        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> current = NxnPlan.objects(group.get("matrixCases"));
            assertFalse(current.isEmpty());
            assertEquals(List.of(STANDARD), group.get("classes"));
            List<String> filters = NxnPlan.strings(group.get("filters"));
            assertEquals(current.size(), filters.size());
            for (Map<String, Object> testCase : current) {
                String identity = (String) testCase.get("caseId");
                assertTrue(assigned.add(identity), "A matrix case belongs to more than one group: " + identity);
                assertTrue(filters.contains(identity.replace('#', '.')));
                count++;
            }
        }
        assertEquals(cases.size(), count);
        assertEquals(new LinkedHashSet<>(caseIdentities(cases)), assigned);
    }

    @Test
    public void rpcCaseAndJUnitMethodFiltersSelectTheirIntersection() throws Exception {
        // 四条向量分别验证协议、case 模式与方法筛选都不能被其它条件绕过。
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(matrixCase("keep-grpc", "grpc", "", "java", "python"));
        cases.add(matrixCase("drop-by-case-grpc", "grpc", "", "java", "python"));
        cases.add(matrixCase("keep-soap", "soap", "1.1", "java", "python"));
        Map<String, Object> otherMethod = matrixCase("keep-grpc-other-method", "grpc", "", "java", "python");
        otherMethod.put("caseId", STANDARD + "#anotherScenario[keep-grpc-other-method]");
        cases.add(otherMethod);
        NxnPlan.Options options = options("groups", "--rpc", "grpc", "--case", "keep-*", //
                "--tests", "StandardMatrixTest." + METHOD + "*");

        Map<String, Object> manifest = NxnPlan.prepare(options, runtime(cases, List.of()));

        // 唯一留下的向量同时满足三个选择器，并保留可直接重跑的完整身份。
        List<Map<String, Object>> selected = NxnPlan.objects(manifest.get("matrixCases"));
        assertEquals(List.of(STANDARD + "#" + METHOD + "[keep-grpc]"), caseIdentities(selected));
        assertEquals(List.of(), manifest.get("legacyClasses"));
        assertEquals(List.of("keep-*"), manifest.get("caseIncludes"));
    }

    @Test
    public void emptyFilterIntersectionFailsBeforeCreatingGroups() {
        // 每个筛选值单独都有匹配，交集为空时不能回退到整个类执行。
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(matrixCase("only-grpc", "grpc", "", "java", "python"));
        cases.add(matrixCase("only-soap", "soap", "1.1", "java", "python"));
        NxnPlan.Options options = options("groups", "--rpc", "soap", "--case", "only-grpc", //
                "--tests", "StandardMatrixTest." + METHOD);
        Map<String, Object> runtime = runtime(cases, List.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> NxnPlan.prepare(options, runtime));

        assertTrue(failure.getMessage().contains("No standard matrix case matches --case"));
        assertFalse(Files.exists(options.output));
    }

    @Test
    public void eachJvmReceivesOnlyItsOwnMatrixPlan() throws Exception {
        // 原始运行清单指向全量计划，每组启动命令必须覆盖为自己的计划文件。
        List<Map<String, Object>> cases = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            cases.add(matrixCase("grpc-" + index, "grpc", "", "python", "java"));
        }
        Map<String, Object> runtime = runtime(cases, List.of());
        NxnPlan.Options options = options("groups", "--groups", "2");

        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);

        // 文件内容、组元数据和 JVM 参数始终引用同一份局部向量集合。
        for (Map<String, Object> group : NxnPlan.objects(manifest.get("groups"))) {
            Path directory = Path.of((String) group.get("outputDirectory"));
            Path matrixFile = Path.of((String) group.get("matrixFile"));
            assertEquals(directory.resolve("matrix.json"), matrixFile);
            Map<String, Object> plan = NxnPlan.readObject(matrixFile);
            assertEquals(1, plan.get("schemaVersion"));
            assertEquals(group.get("matrixCases"), plan.get("cases"));
            assertTrue(NxnPlan.objects(plan.get("cases")).size() < cases.size());

            Map<String, Object> launch = NxnPlan.readObject(Path.of((String) group.get("launchFile")));
            List<String> command = NxnPlan.strings(launch.get("command"));
            assertTrue(command.contains("-Dmeteor.test.matrix.plan=" + matrixFile));
            assertFalse(command.contains("-Dmeteor.test.matrix.plan=/compiled/full-matrix.json"));
            long planOptions = command.stream().filter(argument -> argument.startsWith("-Dmeteor.test.matrix.plan=")).count();
            assertEquals(1, planOptions);
        }
    }

    @Test
    public void addingMatrixCasesPreservesLegacyClassesAndMethodPartitions() throws Exception {
        // 保留普通类、原生注册中心串行类及已有按方向拆分的方法，建立旧计划基线。
        List<String> legacy = new ArrayList<>();
        legacy.add("legacy.FirstTest");
        legacy.add("legacy.SecondTest");
        legacy.add("net.hasor.realmeteor.registry.dubbo.FirstTest");
        legacy.add("net.hasor.realmeteor.registry.dubbo.SecondTest");
        legacy.add("net.hasor.realmeteor.protocol.thrift.python.PythonThriftTypeInteropTest");
        Map<String, Object> baseline = NxnPlan.prepare(options("legacy"), runtime(List.of(), legacy));
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(matrixCase("grpc-a", "grpc", "", "java", "python"));
        cases.add(matrixCase("grpc-b", "grpc", "", "python", "java"));
        cases.add(matrixCase("grpc-c", "grpc", "", "java", "java"));

        Map<String, Object> combined = NxnPlan.prepare(options("combined"), runtime(cases, legacy));

        // 新矩阵只增加精确向量，不移动旧类或重写旧方向过滤器。
        assertEquals(baseline.get("legacyClasses"), combined.get("legacyClasses"));
        assertEquals(baseline.get("methodPartitions"), combined.get("methodPartitions"));
        assertEquals(legacyFilters(baseline), legacyFilters(combined));
        assertEquals(legacy.size(), NxnPlan.strings(combined.get("legacyClasses")).size());
        assertEquals(cases, combined.get("matrixCases"));
    }

    @Test
    public void summaryCountsSelectedMatrixDimensionsAndKeepsLegacyTotalsSeparate() throws Exception {
        // 全量五条向量只选择三条；两个旧类仍需在执行期展开，不能计作两个测试。
        List<Map<String, Object>> cases = new ArrayList<>();
        cases.add(matrixCase("selected-grpc-java", "grpc", "", "java", "java"));
        cases.add(matrixCase("selected-grpc-python", "grpc", "", "python", "java"));
        cases.add(matrixCase("selected-soap-node", "soap", "1.1", "node", "python"));
        cases.add(matrixCase("ignored-soap-java", "soap", "1.1", "java", "java"));
        cases.add(matrixCase("ignored-connect-java", "connect", "1", "java", "node"));
        List<String> legacy = List.of("legacy.FirstTest", "legacy.SecondTest");
        NxnPlan.Options options = options("groups", "--tests", STANDARD + "." + METHOD + "[selected-*]", //
                "--tests", "legacy.*");
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime(cases, legacy));

        // 只打印计划，不执行用例；每个统计维度都必须与选中向量数一致。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            NxnPlan.printSummary(manifest, output);
        }
        String summary = bytes.toString(StandardCharsets.UTF_8);
        Map<String, Integer> protocols = summaryCounts(summary, "rpc");
        Map<String, Integer> languages = summaryCounts(summary, "language");
        Map<String, Integer> apis = summaryCounts(summary, "api");
        assertEquals(Map.of("grpc", 2, "soap/1.1", 1), protocols);
        assertEquals(Map.of("java -> java", 1, "python -> java", 1, "node -> python", 1), languages);
        assertEquals(Map.of("order-v1", 3), apis);
        assertEquals(3, protocols.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(3, languages.values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(3, apis.values().stream().mapToInt(Integer::intValue).sum());
        String discovery = "standard cases 5, selected 3; legacy classes 2 (parameters expand at execution)";
        assertTrue(summary.contains(discovery));
        assertTrue(summary.contains("exact standard case identities before peer startup"));
    }

    @Test
    public void rpcVersionFilterMatchesEitherEndpointVersionInAnInteroperabilityCase() throws Exception {
        // 同一线上契约可由两个 SDK 大版本互调，向量保留两端版本供筛选。
        Map<String, Object> mixed = matrixCase("dubbo-mixed", "dubbo", "", "java", "java");
        mixed.put("protocolVersions", List.of("2", "3"));
        Map<String, Object> current = matrixCase("dubbo-current", "dubbo", "3", "java", "java");
        List<Map<String, Object>> cases = List.of(mixed, current);
        NxnPlan.Options options = options("groups", "--rpc", "dubbo", "--rpc-version", "2");

        Map<String, Object> manifest = NxnPlan.prepare(options, runtime(cases, List.of()));

        // 版本筛选保留跨版本向量，不把整个类或仅版本 3 的向量带入本轮。
        assertEquals(List.of(mixed), manifest.get("matrixCases"));
        assertEquals("2", manifest.get("rpcVersion"));
        Map<String, Object> catalog = NxnPlan.object(manifest.get("rpcCatalog"));
        assertEquals(List.of("2", "3"), catalog.get("dubbo"));
    }

    @Test
    public void caseSelectorAcceptsTheCompleteStableJUnitIdentity() throws Exception {
        // 失败报告中的完整 caseId 可直接重跑，方括号保持参数身份语义。
        Map<String, Object> selected = matrixCase("keep-grpc", "grpc", "", "java", "python");
        Map<String, Object> other = matrixCase("other-grpc", "grpc", "", "java", "python");
        NxnPlan.Options options = options("groups", "--case", (String) selected.get("caseId"));

        Map<String, Object> runtime = runtime(List.of(selected, other), List.of("legacy.OtherTest"));
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);

        // case 重跑只运行该向量，不顺带启动旧类或同协议其它向量。
        assertEquals(List.of(selected), manifest.get("matrixCases"));
        assertEquals(List.of(), manifest.get("legacyClasses"));
        assertEquals(options.cases, manifest.get("caseIncludes"));
    }

    @Test
    public void missingMatrixExecutorClassFailsEvenWhenOnlyLegacyTestsWereSelected() {
        // 能力计划引用了未编译的 API 入口，选择其它旧类不能把这个缺口隐藏掉。
        Map<String, Object> testCase = matrixCase("grpc-missing-class", "grpc", "", "java", "python");
        Map<String, Object> runtime = runtime(List.of(testCase), List.of("legacy.OtherTest"));
        runtime.put("classNames", List.of("legacy.OtherTest"));
        NxnPlan.Options options = options("groups", "--tests", "legacy.*");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> NxnPlan.prepare(options, runtime));

        // 必须在创建工作组之前报告真实缺失的类，不能执行一个缩小后的成功计划。
        assertTrue(failure.getMessage().contains("test class that was not compiled"));
        assertTrue(failure.getMessage().contains(STANDARD));
        assertFalse(Files.exists(options.output));
    }

    @Test
    public void unknownMatrixExecutorMethodFailsEvenWhenOnlyLegacyTestsWereSelected() {
        // 静态测试声明中存在类而不存在拼错的方法，校验必须先于用户筛选。
        Map<String, Object> testCase = matrixCase("grpc-misspelled-method", "grpc", "", "java", "python");
        String misspelled = "savesAndFindsOdrer";
        testCase.put("caseId", STANDARD + "#" + misspelled + "[grpc-misspelled-method]");
        Map<String, Object> runtime = runtime(List.of(testCase), List.of("legacy.OtherTest"));
        runtime.put("testEntries", Map.of(STANDARD, List.of(METHOD)));
        NxnPlan.Options options = options("groups", "--tests", "legacy.*");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> NxnPlan.prepare(options, runtime));

        // 即使本次仅选旧类，错误 API 入口仍明确失败并保留类及方法身份。
        assertTrue(failure.getMessage().contains("unknown test method"));
        assertTrue(failure.getMessage().contains(STANDARD + "#" + misspelled));
        assertFalse(Files.exists(options.output));
    }

    @Test
    public void standardExecutorWithoutMatchingCasesIsNotRunAsALegacyTest() throws Exception {
        // API 已声明执行入口，但当前没有能力交集；该入口不能回落成全类旧测试。
        Map<String, Object> runtime = runtime(List.of(), List.of("legacy.OtherTest"));
        runtime.put("classNames", List.of("legacy.OtherTest", STANDARD));
        runtime.put("testEntries", Map.of(STANDARD, List.of(METHOD)));
        Map<String, String> executor = Map.of("class", STANDARD, "method", METHOD, //
                "api", "order-v1", "scenario", "save-find");
        runtime.put("matrix", Map.of("cases", List.of(), "executors", List.of(executor)));

        Map<String, Object> manifest = NxnPlan.prepare(options("groups"), runtime);

        // 计划仍运行真正的旧类，标准入口既不生成向量，也不占一个工作组。
        assertEquals(List.of(), manifest.get("matrixCases"));
        assertEquals(List.of("legacy.OtherTest"), manifest.get("legacyClasses"));
        assertEquals(List.of("legacy.OtherTest"), manifest.get("selectedClasses"));
        List<Map<String, Object>> groups = NxnPlan.objects(manifest.get("groups"));
        assertEquals(1, groups.size());
        assertEquals(List.of("legacy.OtherTest"), groups.get(0).get("classes"));
    }

    @Test
    public void runtimeDimensionFiltersAndCommandOverridesIntersectRpcAndCaseSelections() throws Exception {
        // 运行配置限定注册发现；命令只覆盖协议维度，不能顺带丢掉发现方式限制。
        Map<String, Object> selected = matrixCase("keep-grpc-zookeeper", "grpc", "", "java", "python");
        selected.put("discovery", "zookeeper");
        Map<String, Object> direct = matrixCase("keep-grpc-direct", "grpc", "", "java", "python");
        Map<String, Object> otherCase = matrixCase("skip-grpc-zookeeper", "grpc", "", "java", "python");
        otherCase.put("discovery", "zookeeper");
        Map<String, Object> otherProtocol = matrixCase("keep-soap-zookeeper", "soap", "1.1", "java", "python");
        otherProtocol.put("discovery", "zookeeper");
        List<Map<String, Object>> cases = List.of(selected, direct, otherCase, otherProtocol);
        Map<String, Object> runtime = runtime(cases, List.of());
        runtime.put("systemProperties", Map.of("meteor.matrix.protocols", "soap", //
                "meteor.matrix.discoveries", "zookeeper"));
        NxnPlan.Options options = options("groups", "-Dmeteor.matrix.protocols=grpc", //
                "--rpc", "grpc", "--case", "keep-*");

        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);

        // 协议覆盖生效，同时按 RPC、case 及继承的注册发现配置收窄到一个向量。
        assertEquals(List.of(selected), manifest.get("matrixCases"));
        assertEquals(List.of("keep-*"), manifest.get("caseIncludes"));
        assertEquals("grpc", manifest.get("rpc"));
    }

    @Test
    public void wildcardDoesNotHideAnUnknownDimensionSelection() {
        // 通配符与拼错的协议同时出现时，不能因先匹配通配符就忽略无效参数。
        Map<String, Object> testCase = matrixCase("grpc-only", "grpc", "", "java", "python");
        Map<String, Object> runtime = runtime(List.of(testCase), List.of());
        NxnPlan.Options options = options("groups", "-Dmeteor.matrix.protocols=*,grpcc");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> NxnPlan.prepare(options, runtime));

        // 错误包含维度与原始 token，且在生成任何工作组之前终止。
        assertTrue(failure.getMessage().contains("meteor.matrix.protocols"));
        assertTrue(failure.getMessage().contains("grpcc"));
        assertFalse(Files.exists(options.output));
    }

    @Test
    public void serialMatrixCasesStayInOneGroupWhileOrdinaryCasesRemainParallel() throws Exception {
        // 三个独占同一资源的向量共用串行组，其余向量仍可使用其它并发工作组。
        List<Map<String, Object>> cases = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Map<String, Object> serial = matrixCase("native-" + index, "dubbo", "3", "java", "java");
            serial.put("serialGroup", "native-registry");
            serial.put("discovery", "dubbo");
            cases.add(serial);
        }
        for (int index = 0; index < 6; index++) {
            cases.add(matrixCase("ordinary-" + index, "grpc", "", "java", "python"));
        }

        Map<String, Object> manifest = NxnPlan.prepare(options("groups"), runtime(cases, List.of()));

        // 同串行组不跨 JVM 排队，同时不能把所有普通向量也串行化。
        Set<String> serialGroups = new LinkedHashSet<>();
        Set<String> ordinaryGroups = new LinkedHashSet<>();
        int serialCount = 0;
        int ordinaryCount = 0;
        for (Map<String, Object> group : NxnPlan.objects(manifest.get("groups"))) {
            String groupId = (String) group.get("id");
            for (Map<String, Object> testCase : NxnPlan.objects(group.get("matrixCases"))) {
                if (testCase.containsKey("serialGroup")) {
                    serialGroups.add(groupId);
                    serialCount++;
                } else {
                    ordinaryGroups.add(groupId);
                    ordinaryCount++;
                }
            }
        }
        assertEquals(3, serialCount);
        assertEquals(1, serialGroups.size());
        assertEquals(6, ordinaryCount);
        assertTrue(ordinaryGroups.size() > 1);
    }

    @Test
    public void matrixSerialGroupSharesTheExistingNativeRegistryLane() throws Exception {
        // 旧原生注册场景和新向量借用同一独占资源，必须归入已有的串行 JVM。
        List<Map<String, Object>> serialCases = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            Map<String, Object> serial = matrixCase("native-" + index, "dubbo", "3", "java", "java");
            serial.put("serialGroup", "native-registry");
            serial.put("discovery", "dubbo");
            serialCases.add(serial);
        }
        List<Map<String, Object>> cases = new ArrayList<>(serialCases);
        cases.add(matrixCase("ordinary-grpc", "grpc", "", "java", "python"));
        List<String> legacy = List.of(NxnPlan.NATIVE_REGISTRY_EXAMPLE, "legacy.OtherTest");

        Map<String, Object> manifest = NxnPlan.prepare(options("groups"), runtime(cases, legacy));

        // 全部新串行向量属于持有旧场景的唯一组，其它工作组不能再包含这些向量。
        List<Map<String, Object>> groups = NxnPlan.objects(manifest.get("groups"));
        List<Map<String, Object>> nativeGroups = groups.stream().filter(group -> NxnPlan.strings(group.get("classes")).contains(NxnPlan.NATIVE_REGISTRY_EXAMPLE)).toList();
        assertEquals(1, nativeGroups.size());
        List<Map<String, Object>> assigned = NxnPlan.objects(nativeGroups.get(0).get("matrixCases"));
        assertTrue(caseIdentities(assigned).containsAll(caseIdentities(serialCases)));
        int serialCount = 0;
        for (Map<String, Object> group : groups) {
            for (Map<String, Object> testCase : NxnPlan.objects(group.get("matrixCases"))) {
                if (testCase.containsKey("serialGroup")) {
                    assertTrue(NxnPlan.strings(group.get("classes")).contains(NxnPlan.NATIVE_REGISTRY_EXAMPLE));
                    serialCount++;
                }
            }
        }
        assertEquals(serialCases.size(), serialCount);
    }

    private NxnPlan.Options options(String directory, String... selection) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--run-id");
        arguments.add("matrix-plan-test");
        arguments.add("--groups");
        arguments.add("3");
        arguments.add("--output");
        arguments.add(this.temporary.resolve(directory).toString());
        arguments.add("--weights");
        arguments.add(this.temporary.resolve("missing-weights.json").toString());
        arguments.add("--workspace");
        arguments.add(this.temporary.toString());
        arguments.addAll(List.of(selection));
        return NxnPlan.parseArguments(arguments.toArray(String[]::new));
    }

    private static Map<String, Object> matrixCase(String id, String protocol, String version, String consumerLanguage, String providerLanguage) {
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("id", id);
        testCase.put("caseId", STANDARD + "#" + METHOD + "[" + id + "]");
        testCase.put("api", "order-v1");
        testCase.put("scenario", "save-find");
        testCase.put("protocol", protocol);
        testCase.put("protocolVersion", version);
        testCase.put("protocolVersions", List.of(version));
        testCase.put("wire", "fixture-" + protocol);
        testCase.put("discovery", "direct");
        testCase.put("consumer", Map.of("id", id + "-consumer", "language", consumerLanguage));
        testCase.put("provider", Map.of("id", id + "-provider", "language", providerLanguage));
        testCase.put("request", Map.of("id", 1001, "description", "nxn-order", "attachment", "AAEAf/8="));
        return testCase;
    }

    private static Map<String, Object> runtime(List<Map<String, Object>> cases, List<String> legacy) {
        List<String> classes = new ArrayList<>(legacy);
        Map<String, Object> methods = new LinkedHashMap<>();
        for (Map<String, Object> testCase : cases) {
            String identity = (String) testCase.get("caseId");
            String method = identity.substring(identity.indexOf('#') + 1, identity.indexOf('['));
            @SuppressWarnings("unchecked") List<Map<String, Object>> declarations = (List<Map<String, Object>>) methods.computeIfAbsent(method, ignored -> new ArrayList<>());
            for (String version : NxnPlan.strings(testCase.get("protocolVersions"))) {
                declarations.add(Map.of("name", testCase.get("protocol"), "version", version, "cases", testCase.get("id")));
            }
        }
        if (!cases.isEmpty()) {
            classes.add(STANDARD);
        }
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("classpath", List.of("/fixture/classes"));
        runtime.put("classNames", classes);
        runtime.put("rpcCases", Map.of(STANDARD, methods));
        runtime.put("matrix", Map.of("cases", cases));
        runtime.put("systemProperties", Map.of("meteor.test.matrix.plan", "/compiled/full-matrix.json"));
        return runtime;
    }

    private static List<String> caseIdentities(List<Map<String, Object>> cases) {
        return cases.stream().map(testCase -> (String) testCase.get("caseId")).toList();
    }

    private static Map<String, List<String>> legacyFilters(Map<String, Object> manifest) {
        Map<String, List<String>> filters = new TreeMap<>();
        for (Map<String, Object> group : NxnPlan.objects(manifest.get("groups"))) {
            List<String> legacy = NxnPlan.strings(group.get("filters")).stream().filter(filter -> !filter.startsWith(STANDARD + ".")).toList();
            filters.put((String) group.get("id"), legacy);
        }
        return filters;
    }

    private static Map<String, Integer> summaryCounts(String summary, String dimension) {
        Map<String, Integer> counts = new TreeMap<>();
        String prefix = "[discovery][" + dimension + "] ";
        for (String line : summary.lines().toList()) {
            if (line.startsWith(prefix)) {
                int separator = line.lastIndexOf(": ");
                counts.put(line.substring(prefix.length(), separator), Integer.parseInt(line.substring(separator + 2)));
            }
        }
        return counts;
    }
}
