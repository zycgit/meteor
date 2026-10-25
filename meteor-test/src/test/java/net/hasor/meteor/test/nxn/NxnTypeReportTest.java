package net.hasor.meteor.test.nxn;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 类型矩阵只接受同一轮原始证据，并区分测试通过与类型支持。 */
public class NxnTypeReportTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void javaBasicDisplayPreservesBoxedType() {
        // 参数展示名同时保留包装类型和向量，不能退化成 Java 原始类型。
        Map<String, Object> testcase = scenario("net.hasor.realmeteor.matrix.types.JavaBasicTypeMatrixTest", "echo", //
                "java-basic/self/rsf-java/direct/type=boxed-char/vector=unicode-cjk");
        Map<String, Object> dimensions = NxnTypeReport.parseDimensions(testcase, Map.of());

        // 读取实际声明的语言、方向、类型和协议配置。
        assertEquals(List.of("java", "self", "boxed-char", "rsf-java"), //
                List.of(dimensions.get("language"), dimensions.get("role"), //
                        dimensions.get("type"), dimensions.get("profile")));
    }

    @Test
    public void javaTimeIsSeparateFromLegacyDate() {
        // Java 8 时间向量拥有独立章节，不与 Date 混合。
        Map<String, Object> testcase = scenario("net.hasor.realmeteor.matrix.types.JavaTimeTypeMatrixTest", "echo", //
                "java-time/self/grpc-protobuf/direct/type=zoned-date-time/vector=utc-region-identity");
        assertEquals("java-time", NxnTypeReport.parseDimensions(testcase, Map.of()).get("section"));
    }

    @Test
    public void pythonProviderRoleIsMeteorProvider() {
        // 用例方法名以 Meteor 的角色解释调用方向。
        assertEquals("provider", NxnTypeReport.parseDimensions(exampleCase(), Map.of()).get("role"));
    }

    @Test
    public void pythonConsumerRoleIsMeteorConsumer() {
        // Python 提供服务时，Meteor 在矩阵中记为消费者。
        Map<String, Object> testcase = scenario("x.PythonGrpcTypeInteropTest", //
                "meteorConsumerPreservesPythonNativeType", "types/python/grpc-protobuf/direct/int64/int64-maximum");
        assertEquals("consumer", NxnTypeReport.parseDimensions(testcase, Map.of()).get("role"));
    }

    @Test
    public void goUppercaseDisplayHasCanonicalLanguage() {
        // 旧参数展示中的 Go 和 grpc 统一成可比较的维度。
        Map<String, Object> testcase = scenario("x.GoProtoTypeMatrixTest", //
                "providerAcceptsOneNativeGoTypeVector", "types/Go/grpc/direct/uint64/uint64-maximum");
        Map<String, Object> dimensions = NxnTypeReport.parseDimensions(testcase, Map.of());
        assertEquals(List.of("go", "grpc-protobuf", "Protobuf"), //
                List.of(dimensions.get("language"), dimensions.get("profile"), dimensions.get("encoding")));
    }

    @Test
    public void nodeConnectDisplayProvidesItsRole() {
        // 参数展示中明确的调用方向可补充通用方法名。
        Map<String, Object> testcase = scenario("x.NodeConnectTypeScenarioTest", //
                "preservesDeclaredTypeAcrossOfficialNodeSdk", //
                "types/node/connect-Json/direct/binary/binary-empty/meteor-consumer");
        assertEquals("consumer", NxnTypeReport.parseDimensions(testcase, Map.of()).get("role"));
    }

    @Test
    public void browserTextIsProtobufNotJson() {
        // gRPC-Web text 只改变帧表示，不改变 Protobuf 编码。
        Map<String, Object> testcase = scenario("x.BrowserGrpcWebTypeScenarioTest", //
                "officialBrowserPreservesDeclaredType", //
                "types/browser/grpcweb-text/direct/binary/binary-empty/meteor-provider");
        Map<String, Object> dimensions = NxnTypeReport.parseDimensions(testcase, Map.of());
        assertEquals(List.of("grpc-web", "Protobuf", "provider"), //
                List.of(dimensions.get("protocol"), dimensions.get("encoding"), dimensions.get("role")));
    }

    @Test
    public void displayCannotContradictDirectionMethod() {
        // 展示参数不能将已确定的 Meteor 提供者方向改成消费者。
        Map<String, Object> testcase = scenario("x.BrowserGrpcWebTypeScenarioTest", //
                "officialBrowserPreservesDeclaredType", //
                "types/browser/grpcweb-text/direct/binary/binary-empty/meteor-consumer");
        assertInvalid("role mismatch", () -> NxnTypeReport.parseDimensions(testcase, Map.of()));
    }

    @Test
    public void cCatalogPreservesNativeLongMapping() {
        // C 类型目录区分语言类型和 XDR 线类型。
        Map<String, Object> testcase = scenario("x.OncRpcNativeTypeMatrixTest", //
                "meteorProviderAcceptsNativeType", "udp/i64-long-min");
        Map<String, Map<String, Object>> catalog = Map.of("i64-long-min", //
                values("type", "long", "nativeType", "C long (LP64)", "wireType", "XDR hyper"));
        Map<String, Object> dimensions = NxnTypeReport.parseDimensions(testcase, catalog);
        assertEquals(List.of("long", "C long (LP64)", "oncrpc-xdr-udp"), //
                List.of(dimensions.get("type"), dimensions.get("nativeType"), dimensions.get("profile")));
    }

    @Test
    public void cVectorWithoutCatalogIsRejected() {
        // 未提供 C 向量目录时不能猜测原生类型。
        Map<String, Object> testcase = scenario("x.OncRpcNativeTypeMatrixTest", //
                "meteorProviderAcceptsNativeType", "tcp/i64-long-min");
        assertInvalid("missing from", () -> NxnTypeReport.parseDimensions(testcase, Map.of()));
    }

    @Test
    public void initializationErrorIsNotAnIgnoredTypeCase() {
        // 类型测试类初始化失败必须报告，不能当作无关用例忽略。
        Map<String, Object> testcase = values("caseId", "x.JavaBasicTypeMatrixTest#initializationError", //
                "className", "x.JavaBasicTypeMatrixTest", "scenario", "initializationError");
        assertInvalid("initialization", () -> NxnTypeReport.parseDimensions(testcase, Map.of()));
    }

    @Test
    public void unreviewedGreenCaseStaysUnverified() {
        // 通过的测试只有经审查后才能成为支持结论。
        Map<String, Object> actual = NxnTypeReport.applyExpectation(supportEntry("passed"), List.of());
        assertEquals("unverified", actual.get("support"));
    }

    @Test
    public void reviewedRoundTripCanEstablishSupport() {
        // 已审查的保真往返测试可以建立支持结论。
        Map<String, Object> actual = NxnTypeReport.applyExpectation(supportEntry("passed"), List.of(rule()));
        assertEquals("supported", actual.get("support"));
    }

    @Test
    public void passedRejectionRemainsUnsupported() {
        // 精确拒绝测试通过，表示正确拒绝而非支持该类型。
        Map<String, Object> actual = NxnTypeReport.applyExpectation(supportEntry("passed"), //
                List.of(rule("unsupported", "rejection")));
        assertEquals(List.of("unsupported", "passed"), List.of(actual.get("support"), actual.get("testStatus")));
    }

    @Test
    public void passedNormalizationRemainsLimited() {
        // 规范化后往返仍属于有限支持。
        Map<String, Object> actual = NxnTypeReport.applyExpectation(supportEntry("passed"), //
                List.of(rule("limited", "normalization")));
        assertEquals("limited", actual.get("support"));
    }

    @Test
    public void failureCannotBeReclassifiedAsUnsupported() {
        // 失败不能被拒绝期望转写成已验证的不支持。
        Map<String, Object> actual = NxnTypeReport.applyExpectation(supportEntry("failed"), //
                List.of(rule("unsupported", "rejection")));
        assertEquals("failed", actual.get("support"));
    }

    @Test
    public void declaredRejectionRejectsGlobalRoundTripRule() {
        // 明确的拒绝向量不能套用全局保真往返规则。
        Map<String, Object> entry = supportEntry("passed");
        entry.put("declaredObservation", "rejection");
        assertInvalid("Rejection testcase", () -> NxnTypeReport.applyExpectation(entry, List.of(rule())));
    }

    @Test
    public void normalizationCannotBeClaimedAsLossless() {
        // 规范化行为与无损支持声明矛盾时直接拒绝。
        List<Map<String, Object>> rules = List.of(rule("supported", "normalization"));
        assertInvalid("cannot establish", () -> NxnTypeReport.applyExpectation(supportEntry("passed"), rules));
    }

    @Test
    public void ambiguousEqualPriorityRulesAreRejected() {
        // 两条同优先级规则匹配同一用例时不任意挑选。
        Map<String, Object> second = rule();
        second.put("id", "another-review");
        List<Map<String, Object>> rules = List.of(rule(), second);
        assertInvalid("Ambiguous", () -> NxnTypeReport.applyExpectation(supportEntry("passed"), rules));
    }

    @Test
    public void explicitHigherPriorityRuleWins() {
        // 更具体的显式优先级覆盖一般规则。
        Map<String, Object> second = rule("limited", "normalization");
        second.put("id", "specific-review");
        second.put("priority", 10);
        Map<String, Object> actual = NxnTypeReport.applyExpectation(supportEntry("passed"), List.of(rule(), second));
        assertEquals("limited", actual.get("support"));
    }

    @Test
    public void exactCaseIdWithParameterBracketsMatchesItself() {
        // 参数化用例的中括号属于身份文本。
        Map<String, Object> entry = supportEntry("passed");
        entry.put("caseId", exampleCase().get("caseId"));
        Map<String, Object> exact = rule();
        exact.put("select", values("caseId", entry.get("caseId")));
        assertEquals("supported", NxnTypeReport.applyExpectation(entry, List.of(exact)).get("support"));
    }

    @Test
    public void caseIdWildcardKeepsParameterBracketsLiteral() {
        // 通配符可跨部分身份文本，但不能把参数中括号当成字符组。
        Map<String, Object> entry = supportEntry("passed");
        entry.put("caseId", exampleCase().get("caseId"));
        Map<String, Object> exact = rule();
        exact.put("select", values("caseId", "*#meteorProvider*[types/python/*/int64/int64-maximum]"));
        assertEquals("supported", NxnTypeReport.applyExpectation(entry, List.of(exact)).get("support"));
    }

    @Test
    public void partialVectorSetIsUnverified() {
        // 缺失的向量不能借邻近向量通过来建立支持。
        List<Map<String, Object>> entries = List.of( // 同一个类型的两条执行证据。
                values("support", "supported", "testStatus", "passed"), //
                values("support", "unverified", "testStatus", "missing"));
        assertEquals("unverified", NxnTypeReport.aggregate(entries).get("support"));
    }

    @Test
    public void mixedValidAndRejectedRangesAreLimited() {
        // 部分可用、部分拒绝的值域总结为有限支持。
        List<Map<String, Object>> entries = List.of( // 两段值域归并成一个类型结论。
                values("support", "supported", "testStatus", "passed"), //
                values("support", "unsupported", "testStatus", "passed"));
        assertEquals("limited", NxnTypeReport.aggregate(entries).get("support"));
    }

    @Test
    public void sameRunXmlAndSelectedCasesAreAccepted() throws Exception {
        // 选中用例和 XML 均来自同一个归档目录。
        Path run = createRun();
        assertEquals(1, NxnTypeReport.object(NxnTypeReport.readRun(run).get("cases")).size());
    }

    @Test
    public void duplicateSelectedIdIsRejected() throws Exception {
        // 同一轮同一用例出现两次不能增加覆盖数。
        Path run = createRun();
        Map<String, Object> testcase = executedCase();
        writeRun(run, List.of(testcase, testcase));
        assertInvalid("Duplicate caseId", () -> NxnTypeReport.readRun(run));
    }

    @Test
    public void anotherRunConfigurationIsRejected() throws Exception {
        // 配置来自另一轮时不能与本轮结果拼接。
        Path run = createRun();
        writeJson(run.resolve("configuration.json"), values("runId", "run-B"));
        assertInvalid("runId mismatch", () -> NxnTypeReport.readRun(run));
    }

    @Test
    public void cachedReportIsRejected() throws Exception {
        // 标记复用的结果不能成为本轮新验证证据。
        Path run = createRun();
        changeSummary(run, "reusedResults", true);
        assertInvalid("Cached", () -> NxnTypeReport.readRun(run));
    }

    @Test
    public void startupFailureDoesNotAcceptOldXml() throws Exception {
        // 启动失败时即使目录里存在旧 XML 也必须拒绝。
        Path run = createRun();
        changeSummary(run, "testTaskStarted", false);
        assertInvalid("did not start", () -> NxnTypeReport.readRun(run));
    }

    @Test
    public void summaryCountsMustMatchSelectedCases() throws Exception {
        // 汇总通过数必须与实际选择结果一致。
        Path run = createRun();
        changeSummary(run, "passed", 2);
        assertInvalid("summary counts", () -> NxnTypeReport.readRun(run));
    }

    @Test
    public void selectedAndXmlStatusMustMatch() throws Exception {
        // XML 失败不能被选中列表中的通过状态覆盖。
        Path run = createRun();
        Path xml = run.resolve("test-results/TEST-fixture.xml");
        String content = Files.readString(xml).replace("</testcase>", "<failure>failure</failure></testcase>");
        Files.writeString(xml, content);
        assertInvalid("status mismatch", () -> NxnTypeReport.readRun(run));
    }

    @Test
    public void extraXmlCaseIsNotSilentlyImported() throws Exception {
        // 旧 XML 中额外的用例不能悄悄并入当前轮次。
        Path run = createRun();
        Path xml = run.resolve("test-results/TEST-fixture.xml");
        String staleCase = "<testcase classname=\"old.Case\" name=\"oldTest\"></testcase>";
        Files.writeString(xml, Files.readString(xml).replace("</testsuite>", staleCase + "</testsuite>"));
        assertInvalid("identity mismatch", () -> NxnTypeReport.readRun(run));
    }

    @Test
    public void plannedMissingVectorIsRetained() throws Exception {
        // 计划中尚未执行的精确向量继续显示为 missing。
        Map<String, Object> run = NxnTypeReport.readRun(createRun());
        Map<String, Object> planned = NxnTypeReport.parseDimensions(executedCase(), Map.of());
        planned.put("caseId", "future.Case#missing");
        Map<String, Object> expectations = values("plannedCases", List.of(planned), "rules", List.of(rule()));
        List<Map<String, Object>> details = NxnTypeReport.buildDetails(run, expectations, Map.of());

        // 已执行和未执行证据同时保留。
        List<String> statuses = new ArrayList<>();
        for (Map<String, Object> detail : details) {
            statuses.add(NxnTypeReport.string(detail, "testStatus"));
        }
        statuses.sort(String::compareTo);
        assertEquals(List.of("missing", "passed"), statuses);
    }

    @Test
    public void oldExecutionFieldsCannotTurnMissingPlanIntoSupport() throws Exception {
        // 计划声明只描述维度，禁止夹带历史执行状态。
        Map<String, Object> run = NxnTypeReport.readRun(createRun());
        Map<String, Object> planned = NxnTypeReport.parseDimensions(executedCase(), Map.of());
        planned.put("caseId", "future.Case#missing");
        planned.put("testStatus", "passed");
        planned.put("runId", "WRONG-RUN");
        Map<String, Object> expectations = values("plannedCases", List.of(planned), "rules", List.of(rule()));
        assertInvalid("execution fields", () -> NxnTypeReport.buildDetails(run, expectations, Map.of()));
    }

    @Test
    public void explicitMappingCannotReplaceActualClassIdentity() throws Exception {
        // 显式维度映射不能改变 XML 所确定的真实类名。
        Map<String, Object> run = NxnTypeReport.readRun(createRun());
        Map<String, Object> mapping = values("caseId", executedCase().get("caseId"), "className", "old.OtherClass");
        Map<String, Object> expectations = values("caseMappings", List.of(mapping), "rules", List.of(rule()));
        assertInvalid("actual identity", () -> NxnTypeReport.buildDetails(run, expectations, Map.of()));
    }

    @Test
    public void minimalPlannedCaseGetsConsistentProfileEncoding() throws Exception {
        // 只给最小计划维度时，编码从 profile 补齐。
        Map<String, Object> run = NxnTypeReport.readRun(createRun());
        Map<String, Object> planned = values("caseId", "future.Case#missing", "language", "python", //
                "protocol", "grpc", "profile", "grpc-protobuf", "role", "provider", //
                "type", "int64", "vector", "minimum");
        Map<String, Object> expectations = values("plannedCases", List.of(planned), "rules", List.of(rule()));
        List<Map<String, Object>> details = NxnTypeReport.buildDetails(run, expectations, Map.of());
        List<List<String>> profiles = NxnTypeReport.profilesFor(details, Map.of(), "python", "provider");
        assertEquals(List.of(List.of("grpc", "grpc-protobuf", "Protobuf")), profiles);
    }

    @Test
    public void missingJavaTimeCaseDefaultsToTimeSection() throws Exception {
        // 未执行的 Java 时间用例仍落到 Java 8 时间章节。
        Map<String, Object> run = NxnTypeReport.readRun(createRun());
        Map<String, Object> planned = values("caseId", "future.JavaTimeTypeMatrixTest#missing", "language", "java", //
                "protocol", "rsf", "profile", "rsf-java", "role", "self", //
                "type", "instant", "vector", "before-epoch");
        Map<String, Object> expectations = values("plannedCases", List.of(planned), "rules", List.of());
        List<Map<String, Object>> details = NxnTypeReport.buildDetails(run, expectations, Map.of());

        // 只检查这个未执行向量的章节归属。
        Map<String, Object> missing = details.stream() //
                .filter(entry -> StringUtils.equals(NxnTypeReport.string(entry, "testStatus"), "missing")) //
                .findFirst().orElseThrow();
        assertEquals("java-time", missing.get("section"));
    }

    @Test
    public void unexecutedAxesProfileIsRetainedInJavaTimeTable() throws Exception {
        // 计划列出的 Java profile 即使尚未执行也保留在时间表中。
        Map<String, Object> report = markdownReport(List.of(), List.of("java"));
        report.put("typeCaseCounts", Map.of());
        report.put("axes", values("languages", List.of("java"), "protocols", List.of("rsf"), //
                "profiles", List.of(values("language", "java", "role", "self", "protocol", "rsf", //
                        "profile", "rsf-java", "encoding", "Java"))));
        String rendered = NxnTypeReport.renderMarkdown(report, Path.of("a.json"), null);
        String timeTable = rendered.split("#### Java 8 时间", 2)[1];
        assertTrue(timeTable.contains("rsf-java / Java"));
        assertTrue(timeTable.contains("未验证"));
    }

    @Test
    public void generateWritesMarkdownAndCompleteCaseDetails() throws Exception {
        // 从本轮归档和已审查规则生成 Markdown 及完整审计明细。
        Path run = createRun();
        Path expectations = run.resolve("expectations.json");
        writeJson(expectations, reviewedExpectations());
        Path output = run.resolve("generated/support.md");
        Map<String, Object> report = NxnTypeReport.generate(run, expectations, null, output, null);

        // Markdown 保留调用方向，明细使用 JSON，不创建 CSV。
        assertEquals("supported", NxnTypeReport.objects(report.get("cases")).get(0).get("support"));
        assertTrue(Files.readString(output).contains("Meteor 作为消费者"));
        assertTrue(Files.isRegularFile(output.resolveSibling("support.json")));
        assertFalse(Files.exists(output.resolveSibling("support.csv")));
    }

    @Test
    public void unusedTypeColumnsAreNotBorrowedFromAnotherLanguage() throws Exception {
        // 表格只呈现当前语言实际声明的类型列。
        String rendered = render(List.of(markdownEntry()));
        String header = rendered.lines().filter(line -> line.startsWith("| 协议 / 编码")).findFirst().orElseThrow();
        assertEquals("| 协议 / 编码 | `int64` |", header);
    }

    @Test
    public void protocolWithoutVectorsIsSummarizedWithoutMatrixRows() throws Exception {
        // 没有向量的协议用一句说明呈现，不制造空矩阵行。
        String rendered = render(List.of(markdownEntry()));
        assertTrue(rendered.contains("未验证协议：soap。"));
        assertFalse(rendered.contains("未配置 SDK/profile"));
    }

    @Test
    public void missingDirectionHasOneSentenceInsteadOfEmptyMatrices() throws Exception {
        // 当前轮没有消费者方向时不输出空表格。
        String rendered = render(List.of(markdownEntry()));
        String consumer = rendered.split("### Meteor 作为消费者", 2)[1].split("## 映射与限制", 2)[0];
        assertTrue(consumer.contains("未验证：本轮没有该调用方向的类型用例。"));
        assertFalse(consumer.contains("| 协议"));
    }

    @Test
    public void rustIsExplicitlyUnverified() throws Exception {
        // 计划语言 Rust 没有执行证据时明确标为未验证。
        String rendered = render(List.of(markdownEntry()), List.of("python", "rust"));
        assertTrue(rendered.contains("## Rust\n\n未验证"));
    }

    @Test
    public void cellReportsSupportWithoutPerVectorPassCounts() throws Exception {
        // 单元格呈现支持结论，不用通过比例冒充支持程度。
        String rendered = render(List.of(markdownEntry()));
        assertTrue(rendered.contains("| grpc-protobuf / Protobuf | 支持 |"));
        assertFalse(rendered.contains("(1/1)"));
    }

    @Test
    public void rejectionIsNotPresentedAsLosslessSupport() throws Exception {
        // 拒绝行为通过也应在表格中显示不支持。
        String rendered = render(List.of(markdownEntry("support", "unsupported", "outcome", "rejection")));
        assertTrue(rendered.contains("| grpc-protobuf / Protobuf | 不支持 |"));
    }

    @Test
    public void limitationIsSharedBetweenDirections() throws Exception {
        // 相同限制说明只写一次，避免提供者和消费者章节重复。
        List<Map<String, Object>> entries = List.of( // 两个方向共享同一边界说明。
                markdownEntry("support", "limited", "reason", "One numeric boundary."), //
                markdownEntry("role", "consumer", "support", "limited", "reason", "One numeric boundary."));
        String rendered = render(entries);
        assertEquals(1, occurrences(rendered, "One numeric boundary."));
    }

    @Test
    public void identicalDirectionMatricesAreShownOnceWithBothRoles() throws Exception {
        // 双向精确维度和结论相同才合并展示。
        String rendered = render(List.of(markdownEntry(), markdownEntry("role", "consumer")));
        assertTrue(rendered.contains("### 提供者与消费者（双向验证）"));
        assertEquals(1, occurrences(rendered, "| grpc-protobuf / Protobuf | 支持 |"));
    }

    @Test
    public void differentSupportConclusionsKeepDirectionTablesSeparate() throws Exception {
        // 双向支持结论不一致时保留两个方向的表格。
        String rendered = render(List.of(markdownEntry(), markdownEntry("role", "consumer", "support", "limited")));
        assertTrue(rendered.contains("### Meteor 作为提供者"));
        assertTrue(rendered.contains("### Meteor 作为消费者"));
        assertFalse(rendered.contains("双向验证"));
    }

    @Test
    public void missingTypeInOneDirectionPreventsMerging() {
        // 某个方向缺少一种类型时不能合并表格。
        List<Map<String, Object>> entries = List.of( // 额外类型只有提供者方向。
                markdownEntry(), markdownEntry("role", "consumer"), markdownEntry("type", "float64"));
        assertFalse(NxnTypeReport.matchingDirections(entries, Map.of(), "python"));
    }

    @Test
    public void missingVectorInOneDirectionPreventsMerging() {
        // 相同类型的不同边界向量也不能互相代替。
        List<Map<String, Object>> entries = List.of( // 两个方向使用不同值域向量。
                markdownEntry("vector", "minimum"), markdownEntry("role", "consumer", "vector", "maximum"));
        assertFalse(NxnTypeReport.matchingDirections(entries, Map.of(), "python"));
    }

    @Test
    public void unexecutedDirectionPreventsMerging() {
        // 尚未执行的消费者方向不能与已通过方向合并。
        List<Map<String, Object>> entries = List.of( // 消费者缺少本轮执行证据。
                markdownEntry(), markdownEntry("role", "consumer", "testStatus", "missing", "support", "unverified"));
        assertFalse(NxnTypeReport.matchingDirections(entries, Map.of(), "python"));
    }

    @Test
    public void extraPlannedProfilePreventsDirectionMerging() {
        // 消费者还有待执行协议配置时保留方向差异。
        List<Map<String, Object>> entries = List.of(markdownEntry(), markdownEntry("role", "consumer"));
        Map<String, Object> axes = values("profiles", List.of(values("language", "python", "role", "consumer", //
                "protocol", "soap", "profile", "soap")));
        assertFalse(NxnTypeReport.matchingDirections(entries, axes, "python"));
    }

    @Test
    public void distinctJaysonLexemesShareOneReadableLimitation() throws Exception {
        // 不同数字词法共享同一种 Jayson 精度限制说明。
        Map<String, Object> positive = markdownEntry("language", "node", "protocol", "jsonrpc", //
                "profile", "jsonrpc", "support", "limited", //
                "reason", "固定 Jayson 数字词法 {\"int64Value\": \"9007199254740992\"} 在范围内");
        Map<String, Object> negative = markdownEntry("language", "node", "protocol", "jsonrpc", //
                "profile", "jsonrpc", "support", "limited", //
                "reason", "固定 Jayson 数字词法 {\"int64Value\": \"-9223372036854776000\"} 超出范围");
        String rendered = render(List.of(positive, negative), List.of("node"));
        assertEquals(1, occurrences(rendered, "Jayson 将整数解析为 Number"));
        assertFalse(rendered.contains("9007199254740992"));
    }

    @Test
    public void scopeUsesReadableFieldsWithoutConfigurationJsonBlock() throws Exception {
        // 验证范围使用可读字段，不输出整段内部配置。
        String rendered = render(List.of(markdownEntry()));
        assertTrue(rendered.contains("| 执行环境 |"));
        assertFalse(rendered.contains("运行编号"));
        assertFalse(rendered.contains("```json"));
    }

    @Test
    public void unfilteredScopeIsRenderedWithoutEmptyJsonValues() throws Exception {
        // 无筛选条件直接展示未筛选，避免空 JSON 文案。
        String rendered = render(List.of(markdownEntry()));
        assertTrue(rendered.contains("| 测试筛选 | 未筛选 |"));
        assertTrue(rendered.contains("| 矩阵筛选 | 未筛选 |"));
    }

    @Test
    public void javaGenericTypeNamesAreInlineCode() {
        // 泛型类型名使用行内代码，避免尖括号被 Markdown 当成标签。
        String table = NxnTypeReport.matrixTable(List.of(markdownEntry("type", "map-string-int")), //
                List.of(List.of("grpc", "grpc-protobuf", "Protobuf")), List.of("map-string-int"));
        assertTrue(table.contains("`Map<String,Integer>`"));
    }

    @Test
    public void matrixCellsKeepEncodingsSeparate() {
        // 同协议配置中的不同编码必须各有独立支持结论。
        List<Map<String, Object>> entries = List.of( // Protobuf 可用，JSON 在该向量中拒绝。
                markdownEntry(), markdownEntry("encoding", "JSON", "support", "unsupported"));
        List<List<String>> profiles = List.of( // 按明确编码分别生成行。
                List.of("grpc", "grpc-protobuf", "Protobuf"), List.of("grpc", "grpc-protobuf", "JSON"));
        String table = NxnTypeReport.matrixTable(entries, profiles, List.of("int64"));
        assertTrue(table.contains("| grpc-protobuf / Protobuf | 支持 |"));
        assertTrue(table.contains("| grpc-protobuf / JSON | 不支持 |"));
    }

    @Test
    public void matrixCellsKeepTypeConclusionsSeparate() {
        // 类型列不能借同一协议中其它类型的结论。
        List<Map<String, Object>> entries = List.of( // 布尔支持，整数拒绝。
                markdownEntry("type", "boolean"), markdownEntry("support", "unsupported"));
        String table = NxnTypeReport.matrixTable(entries, List.of(List.of("grpc", "grpc-protobuf", "Protobuf")), //
                List.of("boolean", "int64"));
        assertTrue(table.contains("| grpc-protobuf / Protobuf | 支持 | 不支持 |"));
    }

    @Test
    public void tableTypeGroupsPreserveEveryDeclaredType() {
        // 已知和未来声明类型均应完整保留在类型分组中。
        Set<String> names = Set.of("boolean", "int8", "int64", "char", "text", "bytes", "optional", "future-type");
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : names) {
            entries.add(markdownEntry("type", name));
        }
        Map<String, List<String>> groups = NxnTypeReport.typeGroups(entries, "python");

        // 分组之后不能遗漏或扩充任何类型。
        Set<String> grouped = new LinkedHashSet<>();
        for (List<String> group : groups.values()) {
            grouped.addAll(group);
        }
        assertEquals(names, grouped);
    }

    @Test
    public void cliHelpDoesNotRequireRunEvidence() {
        // 帮助命令在无归档输入时也能成功输出用法。
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = runCli(output, errors, "--help");
        assertEquals(0, status);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("--run-directory"));
        assertEquals("", errors.toString(StandardCharsets.UTF_8));
    }

    @Test
    public void cliRejectsUnknownOption() {
        // 拼错的参数应立即失败，不触发报告生成。
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = runCli(new ByteArrayOutputStream(), errors, "--unknown", "value");
        assertEquals(2, status);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Unknown types option"));
    }

    @Test
    public void cliRejectsMissingOptionValue() {
        // 具名参数缺少值时提供明确错误。
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = runCli(new ByteArrayOutputStream(), errors, "--output");
        assertEquals(2, status);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Missing value for --output"));
    }

    @Test
    public void cliRejectsRepeatedOption() {
        // 同一参数重复声明不能悄悄采用后一个值。
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = runCli(new ByteArrayOutputStream(), errors, "--output", "one.md", "--output", "two.md");
        assertEquals(2, status);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Repeated types option"));
    }

    @Test
    public void cliRequiresExplicitRunDirectory() {
        // 不使用 latest 或邻近轮次结果补齐省略的归档。
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = runCli(new ByteArrayOutputStream(), errors, "--expectations", "review.json", "--output", "a.md");
        assertEquals(2, status);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("Required types option: --run-directory"));
    }

    @Test
    public void cliWritesReportForExplicitRun() throws Exception {
        // CLI 使用显式本轮归档和审查文件执行完整报告路径。
        Path run = createRun();
        Path expectations = run.resolve("expectations.json");
        writeJson(expectations, reviewedExpectations());
        Path output = run.resolve("cli/support.md");
        ByteArrayOutputStream standard = new ByteArrayOutputStream();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = runCli(standard, errors, "--run-directory", run.toString(), //
                "--expectations", expectations.toString(), "--output", output.toString());

        // 标准输出提供机器可读摘要，报告和明细写入各自文件。
        assertEquals(errors.toString(StandardCharsets.UTF_8), 0, status);
        Map<?, ?> summary = JSON.readValue(standard.toByteArray(), Map.class);
        assertFalse(summary.containsKey("runId"));
        assertEquals(1, summary.get("typeCases"));
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.isRegularFile(output.resolveSibling("support.json")));
    }

    @Test
    public void expectationsRequireNamedReviewer() throws Exception {
        // 没有审查者的声明不能被当成已审查支持依据。
        Path path = this.temporary.newFile("unreviewed.json").toPath();
        Map<String, Object> input = reviewedExpectations();
        input.put("review", values("basis", "test"));
        writeJson(path, input);
        assertInvalid("review.reviewedBy/basis", () -> NxnTypeReport.readExpectations(path));
    }

    @Test
    public void expectationsRejectDuplicateRuleIdentity() throws Exception {
        // 规则 ID 在一份审查文件中必须唯一。
        Path path = this.temporary.newFile("duplicate-review.json").toPath();
        Map<String, Object> input = reviewedExpectations();
        input.put("rules", List.of(rule(), rule()));
        writeJson(path, input);
        assertInvalid("unique IDs", () -> NxnTypeReport.readExpectations(path));
    }

    @Test
    public void expectationsRejectUnsupportedWithoutRejection() throws Exception {
        // 不支持的结论必须明确以精确拒绝作为审查依据。
        Path path = this.temporary.newFile("unsupported-review.json").toPath();
        Map<String, Object> input = reviewedExpectations();
        input.put("rules", List.of(rule("unsupported", "roundtrip")));
        writeJson(path, input);
        assertInvalid("precise rejection", () -> NxnTypeReport.readExpectations(path));
    }

    @Test
    public void readRunRecordsExactInputHashes() throws Exception {
        // 输入摘要按原始字节计算，便于定位生成报告所依据的证据。
        Path run = createRun();
        Map<String, Object> result = NxnTypeReport.readRun(run);
        Map<String, Object> hashes = NxnTypeReport.object(result.get("inputSha256"));
        Path selected = run.resolve("selected-cases.json").toAbsolutePath().normalize();
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(selected));
        assertEquals(HexFormat.of().formatHex(digest), hashes.get(selected.toString()));
        assertEquals(4, hashes.size());
    }

    @Test
    public void generationRecordsReviewFileHash() throws Exception {
        // 审查文件的内容摘要与报告一起保存。
        Path run = createRun();
        Path expectations = run.resolve("expectations.json");
        writeJson(expectations, reviewedExpectations());
        Map<String, Object> report = NxnTypeReport.generate(run, expectations, null, run.resolve("support.md"), null);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(expectations));
        assertEquals(HexFormat.of().formatHex(digest), report.get("expectationsSha256"));
    }

    @Test
    public void generationRejectsInvalidCatalogVersion() throws Exception {
        // C 目录版本不合法时不生成可能误分类的报告。
        Path run = createRun();
        Path expectations = run.resolve("expectations.json");
        writeJson(expectations, reviewedExpectations());
        Path catalog = run.resolve("catalog.json");
        writeJson(catalog, values("schemaVersion", 2, "vectors", List.of()));
        Path output = run.resolve("support.md");
        assertInvalid("C catalog requires", () -> NxnTypeReport.generate(run, expectations, catalog, output, null));
        assertFalse(Files.exists(output));
    }

    private Path createRun() throws Exception {
        Path directory = this.temporary.newFolder().toPath().resolve("interoperability");
        Files.createDirectories(directory);
        writeRun(directory, List.of(executedCase()));
        return directory;
    }

    private static void writeRun(Path directory, List<Map<String, Object>> cases) throws Exception {
        // 每个夹具保留同轮次汇总、选择身份及 XML 三类原始输入。
        writeJson(directory.resolve("selected-cases.json"), cases);
        writeJson(directory.resolve("summary.json"), values("runId", "run-A", "tests", cases.size(), //
                "passed", cases.size(), "failed", 0, "skipped", 0, "testTaskStarted", true, //
                "resultsAvailable", true, "reusedResults", false));
        writeJson(directory.resolve("configuration.json"), values("runId", "run-A"));
        Path results = directory.resolve("test-results");
        Files.createDirectories(results);

        // XML 身份直接来自同一个用例列表，不读取仓库已有测试产物。
        StringBuilder xml = new StringBuilder("<testsuite>");
        for (Map<String, Object> testcase : cases) {
            xml.append("<testcase classname=\"").append(testcase.get("className"));
            xml.append("\" name=\"").append(testcase.get("scenario")).append("\"></testcase>");
        }
        xml.append("</testsuite>");
        Files.writeString(results.resolve("TEST-fixture.xml"), xml);
    }

    private static void changeSummary(Path directory, String key, Object value) throws Exception {
        Path path = directory.resolve("summary.json");
        Map<String, Object> summary = NxnTypeReport.object(NxnTypeReport.load(path));
        summary.put(key, value);
        writeJson(path, summary);
    }

    private static void writeJson(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writeValueAsString(value));
    }

    private static Map<String, Object> executedCase() {
        Map<String, Object> testcase = exampleCase();
        testcase.put("status", "passed");
        testcase.put("seconds", "0.123");
        return testcase;
    }

    private static Map<String, Object> exampleCase() {
        return scenario("net.hasor.realmeteor.protocol.grpc.python.PythonGrpcTypeInteropTest", //
                "meteorProviderPreservesPythonNativeType", "types/python/grpc-protobuf/direct/int64/int64-maximum");
    }

    private static Map<String, Object> scenario(String className, String method, String parameters) {
        String name = method + "[" + parameters + "]";
        return values("caseId", className + "#" + name, "className", className, "scenario", name);
    }

    private static Map<String, Object> rule() {
        return rule("supported", "roundtrip");
    }

    private static Map<String, Object> rule(String support, String outcome) {
        return values("id", "reviewed-example", "select", values("language", "python"), //
                "support", support, "outcome", outcome, "reason", "One reviewed test fixture.");
    }

    private static Map<String, Object> reviewedExpectations() {
        return values("schemaVersion", 1, "review", values("reviewedBy", "fixture", "basis", "test"), //
                "rules", List.of(rule()), "axes", values("languages", List.of("python"), "protocols", List.of("grpc")));
    }

    private static Map<String, Object> supportEntry(String status) {
        return values("caseId", "a#b", "language", "python", "testStatus", status);
    }

    private static Map<String, Object> markdownEntry(Object... changes) {
        Map<String, Object> result = values("language", "python", "protocol", "grpc", //
                "profile", "grpc-protobuf", "encoding", "Protobuf", "role", "provider", "section", "basic", //
                "type", "int64", "support", "supported", "testStatus", "passed", //
                "ruleId", "reviewed-fixture", "reason", "Reviewed fixture.");
        result.putAll(values(changes));
        return result;
    }

    private static Map<String, Object> markdownReport(List<Map<String, Object>> cases, List<String> languages) {
        Map<String, Object> run = values("runId", "fixture", "configuration", Map.of(), "directory", "fixture");
        return values("run", run, "cases", cases, "hasPlannedCases", true, //
                "typeCaseCounts", values("passed", cases.size()), "review", Map.of(), //
                "axes", values("languages", languages, "protocols", List.of("grpc", "soap")));
    }

    private static String render(List<Map<String, Object>> cases) throws Exception {
        return render(cases, List.of("python"));
    }

    private static String render(List<Map<String, Object>> cases, List<String> languages) throws Exception {
        return NxnTypeReport.renderMarkdown(markdownReport(cases, languages), Path.of("audit.json"), null);
    }

    private static int runCli(ByteArrayOutputStream output, ByteArrayOutputStream errors, String... arguments) {
        try (PrintStream standard = new PrintStream(output, true, StandardCharsets.UTF_8); //
                PrintStream diagnostics = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            return NxnTypeReport.run(arguments, standard, diagnostics);
        }
    }

    private static Map<String, Object> values(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static int occurrences(String text, String target) {
        return (text.length() - text.replace(target, "").length()) / target.length();
    }

    private static void assertInvalid(String message, ThrowingRunnable action) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action);
        assertTrue(error.getMessage(), error.getMessage().contains(message));
    }
}
