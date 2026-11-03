/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.meteor.test.nxn;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import net.hasor.cobble.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import static org.junit.jupiter.api.Assertions.*;

/** 报告完整性使用独立文件夹验证，不启动 Docker、Gradle 或协议对端。 */
public class NxnReportTest {
    @TempDir
    public  Path                temporary;
    private Path                root;
    private Path                build;
    private Path                manifestPath;
    private Map<String, Object> manifest;

    @BeforeEach
    public void prepareCurrentRun() throws Exception {
        // 两个工作组分别持有一份原始 XML、逻辑报告和独立进程证据。
        this.root = this.temporary;
        this.build = this.root.resolve("build");
        this.manifestPath = this.root.resolve("manifest.json");
        this.manifest = map("schemaVersion", 3, "runId", "current-run", "userIncludes", list(), //
                "matrixSelection", map(), "discoveredClasses", list("test.First", "test.Second"), //
                "selectedClasses", list("test.First", "test.Second"), //
                "selectedSelections", list("test.First", "test.Second"), "groups", list());
        this.addGroup("g1", "test.First", "first[vector]", "passed", null);
        this.addGroup("g2", "test.Second", "second[vector]", "passed", null);
    }

    @Test
    public void validGroupsHaveNoAggregationErrors() throws Exception {
        // 完整的当前轮次证据可以聚合。
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void singleGroupSuiteRemainsByteIdentical() throws Exception {
        // 聚合单个工作组的测试类时保留原始 XML 字节。
        this.aggregate();
        Path source = this.root.resolve("g1/test-results/test/TEST-test.First.xml");
        Path copied = this.build.resolve("test-results/test/TEST-test.First.xml");
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(copied));
    }

    @Test
    public void parameterizedCaseNameRemainsIntact() throws Exception {
        // 参数化名称是原始 caseId 的组成部分。
        this.aggregate();
        Path selected = this.build.resolve("interoperability/selected-cases.json");
        List<Map<String, Object>> cases = readList(selected);
        assertEquals("test.First#first[vector]", cases.get(0).get("caseId"));
    }

    @Test
    public void matrixDimensionsComeFromThePlanWithoutParsingDisplayNames() throws Exception {
        // 两个普通显示名对应相反语言方向，维度只能从本轮计划精确关联。
        this.declareMatrix("g1", "test.First#first[vector]", "java", "python");
        this.declareMatrix("g2", "test.Second#second[vector]", "python", "java");
        Map<String, Object> summary = this.aggregate();

        // 固定矩阵统计不把运行期发现的旧场景伪装成静态计划。
        assertEquals(list(), summary.get("aggregationErrors"));
        Map<String, Object> matrix = child(summary, "matrixSummary");
        assertEquals(2, matrix.get("planned"));
        assertEquals(2, matrix.get("passed"));
        assertEquals(1, entries(matrix, "byRpc").size());
        assertEquals(list("2", "3"), entries(matrix, "byRpc").get(0).get("protocolVersions"));
        assertEquals(2, entries(matrix, "byLanguage").size());
        assertEquals("runtime", summary.get("legacyDiscovery"));
        List<Map<String, Object>> results = entries(summary, "matrixResults");
        Map<String, Object> provider = child(child(results.get(0), "matrix"), "provider");
        assertEquals("java", provider.get("language"));
        assertFalse(provider.containsKey("command"));
        assertFalse(provider.containsKey("environment"));
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("执行前计划 2 项"));
        assertTrue(markdown.contains("提供者 `java` → 消费者 `python`"));
    }

    @Test
    public void matrixGroupCannotChangeItsDeclaredWireProfile() throws Exception {
        // 分组的运行范围必须与根计划一致，不能自行改成另一个传输组合。
        this.declareMatrix("g1", "test.First#first[vector]", "java", "python");
        entries(this.group(0), "matrixCases").get(0).put("wire", "different-wire");

        this.assertError("changed its matrix case");
    }

    @Test
    public void matrixProtocolVersionsRejectNonStringEntries() throws Exception {
        // 版本是声明文本，不能把错误的数字或空值悄悄转换成另一份范围。
        Map<String, Object> planned = this.declareMatrix( //
                "g1", "test.First#first[vector]", "java", "python");
        planned.put("protocolVersions", list("2", 3));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, this::aggregate);
        assertTrue(failure.getMessage().contains("protocolVersions must be a string array"));
    }

    @Test
    public void reportedMatrixCannotOverrideThePlannedParticipants() throws Exception {
        // XML 用例本身真实通过，但附带的消费者语言被逻辑报告篡改。
        Map<String, Object> planned = this.declareMatrix( //
                "g1", "test.First#first[vector]", "java", "python");
        Map<String, Object> forged = new LinkedHashMap<>(planned);
        forged.put("consumer", map("id", "other", "language", "cpp"));
        Path selected = this.report("g1").resolve("selected-cases.json");
        List<Map<String, Object>> reported = readList(selected);
        reported.get(0).put("matrix", forged);
        NxnReport.writeJson(selected, reported);

        // 聚合拒绝冲突元数据，输出仍只能使用根计划中的真实双方。
        Map<String, Object> summary = this.aggregate();
        assertTrue(containsError(strings(summary, "aggregationErrors"), "conflicting matrix metadata"));
        Map<String, Object> matrix = child(entries(summary, "matrixResults").get(0), "matrix");
        assertEquals("python", child(matrix, "consumer").get("language"));
    }

    @Test
    public void matrixCaseWithoutAGroupReportHasAnUnknownOutcome() throws Exception {
        // 丢失工作进程报告后无法断言业务是否执行，不能补写通过或未执行。
        this.declareMatrix("g1", "test.First#first[vector]", "java", "python");
        Files.delete(this.report("g1").resolve("summary.json"));
        Map<String, Object> summary = this.aggregate();

        assertEquals(1, summary.get("tests"));
        assertEquals(1, summary.get("passed"));
        assertEquals(0, summary.get("notExecuted"));
        assertEquals(1, summary.get("unknown"));
        Map<String, Object> matrix = child(summary, "matrixSummary");
        assertEquals(1, matrix.get("unknown"));
        assertEquals(0, matrix.get("passed"));
        assertEquals("unknown", entries(summary, "matrixResults").get(0).get("status"));
    }

    @Test
    public void legacyUnknownOutcomesAreVisibleInTheOverallSummary() throws Exception {
        // 旧场景报告完整，但某个已开始用例没有终态，不能归入未执行或通过。
        String unknown = "test.First#unfinished[vector]";
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", unknown), //
                "notExecutedCases", list(), "notExecuted", 0, "unknownCases", list(unknown), "unknown", 1));

        // 不依赖标准矩阵或缺失工作组提示，顶层摘要也必须显示结果未知。
        Map<String, Object> summary = this.aggregate();
        assertEquals(1, summary.get("unknown"));
        assertEquals(0, summary.get("notExecuted"));
        assertEquals(false, summary.get("notExecutedUnknown"));
        assertEquals(0, child(summary, "matrixSummary").get("planned"));
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("已确认未执行 0 项，结果未知 1 项"));
    }

    @Test
    public void matrixNotStartedCasesRemainSeparateFromUnknownOutcomes() throws Exception {
        // 第一项已有 XML，后续固定计划未开始；工作组明确保留该未执行身份。
        this.declareMatrix("g1", "test.First#first[vector]", "java", "python");
        String missing = "test.First#later[second-vector]";
        this.declareMatrix("g1", missing, "java", "python");
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", missing), //
                "notExecutedCases", list(missing), "notExecuted", 1, "unknownCases", list(), "unknown", 0));

        Map<String, Object> summary = this.aggregate();
        assertEquals(2, summary.get("passed"));
        assertEquals(1, summary.get("notExecuted"));
        assertEquals(0, summary.get("unknown"));
        Map<String, Object> matrix = child(summary, "matrixSummary");
        assertEquals(2, matrix.get("planned"));
        assertEquals(1, matrix.get("passed"));
        assertEquals(1, matrix.get("notExecuted"));
    }

    @Test
    public void preparationFailureNamesTheUnstartedMatrixCombination() throws Exception {
        // 初始化错误是真实 XML 事件，原定 RPC 叶子没有执行结果。
        this.replaceFirstGroup("failed");
        this.rewriteFirstRpcCase("initializationError");
        String caseId = "test.First#first[vector]";
        this.declareMatrix("g1", caseId, "java", "python");
        Map<String, Object> failure = map("caseId", "test.First#initializationError", //
                "affectedCaseIds", list(caseId), "failure", "provider startup failed", //
                "logs", map("stderr", "stderr.log"));
        this.update("g1", "summary.json", map("plannedCases", list(caseId), //
                "notExecutedCases", list(caseId), "notExecuted", 1, "preparationFailures", list(failure)));

        // 准备失败保留双方和阶段，不伪造该 RPC 叶子为失败或成功 XML。
        Map<String, Object> summary = this.aggregate();
        assertEquals(2, summary.get("tests"));
        Map<String, Object> result = entries(summary, "matrixResults").get(0);
        assertEquals(caseId, result.get("caseId"));
        assertEquals("preparation-failed", result.get("status"));
        assertEquals("preparing", result.get("phase"));
        assertEquals("python", child(child(result, "matrix"), "consumer").get("language"));
        assertEquals(1, child(summary, "matrixSummary").get("preparationFailed"));
        assertEquals(0, child(summary, "matrixSummary").get("failed"));
    }

    @Test
    public void cleanupFailureDoesNotRewriteAPassedRpcResult() throws Exception {
        // RPC 已通过，独立的关闭错误仍需影响整轮结果并定位原矩阵组合。
        String caseId = "test.First#first[vector]";
        this.declareMatrix("g1", caseId, "java", "python");
        Map<String, Object> failure = map("caseId", "test.First#initializationError", //
                "affectedCaseIds", list(caseId), "failure", "consumer close failed", //
                "logs", map("stderr", "stderr.log"));
        this.update("g1", "summary.json", map("cleanupFailures", list(failure)));

        // 原始 XML 的通过证据不改写，矩阵结果单独说明清理失败。
        Map<String, Object> summary = this.aggregate();
        assertEquals(2, summary.get("passed"));
        assertEquals(0, summary.get("failed"));
        assertTrue(containsError(strings(summary, "aggregationErrors"), "preparation or cleanup failures"));
        Map<String, Object> result = entries(summary, "matrixResults").get(0);
        assertEquals("cleanup-failed", result.get("status"));
        assertEquals("cleanup", result.get("phase"));
        assertEquals(1, child(summary, "matrixSummary").get("cleanupFailed"));
        assertEquals(0, child(summary, "matrixSummary").get("failed"));
        assertEquals(0, child(summary, "matrixSummary").get("passed"));
    }

    @Test
    public void sdkVersionsRemainSeparateInTheCompatibilityReport() throws Exception {
        // 同一个 SDK 的两个发行版本各有一项真实消费者执行证据。
        this.addSdkGroup("g3", "sdk/java/hessian/Caucho Hessian@4.0.51/hessian/SDK-to-Meteor", "passed");
        this.addSdkGroup("g4", "sdk/java/hessian/Caucho Hessian@4.0.63/hessian/SDK-to-Meteor", "passed");

        // 不同版本分别落盘；没有执行的反向调用不能自动视为通过或不支持。
        Map<String, Object> summary = this.aggregate();
        List<Map<String, Object>> versions = entries(summary, "sdkCompatibility");
        assertEquals(list(), summary.get("aggregationErrors"));
        assertEquals(2, versions.size());
        assertEquals("java", versions.get(0).get("language"));
        assertEquals("4.0.51", versions.get(0).get("version"));
        assertEquals("4.0.63", versions.get(1).get("version"));
        assertEquals("Caucho Hessian", versions.get(0).get("sdk"));
        assertEquals("passed", versions.get(0).get("sdkToMeteor"));
        assertEquals("not-executed", versions.get(0).get("meteorToSdk"));
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("| java | hessian | Caucho Hessian | 4.0.51 | hessian | 通过 | — |"));
        assertTrue(markdown.contains("— 表示本轮未执行该方向，不表示 SDK 不支持。"));
        Map<String, Object> stored = readMap(this.build.resolve("interoperability/summary.json"));
        assertEquals(versions, entries(stored, "sdkCompatibility"));
    }

    @Test
    public void sdkDirectionsShareOneExactVersionAndProfile() throws Exception {
        // 两个独立工作组分别执行同一版本、同一运行图的两个调用方向。
        this.addSdkGroup("g3", "sdk/java/grpc/gRPC Java@1.84.1/grpc/SDK-to-Meteor", "passed");
        this.addSdkGroup("g4", "sdk/java/grpc/gRPC Java@1.84.1/grpc/Meteor-to-SDK", "passed");

        // 双向结果合并到同一行，保留 SDK 与运行图的精确身份。
        List<Map<String, Object>> versions = entries(this.aggregate(), "sdkCompatibility");
        assertEquals(1, versions.size());
        Map<String, Object> grpc = versions.get(0);
        assertEquals("java", grpc.get("language"));
        assertEquals("grpc", grpc.get("protocol"));
        assertEquals("gRPC Java", grpc.get("sdk"));
        assertEquals("1.84.1", grpc.get("version"));
        assertEquals("grpc", grpc.get("profile"));
        assertEquals("passed", grpc.get("sdkToMeteor"));
        assertEquals("passed", grpc.get("meteorToSdk"));
    }

    @Test
    public void sdkProfilesRemainSeparateForTheSameVersion() throws Exception {
        // 相同 SDK 版本的不同运行图不能借用彼此的方向结果。
        this.addSdkGroup("g3", "sdk/java/hessian/Caucho Hessian@4.0.63/hessian1/SDK-to-Meteor", "passed");
        this.addSdkGroup("g4", "sdk/java/hessian/Caucho Hessian@4.0.63/hessian2/Meteor-to-SDK", "passed");

        // 每个运行图只记录自己已经执行的方向。
        List<Map<String, Object>> versions = entries(this.aggregate(), "sdkCompatibility");
        assertEquals(2, versions.size());
        assertEquals("hessian1", versions.get(0).get("profile"));
        assertEquals("not-executed", versions.get(0).get("meteorToSdk"));
        assertEquals("hessian2", versions.get(1).get("profile"));
        assertEquals("not-executed", versions.get(1).get("sdkToMeteor"));
    }

    @Test
    public void sdkLanguagesRemainSeparateForTheSameSdkVersionAndProfile() throws Exception {
        // C 与 C++ 即使采用同名 SDK、版本及运行图，也有各自的互通证据。
        this.addSdkGroup("g3", "sdk/c/grpc/gRPC Core@1.51.1/native/SDK-to-Meteor", "passed");
        this.addSdkGroup("g4", "sdk/c++/grpc/gRPC Core@1.51.1/native/Meteor-to-SDK", "passed");

        // 两种语言分别保留自己的方向，不能合并成某一语言的双向通过。
        List<Map<String, Object>> versions = entries(this.aggregate(), "sdkCompatibility");
        assertEquals(2, versions.size());
        assertEquals("c", versions.get(0).get("language"));
        assertEquals("passed", versions.get(0).get("sdkToMeteor"));
        assertEquals("not-executed", versions.get(0).get("meteorToSdk"));
        assertEquals("c++", versions.get(1).get("language"));
        assertEquals("not-executed", versions.get(1).get("sdkToMeteor"));
        assertEquals("passed", versions.get(1).get("meteorToSdk"));
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("## SDK 版本兼容"));
        assertTrue(markdown.contains("| 语言 | 协议 | SDK | 版本 |"));
        assertTrue(markdown.contains("| c++ | grpc | gRPC Core | 1.51.1 | native | — | 通过 |"));
    }

    @Test
    public void nonJavaSdkVersionsRemainSeparate() throws Exception {
        // 同一个 Python SDK 的两个发行版本使用相同运行图名称。
        this.addSdkGroup("g3", "sdk/python/thrift/Apache Thrift@0.22.0/thrift/SDK-to-Meteor", "passed");
        this.addSdkGroup("g4", "sdk/python/thrift/Apache Thrift@0.25.0/thrift/SDK-to-Meteor", "passed");

        // 通用解析保留版本差异，不把 Java 之外的结果忽略或合并。
        List<Map<String, Object>> versions = entries(this.aggregate(), "sdkCompatibility");
        assertEquals(2, versions.size());
        assertEquals("python", versions.get(0).get("language"));
        assertEquals("0.22.0", versions.get(0).get("version"));
        assertEquals("python", versions.get(1).get("language"));
        assertEquals("0.25.0", versions.get(1).get("version"));
    }

    @Test
    public void sdkNamesContainingASlashDoNotCreateACompatibilityRow() throws Exception {
        // SDK 名称中的斜杠会破坏固定参数结构，不能被错分为其它版本信息。
        this.addSdkGroup("g3", "sdk/python/grpc/grpc/io@1.84.0/grpc/SDK-to-Meteor", "passed");

        // 原始用例仍参与整轮计数，但没有有效身份的 SDK 结果不进入版本表。
        Map<String, Object> summary = this.aggregate();
        assertEquals(3, summary.get("tests"));
        assertEquals(list(), summary.get("sdkCompatibility"));
        assertEquals(list(), summary.get("aggregationErrors"));
    }

    @Test
    public void sdkMethodDirectionTokensPreserveBothMethodsForOneVersion() throws Exception {
        // 同一测试类的两个方法共享版本参数，方向标识不区分大小写。
        deleteTree(this.root.resolve("g1"));
        deleteTree(this.root.resolve("g2"));
        String className = "test.NativeSdk";
        String incoming = "nativeSDKToMeteorEcho";
        String outgoing = "nativemeteortosdkEcho";
        String parameter = "[sdk/c/grpc/gRPC Core@1.51.1/native]";
        this.manifest.put("discoveredClasses", list(className));
        this.manifest.put("selectedClasses", list(className));
        this.manifest.put("selectedSelections", list(className + "." + incoming, className + "." + outgoing));
        this.manifest.put("groups", list());
        this.addGroup("g1", className, incoming + parameter, "passed", className + "." + incoming);
        this.addGroup("g2", className, outgoing + parameter, "passed", className + "." + outgoing);

        // 原始两项执行都保留，版本表按明确的方法方向汇总为一行双向结果。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("aggregationErrors"));
        assertEquals(2, summary.get("tests"));
        List<Map<String, Object>> versions = entries(summary, "sdkCompatibility");
        assertEquals(1, versions.size());
        assertEquals("passed", versions.get(0).get("sdkToMeteor"));
        assertEquals("passed", versions.get(0).get("meteorToSdk"));
        Path selected = this.build.resolve("interoperability/selected-cases.json");
        List<Map<String, Object>> cases = readList(selected);
        assertEquals(2, cases.size());
        assertEquals(className + "#" + incoming + parameter, cases.get(0).get("caseId"));
        assertEquals(className + "#" + outgoing + parameter, cases.get(1).get("caseId"));
    }

    @Test
    public void sdkParametersWithoutDirectionDoNotInferConsumerOrProviderRoles() throws Exception {
        // 普通消费者和提供者字样不是约定的显式方向标识。
        this.addSdkMethodGroup("g3", "consumerCallsProvider", //
                "sdk/c/grpc/gRPC Core@1.51.1/native", "passed");

        // 该用例仍参与执行统计，但未知方向不能被默认归到任一列。
        Map<String, Object> summary = this.aggregate();
        assertEquals(3, summary.get("tests"));
        assertEquals(list(), summary.get("sdkCompatibility"));
        assertEquals(list(), summary.get("aggregationErrors"));
    }

    @Test
    public void sdkMethodWithBothDirectionTokensDoesNotCreateACompatibilityRow() throws Exception {
        // 一个方法同时包含两个方向标识时，参数没有足够信息消除歧义。
        this.addSdkMethodGroup("g3", "nativeSdkToMeteorAndMeteorToSdk", //
                "sdk/c/grpc/gRPC Core@1.51.1/native", "passed");

        // 歧义只阻止版本表统计，不改变原始用例的执行结果。
        Map<String, Object> summary = this.aggregate();
        assertEquals(3, summary.get("passed"));
        assertEquals(list(), summary.get("sdkCompatibility"));
        assertEquals(list(), summary.get("aggregationErrors"));
    }

    @Test
    public void sdkExplicitDirectionConflictingWithTheMethodTokenIsNotReported() throws Exception {
        // 方法名与参数给出相反方向时，不能任选一方解释兼容结果。
        this.addSdkMethodGroup("g3", "nativeMeteorToSdkEcho", //
                "sdk/c/grpc/gRPC Core@1.51.1/native/SDK-to-Meteor", "passed");
        this.addSdkMethodGroup("g4", "nativeSdkToMeteorEcho", //
                "sdk/c/grpc/gRPC Core@1.51.1/native/Meteor-to-SDK", "passed");

        // 两项冲突证据照常保留，版本表不输出错误的通过方向。
        Map<String, Object> summary = this.aggregate();
        assertEquals(4, summary.get("tests"));
        assertEquals(list(), summary.get("sdkCompatibility"));
        assertEquals(list(), summary.get("aggregationErrors"));
    }

    @Test
    public void sdkFailedDirectionCannotBeOverwrittenByAPassingCase() throws Exception {
        // 同一版本和方向先有失败用例，后有其它通过用例。
        String parameter = "sdk/java/thrift/Apache Thrift@0.22.0/thrift-old/SDK-to-Meteor";
        this.addSdkGroup("g3", parameter, "failed");
        this.addSdkGroup("g4", parameter, "passed");

        // 版本汇总保持失败，原有整轮验收也继续报告失败。
        Map<String, Object> summary = this.aggregate();
        Map<String, Object> thrift = entries(summary, "sdkCompatibility").get(0);
        assertEquals("failed", thrift.get("sdkToMeteor"));
        assertEquals(1, summary.get("failed"));
        assertFalse(strings(summary, "aggregationErrors").isEmpty());
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("| java | thrift | Apache Thrift | 0.22.0 | thrift-old | 失败 | — |"));
    }

    @Test
    public void sdkSkippedDirectionCannotBeOverwrittenByAPassingCase() throws Exception {
        // 同一版本和方向存在跳过用例时，另一项通过不能证明完整兼容。
        String parameter = "sdk/java/soap/Metro JAX-WS@3.0.2/soap-old/Meteor-to-SDK";
        this.addSdkGroup("g3", parameter, "skipped");
        this.addSdkGroup("g4", parameter, "passed");

        // 跳过状态保留在版本表和整轮计数中。
        Map<String, Object> summary = this.aggregate();
        Map<String, Object> soap = entries(summary, "sdkCompatibility").get(0);
        assertEquals("skipped", soap.get("meteorToSdk"));
        assertEquals(1, summary.get("skipped"));
        assertFalse(strings(summary, "aggregationErrors").isEmpty());
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("| java | soap | Metro JAX-WS | 3.0.2 | soap-old | — | 跳过 |"));
    }

    @Test
    public void unversionedSdkCasesDoNotCreateACompatibilityTable() throws Exception {
        // 原有 SDK 用例没有版本参数，即使清单声明依赖也不能补成版本证据。
        this.addSdkGroup("g3", "sdk/java/hessian/SDK-to-Meteor", "passed");
        this.manifest.put("sdkVersions", map("hessian", "4.0.63"));

        // 没有实际版本化用例时不输出兼容表，原有通过判定保持不变。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("sdkCompatibility"));
        assertEquals(list(), summary.get("aggregationErrors"));
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertFalse(markdown.contains("SDK 版本兼容"));
    }

    @Test
    public void existingAggregateIsRegenerated() throws Exception {
        // 再次生成时使用同一目录，不复用已经生成的汇总。
        this.aggregate();
        Path obsolete = this.build.resolve("interoperability/old-results/TEST-old.xml");
        this.writeText(obsolete, "old results");

        // 本轮输入重新生成报告，清除原目录中已不属于本轮的文件。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("aggregationErrors"));
        assertFalse(Files.exists(obsolete));
        assertTrue(Files.isRegularFile(this.build.resolve("interoperability/summary.json")));
        assertFalse(Files.exists(this.build.resolve("interoperability/current-run")));
    }

    @Test
    public void emptySelectionIsRejected() throws Exception {
        // 空选择不能生成一个看似成功的零用例报告。
        this.manifest.put("selectedClasses", list());
        this.manifest.put("selectedSelections", list());
        this.assertError("must not be empty");
    }

    @Test
    public void unknownSchemaIsRejected() throws Exception {
        // 聚合器只接受明确支持的清单版本。
        this.manifest.put("schemaVersion", 2);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, this::aggregate);
        assertTrue(failure.getMessage().contains("schemaVersion 3"));
    }

    @Test
    public void missingGroupReportIsRejected() throws Exception {
        // 工作组缺失摘要时不能仅靠原始 XML 判为成功。
        Files.delete(this.report("g1").resolve("summary.json"));
        this.assertError("report is invalid");
    }

    @Test
    public void failedGroupExitIsRejected() throws Exception {
        // 工作组启动进程失败必须传播到聚合结果。
        this.group(0).put("exitCode", 1);
        this.assertError("exited with 1");
    }

    @Test
    public void groupCleanupFailureIsRejected() throws Exception {
        // 已知资源释放失败不能被通过的用例掩盖。
        this.group(0).put("cleanupFailures", list("unreleased process"));
        this.assertError("cleanup failed");
    }

    @Test
    public void missingProcessExitIsRejected() throws Exception {
        // JVM 的退出证据是独立于 JUnit 报告的必需信息。
        Files.delete(this.root.resolve("g1/process/status.json"));
        this.assertError("process evidence is invalid");
    }

    @Test
    public void processExitFromAnotherRunIsRejected() throws Exception {
        // 上一轮进程状态不能证明本轮已经退出。
        this.updateProcess(map("runId", "previous-run"));
        this.assertError("process belongs to another run/group");
    }

    @Test
    public void processExitFromAnotherGroupIsRejected() throws Exception {
        // 其它工作组的进程状态不能被复用。
        this.updateProcess(map("groupId", "g2"));
        this.assertError("process belongs to another run/group");
    }

    @Test
    public void processExitFailureIsRejected() throws Exception {
        // JUnit JVM 非零退出必须导致聚合失败。
        this.updateProcess(map("exitCode", 7));
        this.assertError("JVM process exited unsuccessfully");
    }

    @Test
    public void processCleanupFailureIsRejected() throws Exception {
        // 主进程退出后仍有进程组成员表示清理未完成。
        this.updateProcess(map("processGroupEmpty", false));
        this.assertError("process cleanup is incomplete");
    }

    @Test
    public void remainingProcessesPreventCleanupSuccess() throws Exception {
        // 明确列出的残留 PID 不能被清理成功标记覆盖。
        this.updateProcess(map("remainingPids", list(42)));
        this.assertError("process cleanup is incomplete");
    }

    @Test
    public void remainingTemporaryDirectoryPreventsCleanupSuccess() throws Exception {
        // 本场景临时目录的释放也属于进程生命周期。
        this.updateProcess(map("temporaryDirectoryRemoved", false));
        this.assertError("process cleanup is incomplete");
    }

    @Test
    public void processIdentityMustMatchLaunch() throws Exception {
        // 退出记录必须对应真正启动的同一个进程。
        this.updateProcess(map("processId", "some-other-process"));
        this.assertError("process identity does not match launch");
    }

    @Test
    public void processErrorPreventsSuccess() throws Exception {
        // 启动错误不能因为已有退出码为零而被忽略。
        this.updateProcess(map("error", "program failed to start"));
        this.assertError("process did not exit cleanly");
    }

    @Test
    public void processOutputLogsMustBeArchived() throws Exception {
        // 进程输出归档缺失会导致证据不完整。
        Files.delete(this.root.resolve("g1/process/stderr.log"));
        this.assertError("process output evidence is incomplete");
    }

    @Test
    public void peerExpectedNonzeroExitIsAccepted() throws Exception {
        // 官方对端可以通过非零退出表达测试所需的错误场景。
        this.remotePeer(map("exitCode", 9), false);
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void peerCleanupFailureIsRejected() throws Exception {
        // 对端自身报告的子进程清理失败不能忽略。
        this.remotePeer(map("cleanupFailure", "orphaned subprocess"), false);
        this.assertError("process did not exit cleanly");
    }

    @Test
    public void missingRemoteCleanupEvidenceIsRejected() throws Exception {
        // 每个工作组都必须提供对端清理检查结果。
        this.group(0).remove("remoteCleanup");
        this.assertError("remote cleanup evidence is missing or invalid");
    }

    @Test
    public void corruptRemoteCleanupEvidenceIsRejected() throws Exception {
        // 清理结果必须保留规定的数据结构。
        this.group(0).put("remoteCleanup", "invalid");
        this.assertError("remote cleanup evidence is missing or invalid");
    }

    @Test
    public void remoteCleanupFailureIsRejected() throws Exception {
        // 清理检查声明失败时聚合不能通过。
        child(this.group(0), "remoteCleanup").put("successful", false);
        this.assertError("remote cleanup failed");
    }

    @Test
    public void remoteCleanupFailureDetailsPreventSuccess() throws Exception {
        // 清理失败明细优先于错误的成功标记。
        child(this.group(0), "remoteCleanup").put("failures", list("stop failed"));
        this.assertError("remote cleanup failed");
    }

    @Test
    public void uncoveredRemotePeerIsRejected() throws Exception {
        // 实际启动的对端必须恰好有一份清理记录。
        this.addProcess(this.root.resolve("g1/process-evidence/peer-one"), "g1", "peer-one");
        this.assertError("remote cleanup does not cover peer launches exactly once");
    }

    @Test
    public void unknownRemotePeerRecordIsRejected() throws Exception {
        // 清理记录不能凭空指向没有启动过的对端。
        Map<String, Object> cleanup = this.remotePeer(map(), false);
        Path unknown = this.root.resolve("g1/process-evidence/unknown/launch.json");
        entries(cleanup, "processes").get(0).put("launchFile", unknown.toString());
        this.assertError("remote cleanup covers an unknown process");
    }

    @Test
    public void duplicateRemotePeerRecordIsRejected() throws Exception {
        // 重复清理记录不能抵消另一个未被检查的对端。
        Map<String, Object> cleanup = this.remotePeer(map(), false);
        List<Map<String, Object>> processes = entries(cleanup, "processes");
        processes.add(new LinkedHashMap<>(processes.get(0)));
        this.assertError("remote cleanup does not cover peer launches exactly once");
    }

    @Test
    public void remotePeerIdentityMustMatchLaunch() throws Exception {
        // 对端清理身份必须与启动文件一致。
        Map<String, Object> cleanup = this.remotePeer(map(), false);
        entries(cleanup, "processes").get(0).put("processId", "not-peer-one");
        this.assertError("remote cleanup process identity does not match");
    }

    @Test
    public void remotePeerStopStateIsRequired() throws Exception {
        // 对端是否由清理阶段主动停止必须明确记录。
        Map<String, Object> cleanup = this.remotePeer(map(), false);
        entries(cleanup, "processes").get(0).remove("requiredStop");
        this.assertError("remote cleanup stop state is missing");
    }

    @Test
    public void remotePeerFailureDetailsPreventSuccess() throws Exception {
        // 单个对端的失败明细不能被工作组总状态掩盖。
        Map<String, Object> cleanup = this.remotePeer(map(), false);
        entries(cleanup, "processes").get(0).put("failures", list("wrapper did not acknowledge stop"));
        this.assertError("remote process cleanup failed");
    }

    @Test
    public void remotePeerFinalSnapshotMustMatchStatusFile() throws Exception {
        // 清理记录必须使用最终状态文件的真实内容。
        Map<String, Object> cleanup = this.remotePeer(map(), false);
        entries(cleanup, "processes").get(0).put("after", map("state", "exited"));
        this.assertError("remote cleanup final status does not match");
    }

    @Test
    public void remoteCleanupCheckedCountMustMatchLaunches() throws Exception {
        // 已检查数量必须与实际启动的对端数量一致。
        Map<String, Object> cleanup = this.remotePeer(map(), false);
        cleanup.put("checked", 2);
        this.assertError("remote cleanup counts do not match");
    }

    @Test
    public void remoteCleanupStoppedCountMustMatchStopRecords() throws Exception {
        // 已停止数量必须与主动停止记录一致。
        Map<String, Object> cleanup = this.remotePeer(map(), true);
        cleanup.put("stopped", 0);
        this.assertError("remote cleanup counts do not match");
    }

    @Test
    public void remotePeerContainerMustMatchActualPeerEvidence() throws Exception {
        // 清理结果不能误用其它容器的同名对端。
        this.remotePeer(map(), false);
        Path peer = this.root.resolve("g1/process-evidence/peer-one.json");
        NxnReport.writeJson(peer, map("container", "other-service"));
        this.assertError("remote cleanup container does not match");
    }

    @Test
    public void stoppedRemotePeerWithCleanFinalStatusIsAccepted() throws Exception {
        // 清理阶段主动终止的干净对端可以退出为 143。
        this.remotePeer(map("exitCode", 143), true);
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void missingJunitReportStillChecksMissingRemoteCleanup() throws Exception {
        // JUnit 报告缺失时仍应继续验证对端清理。
        Files.delete(this.report("g1").resolve("summary.json"));
        this.group(0).remove("remoteCleanup");
        List<String> errors = strings(this.aggregate(), "aggregationErrors");

        // 两类证据错误同时记录，便于定位中断后的残留资源。
        assertTrue(containsError(errors, "report is invalid"));
        assertTrue(containsError(errors, "remote cleanup evidence is missing"));
    }

    @Test
    public void missingJunitReportRecordsFailedRemoteCleanupVerification() throws Exception {
        // 无法解析 JUnit 报告不能跳过清理状态的归档。
        Files.delete(this.report("g1").resolve("summary.json"));
        this.group(0).put("remoteCleanup", "invalid");
        Map<String, Object> summary = this.aggregate();
        Map<String, Object> first = entries(summary, "groups").get(0);
        assertEquals(false, child(first, "remoteCleanup").get("successful"));
    }

    @Test
    public void missingJunitReportStillChecksRemotePeerStatus() throws Exception {
        // 报告缺失时仍然检查对端的残留 PID。
        this.remotePeer(map("remainingPids", list(42)), false);
        Files.delete(this.report("g1").resolve("summary.json"));
        this.assertError("process cleanup is incomplete");
    }

    @Test
    public void claimedRemoteCleanupSuccessDoesNotHideUncleanStatus() throws Exception {
        // 最终进程证据可以推翻清理阶段的成功声明。
        this.remotePeer(map("remainingPids", list(42)), false);
        Map<String, Object> first = entries(this.aggregate(), "groups").get(0);
        assertEquals(false, child(first, "remoteCleanup").get("successful"));
    }

    @Test
    public void missingPeerStatusDoesNotBypassLaunchCoverage() throws Exception {
        // 无状态文件的对端仍属于必须验证的启动集合。
        this.remotePeer(map(), false);
        Files.delete(this.root.resolve("g1/process-evidence/peer-one/status.json"));
        Map<String, Object> first = entries(this.aggregate(), "groups").get(0);
        assertEquals(false, child(first, "remoteCleanup").get("successful"));
    }

    @Test
    public void previousRunConfigurationIsRejected() throws Exception {
        // 工作组配置必须属于当前编排轮次。
        this.update("g1", "configuration.json", map("orchestrationRunId", "previous-run"));
        this.assertError("configuration belongs to another run");
    }

    @Test
    public void reportIdentityMismatchIsRejected() throws Exception {
        // 摘要必须属于目录声明的逻辑报告。
        this.update("g1", "summary.json", map("runId", "different-report"));
        this.assertError("summary belongs to another run");
    }

    @Test
    public void reportGroupMismatchIsRejected() throws Exception {
        // 摘要必须属于正在聚合的工作组。
        this.update("g1", "summary.json", map("groupId", "g2"));
        this.assertError("summary belongs to another group");
    }

    @Test
    public void unstartedTestRunIsRejected() throws Exception {
        // 未真正启动测试任务不能复用目录内的报告。
        this.update("g1", "summary.json", map("testTaskStarted", false));
        this.assertError("did not execute fresh tests");
    }

    @Test
    public void reusedTestResultsAreRejected() throws Exception {
        // 聚合器拒绝缓存或上一轮测试结果。
        this.update("g1", "summary.json", map("reusedResults", true));
        this.assertError("did not execute fresh tests");
    }

    @Test
    public void unavailableCurrentResultsAreRejected() throws Exception {
        // 当前轮次没有结果时不能视为零失败。
        this.update("g1", "summary.json", map("resultsAvailable", false));
        this.assertError("has no current results");
    }

    @Test
    public void incorrectConfiguredClassesAreRejected() throws Exception {
        // 实际启动类必须与分配给该工作组的类一致。
        this.update("g1", "configuration.json", map("classNames", list("test.Second")));
        this.assertError("configured classes differ");
    }

    @Test
    public void incorrectConfiguredFiltersAreRejected() throws Exception {
        // 启动配置中的过滤器不能改变已分配的范围。
        this.update("g1", "configuration.json", map("includes", list("test.First.other")));
        this.assertError("configuration filters differ");
    }

    @Test
    public void incorrectExecutedFiltersAreRejected() throws Exception {
        // 实际执行摘要中的过滤器也必须与清单一致。
        this.update("g1", "summary.json", map("includes", list("test.First.other")));
        this.assertError("summary filters differ");
    }

    @Test
    public void changedMatrixSelectionIsRejected() throws Exception {
        // 工作组不能自行缩小协议矩阵。
        Map<String, Object> selection = map("meteor.matrix.protocol", "grpc");
        this.update("g1", "configuration.json", map("matrixSelection", selection));
        this.assertError("changed the matrix selection");
    }

    @Test
    public void differentXmlGenerationIsRejected() throws Exception {
        // 逻辑报告附带的 XML 必须与本轮原始结果逐字节一致。
        Path xml = this.report("g1").resolve("test-results/TEST-test.First.xml");
        Files.writeString(xml, Files.readString(xml) + "\n", StandardCharsets.UTF_8);
        this.assertError("raw XML does not match");
    }

    @Test
    public void caseListInconsistentWithXmlIsRejected() throws Exception {
        // 选中用例清单不能丢掉原始 XML 中的记录。
        NxnReport.writeJson(this.report("g1").resolve("selected-cases.json"), list());
        this.assertError("selected cases do not match");
    }

    @Test
    public void summaryCountInconsistentWithXmlIsRejected() throws Exception {
        // 摘要中的测试数量需要由 XML 验证。
        this.update("g1", "summary.json", map("tests", 99));
        this.assertError("summary tests does not match");
    }

    @Test
    public void suiteCountInconsistentWithXmlIsRejected() throws Exception {
        // 同时篡改两份 XML 的 suite 计数仍然不能通过内部一致性检查。
        Path xml = this.root.resolve("g1/test-results/test/TEST-test.First.xml");
        String content = Files.readString(xml).replace("tests=\"1\"", "tests=\"99\"");
        Files.writeString(xml, content, StandardCharsets.UTF_8);
        Files.copy(xml, this.report("g1").resolve("test-results/TEST-test.First.xml"), //
                StandardCopyOption.REPLACE_EXISTING);
        this.assertError("JUnit suite count disagrees");
    }

    @Test
    public void duplicateGroupIdentityIsRejected() throws Exception {
        // 不同工作组不能共享归档身份。
        this.group(1).put("id", "g1");
        this.assertError("Group IDs must be present and unique");
    }

    @Test
    public void duplicateOutputDirectoryIsRejected() throws Exception {
        // 不同工作组不能覆盖同一个结果目录。
        this.group(1).put("outputDirectory", this.root.resolve("g1").toString());
        this.assertError("unique output directories");
    }

    @Test
    public void unassignedClassSelectionIsRejected() throws Exception {
        // 已选范围必须被工作组恰好分配一次。
        strings(this.manifest, "selectedSelections").add("test.Missing");
        this.assertError("do not cover selected selections exactly once");
    }

    @Test
    public void selectedClassAbsentFromDiscoveryIsRejected() throws Exception {
        // 清单不能执行没有被发现的测试类。
        this.manifest.put("discoveredClasses", list("test.First"));
        this.assertError("absent from discovery");
    }

    @Test
    public void unfilteredRunMustIncludeDiscoveredClasses() throws Exception {
        // 全量运行必须覆盖全部发现的测试类。
        strings(this.manifest, "discoveredClasses").add("test.Third");
        this.assertError("must select every discovered class");
    }

    @Test
    public void filteredRunCanSelectFewerClasses() throws Exception {
        // 用户明确筛选时允许只执行发现集合中的一部分。
        strings(this.manifest, "discoveredClasses").add("test.Third");
        this.manifest.put("userIncludes", list("test.First", "test.Second"));
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void rpcRunCanSelectFewerDiscoveredClasses() throws Exception {
        // RPC 声明只选择两个类，另一个已发现类属于其它协议。
        strings(this.manifest, "discoveredClasses").add("test.Third");
        this.declareRpcSelection();

        // 协议子集不需要冒充整轮类覆盖，真实结果仍全部验证。
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void emptyRpcGroupIsAcceptedWithExplicitSelectionEvidence() throws Exception {
        // 第一组的参数在展开后全部被排除，第二组仍有实际用例。
        this.declareRpcSelection();
        this.emptyRpcGroup("g1");

        // 被排除的参数不伪造成跳过或未执行用例。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("aggregationErrors"));
        assertEquals(1, summary.get("passed"));
        assertEquals(0, summary.get("skipped"));
        assertEquals(0, summary.get("notExecuted"));
        assertEquals(false, summary.get("notExecutedUnknown"));
    }

    @Test
    public void rpcGroupCanExcludeOneAssignedClassAfterParameterExpansion() throws Exception {
        // 同一组粗选两个类，第三个类没有匹配当前 RPC 的实际参数。
        for (String field : List.of("discoveredClasses", "selectedClasses", "selectedSelections")) {
            strings(this.manifest, field).add("test.Third");
        }
        strings(this.group(0), "classes").add("test.Third");
        strings(this.group(0), "filters").add("test.Third");
        List<String> filters = strings(this.group(0), "filters");
        this.update("g1", "configuration.json", map("classNames", list("test.First", "test.Third"), //
                "includes", filters));
        this.update("g1", "summary.json", map("includes", filters));
        this.declareRpcSelection();

        // 已声明的叶子计划保留实际执行类，不要求排除的类生成假结果。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("aggregationErrors"));
        assertEquals(2, summary.get("classes"));
    }

    @Test
    public void anEntirelyEmptyRpcRunIsRejected() throws Exception {
        // 所有候选组都在参数展开后成为明确的零选择。
        this.declareRpcSelection();
        this.emptyRpcGroup("g1");
        this.emptyRpcGroup("g2");

        // 整轮必须至少执行一个真实用例。
        this.assertError("No testcases were executed");
    }

    @Test
    public void emptyRpcGroupWithoutSelectionEmptyEvidenceIsRejected() throws Exception {
        // 缺少明确的筛除证据不能被解释为正常的协议子集。
        this.declareRpcSelection();
        this.emptyRpcGroup("g1");
        this.update("g1", "summary.json", map("selectionEmpty", false));

        // 没有结果仍然报告错误，不能把 JVM 失联当成零匹配。
        this.assertError("has no current results");
    }

    @Test
    public void emptyRpcXmlCannotReplaceExplicitZeroSelectionEvidence() throws Exception {
        // 空 XML 套件不能自行证明参数已经被 RPC 筛除。
        this.declareRpcSelection();
        this.emptyRpcGroup("g1");
        Path current = this.root.resolve("g1/test-results/test/TEST-test.First.xml");
        this.writeText(current, "<testsuite name=\"test.First\" tests=\"0\"/>");
        Path archived = this.report("g1").resolve("test-results/TEST-test.First.xml");
        Files.createDirectories(archived.getParent());
        Files.copy(current, archived);
        this.update("g1", "summary.json", map("selectionEmpty", false, "resultsAvailable", true));

        // 即使套件、计数和文件一致，也必须明确提供零选择证据。
        this.assertError("empty RPC result has no explicit zero selection evidence");
    }

    @Test
    public void emptySelectionEvidenceCannotRelaxAnAllRun() throws Exception {
        // 全量运行不能借用 RPC 专属的零选择标记。
        this.emptyRpcGroup("g1");

        // 全量覆盖和结果要求保持不变。
        this.assertError("cannot claim an empty selection without RPC filtering");
    }

    @Test
    public void rpcGroupWithoutDeclarationsIsRejected() throws Exception {
        // 清单包含协议选择，工作组却丢失了原始声明。
        this.declareRpcSelection();
        this.group(0).remove("rpcCases");

        // 运行范围必须与实际下发的声明一致。
        this.assertError("changed its RPC declarations");
    }

    @Test
    public void rpcGroupWithAChangedVersionIsRejected() throws Exception {
        // 工作组实际执行另一个线协议版本，不能借用当前范围的结果。
        this.declareRpcSelection();
        this.update("g1", "summary.json", map("rpcVersion", "1.1"));

        // 版本来自本轮清单与执行配置的交叉核验。
        this.assertError("changed the rpcVersion selection");
    }

    @Test
    public void rpcGroupWithChangedDeclarationsIsRejected() throws Exception {
        // 执行配置不能把参数通配范围缩小后仍报告原始范围。
        this.declareRpcSelection();
        Map<String, Object> changed = map("test.First", map("first", //
                list(map("name", "soap", "version", "1.2", "cases", "other"))));
        this.update("g1", "configuration.json", map("rpcCases", changed));

        // 声明快照必须保持一致。
        this.assertError("changed its RPC declarations");
    }

    @Test
    public void rpcGroupWithoutAnExplicitLeafPlanIsRejected() throws Exception {
        // RPC 范围不能只依赖成功 XML 来推测计划。
        this.declareRpcSelection();
        Path summaryPath = this.report("g1").resolve("summary.json");
        Map<String, Object> summary = readMap(summaryPath);
        summary.remove("plannedCases");
        NxnReport.writeJson(summaryPath, summary);

        // 缺少执行前计划时不放宽类与方法覆盖。
        this.assertError("RPC selection has no explicit testcase plan");
    }

    @Test
    public void rpcGroupCannotExecuteCasesAbsentFromItsLeafPlan() throws Exception {
        // 已执行 XML 没有变化，但执行前计划中没有这项用例。
        this.declareRpcSelection();
        this.update("g1", "summary.json", map("plannedCases", list()));

        // 真实执行不能越过已声明的 RPC 范围。
        this.assertError("executed testcases outside its RPC selection plan");
    }

    @Test
    public void matchingRpcPlanAndXmlCannotBypassTheParameterDeclaration() throws Exception {
        // 计划与 XML 相互一致，但实际参数属于 SOAP 1.1 向量。
        this.declareRpcSelection();
        this.firstRpcDeclaration(map("name", "soap", "version", "1.2", "cases", "soap12/*"));
        this.rewriteFirstRpcCase("first[soap11/x]");

        // 聚合器直接核验注解声明，不把两份相同的错误证据视为范围正确。
        this.assertError("testcase does not match its RPC selection: test.First#first[soap11/x]");
    }

    @Test
    public void rpcPlanLeavesAreCheckedEvenWhenTheXmlMatchesTheDeclaration() throws Exception {
        // 已执行向量合法，计划另行混入一个未获准的参数。
        this.declareRpcSelection();
        this.firstRpcDeclaration(map("name", "soap", "version", "1.2", "cases", "soap12/*"));
        this.rewriteFirstRpcCase("first[soap12/x]");
        String unexpected = "test.First#first[soap11/x]";
        this.update("g1", "summary.json", map("plannedCases", list(unexpected), //
                "notExecutedCases", list(unexpected), "notExecuted", 1));

        // 未执行的计划叶子同样必须属于 RPC 声明范围。
        this.assertError("testcase does not match its RPC selection: " + unexpected);
    }

    @Test
    public void rpcXmlLeavesAreCheckedEvenWhenThePlanMatchesTheDeclaration() throws Exception {
        // 计划向量合法，XML 却记录了另一个协议参数。
        this.declareRpcSelection();
        this.firstRpcDeclaration(map("name", "soap", "version", "1.2", "cases", "soap12/*"));
        this.rewriteFirstRpcCase("first[soap11/x]");
        String planned = "test.First#first[soap12/x]";
        this.update("g1", "summary.json", map("plannedCases", list(planned), //
                "notExecutedCases", list(planned), "notExecuted", 1));

        // 实际叶子独立核验，不能仅靠合法的计划声明通过。
        this.assertError("testcase does not match its RPC selection: test.First#first[soap11/x]");
    }

    @Test
    public void rpcLeavesWithoutADeclaredMethodAreReportedAsUnknown() throws Exception {
        // 类过滤器允许方法进入，协议声明中却没有该方法。
        this.declareRpcSelection();
        this.rewriteFirstRpcCase("other[vector]");

        // 未知方法与已知方法的参数不匹配分别报告。
        this.assertError("has an unknown RPC testcase: test.First#other[vector]");
    }

    @Test
    public void rpcLeafFromAnotherProtocolCannotBorrowTheGroupCatalog() throws Exception {
        // 第一组声明含两个协议，目录可用不能证明实际叶子的协议相符。
        this.declareRpcSelection();
        Map<String, Object> selected = map("name", "soap", "version", "1.2", "cases", "other/*");
        Map<String, Object> wrong = map("name", "jsonrpc", "version", "2.0", "cases", "*");
        this.firstRpcDeclaration(selected);
        Map<String, Object> methods = child(child(this.manifest, "rpcCases"), "test.First");
        entries(methods, "first").add(wrong);
        this.firstRpcDeclarationValues(methods);

        // 实际 SOAP 范围不接受只匹配 JSON-RPC 声明的参数。
        this.assertError("testcase does not match its RPC selection: test.First#first[vector]");
    }

    @Test
    public void rpcLeafFromAnotherVersionCannotBorrowTheGroupCatalog() throws Exception {
        // 同一方法可声明两个版本，但当前参数只属于旧版本。
        this.declareRpcSelection();
        Map<String, Object> selected = map("name", "soap", "version", "1.2", "cases", "soap12/*");
        Map<String, Object> wrong = map("name", "soap", "version", "1.1", "cases", "*");
        this.firstRpcDeclarationValues(map("first", list(selected, wrong)));

        // 参数合法性同时受到协议名、版本与显示名称的约束。
        this.assertError("testcase does not match its RPC selection: test.First#first[vector]");
    }

    @Test
    public void rpcParameterNamesPreserveTheirInnerBrackets() throws Exception {
        // 显示名称自身带方括号，契约选择按完整原始名称匹配。
        this.declareRpcSelection();
        this.firstRpcDeclaration(map("name", "soap", "version", "1.2", "cases", "soap12/x[value]"));
        this.rewriteFirstRpcCase("first[soap12/x[value]]");

        // 外层参数包装被移除一次，名称内部内容保持不变。
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void ordinaryRpcMethodsMatchAnEmptyParameterName() throws Exception {
        // 普通 Jupiter 方法没有参数类显示名称。
        this.declareRpcSelection();
        this.firstRpcDeclaration(map("name", "soap", "version", "1.2", "cases", ""));
        this.rewriteFirstRpcCase("first()");

        // 同一选择器使用空参数名称验证普通方法。
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void emptyRpcSelectionCannotContainExecutedCases() throws Exception {
        // 零选择标记与实际成功用例冲突。
        this.declareRpcSelection();
        this.update("g1", "summary.json", map("selectionEmpty", true, "plannedCases", list(), //
                "resultsAvailable", false));

        // 真实 XML 不会因零选择标记被丢弃。
        Map<String, Object> summary = this.aggregate();
        assertEquals(2, summary.get("tests"));
        assertTrue(containsError(strings(summary, "aggregationErrors"), "claims an empty RPC selection"));
    }

    @Test
    public void emptyRpcSelectionCannotHideExecutionFailure() throws Exception {
        // 筛除参数后若启动或发现失败，仍然是测试错误。
        this.declareRpcSelection();
        this.emptyRpcGroup("g1");
        this.update("g1", "summary.json", map("taskFailure", "discovery failed"));

        // 正常零匹配不得掩盖执行异常。
        this.assertError("test execution failed: discovery failed");
    }

    @Test
    public void emptyRpcSelectionStillRequiresProcessEvidence() throws Exception {
        // 参数全部筛除不免除进程退出和资源释放证据。
        this.declareRpcSelection();
        this.emptyRpcGroup("g1");
        Files.delete(this.root.resolve("g1/process/status.json"));

        // 丢失进程状态仍然阻止成功汇总。
        this.assertError("process evidence is invalid");
    }

    @Test
    public void rpcMarkdownNamesTheProtocolVersionAndCaseFilter() throws Exception {
        // 协议版本与显式方法选择共同限定本轮范围。
        this.declareRpcSelection();
        this.manifest.put("userIncludes", list("test.First", "test.Second"));
        this.aggregate();

        // 报告清楚表达子集，不能宣称已经执行全部协议。
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("RPC：`soap`，协议版本：`1.2`。"));
        assertTrue(markdown.contains("用例筛选：`test.First`、`test.Second`。"));
        assertFalse(markdown.contains("用例筛选：全部"));
    }

    @Test
    public void rpcMarkdownWithoutCaseFiltersNamesTheDeclaredScope() throws Exception {
        // 协议选择未叠加 --tests，范围仍然只属于该协议。
        this.declareRpcSelection();
        this.aggregate();

        // 不把协议声明范围写成整个测试工程的全量。
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertTrue(markdown.contains("用例筛选：该协议声明范围。"));
    }

    @Test
    public void splitParameterizedClassIsMerged() throws Exception {
        // 同一参数化类按方法分组后仍合并为一个 XML suite。
        this.splitClass("consumer[vector]");
        this.aggregate();
        Path xml = this.build.resolve("test-results/test/TEST-test.Matrix.xml");
        Element suite = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.toFile()) //
                .getDocumentElement();
        NodeList cases = suite.getElementsByTagName("testcase");
        List<String> names = new ArrayList<>();
        for (int index = 0; index < cases.getLength(); index++) {
            names.add(((Element) cases.item(index)).getAttribute("name"));
        }
        assertEquals(list("provider[vector]", "consumer[vector]"), names);
    }

    @Test
    public void duplicateCaseIdentityIsRejected() throws Exception {
        // 分组后的参数化用例不能拥有重复身份。
        this.splitClass("provider[vector]");
        this.assertError("Duplicate testcase identities");
    }

    @Test
    public void unassignedMethodIsRejected() throws Exception {
        // 参数化类中未分配的方法不能混入工作组结果。
        this.splitClass("other[vector]");
        this.assertError("executed an unassigned testcase");
    }

    @Test
    public void missingAssignedMethodIsRejected() throws Exception {
        // 工作组必须实际执行分配给它的方法。
        this.splitClass("other[vector]");
        this.assertError("did not execute its selection: test.Matrix.consumer");
    }

    @Test
    public void methodWildcardPreservesParameterizedMatching() {
        // 方法通配符可以匹配带 Unicode 参数显示名称的用例。
        Map<String, Object> entry = map("className", "test.Matrix", "scenario", "provider[unicode 值]");
        assertTrue(NxnReport.matchesSelection(entry, "test.Matrix.provider*"));
    }

    @Test
    public void methodParenthesesDoNotChangeMatching() {
        // XML 中的方法括号不改变无参方法筛选的语义。
        Map<String, Object> entry = map("className", "test.Matrix", "scenario", "provider()");
        assertTrue(NxnReport.matchesSelection(entry, "test.Matrix.provider"));
    }

    @Test
    public void exactParameterNameIsMatchedLiterally() {
        // 精确参数名中的方括号按文本解释。
        Map<String, Object> entry = map("className", "test.Matrix", "scenario", "provider[unicode 值]");
        assertTrue(NxnReport.matchesSelection(entry, "test.Matrix.provider[unicode 值]"));
    }

    @Test
    public void differentParameterNameIsNotMatched() {
        // 一个参数向量不能匹配另一个向量的精确选择。
        Map<String, Object> entry = map("className", "test.Matrix", "scenario", "provider[first]");
        assertFalse(NxnReport.matchesSelection(entry, "test.Matrix.provider[second]"));
    }

    @Test
    public void launcherIncludesCanDifferFromLogicalFilters() throws Exception {
        // 启动器可以把逻辑方法选择展开为参数化通配表达式。
        this.splitClass("consumer[vector]");
        for (Map<String, Object> group : entries(this.manifest, "groups")) {
            List<String> filters = strings(group, "filters");
            List<String> includes = list(filters.get(0) + "*");
            group.put("includes", includes);
            String identity = (String) group.get("id");
            this.update(identity, "configuration.json", map("includes", includes));
            this.update(identity, "summary.json", map("includes", includes));
        }
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void failedCasePreventsSuccessfulAggregate() throws Exception {
        // 工作组中的失败用例必须阻止全量成功。
        this.replaceFirstGroup("failed");
        this.assertError("contains failed or skipped");
    }

    @Test
    public void failedCaseSummaryIncludesItsIdentityAndCause() throws Exception {
        // 最终摘要直接列出失败用例，外层报告无需再次解释原始 XML。
        this.replaceFirstGroup("failed");

        // 用例身份及完整原因共同说明这一次失败。
        List<Map<String, Object>> failures = entries(this.aggregate(), "failedCases");
        Map<String, Object> expected = map("caseId", "test.First#first[vector]", //
                "failure", "failed assertion", "groupId", "g1", "phase", "testing", //
                "logs", map("stdout", "stdout.log", "stderr", "stderr.log", //
                        "suiteStdout", "test-output/test.First.stdout.log", //
                        "suiteStderr", "test-output/test.First.stderr.log"));
        assertEquals(list(expected), failures);
    }

    @Test
    public void failedCaseSummaryPreservesTheCompleteFailure() throws Exception {
        // 长堆栈和多行诊断必须保留到摘要，不截断为控制台提示长度。
        this.replaceFirstGroup("failed");
        String reason = "java.lang.AssertionError: RPC response\n" + "full diagnostic line\n".repeat(2048);
        Path xml = this.root.resolve("g1/test-results/test/TEST-test.First.xml");
        String content = Files.readString(xml).replace("failed assertion", reason);
        Files.writeString(xml, content, StandardCharsets.UTF_8);
        Files.copy(xml, this.report("g1").resolve("test-results/TEST-test.First.xml"), //
                StandardCopyOption.REPLACE_EXISTING);

        // 汇总原样保留这份失败正文。
        List<Map<String, Object>> failures = entries(this.aggregate(), "failedCases");
        assertEquals(reason, failures.get(0).get("failure"));
    }

    @Test
    public void markdownFailureExpandsTabsWithoutChangingRawEvidence() throws Exception {
        // 异常堆栈包含制表符、行尾空格及空白行，原始证据必须保持完整。
        this.replaceFirstGroup("failed");
        String reason = "java.lang.AssertionError: failure\n\tat peer.Main.call(Main.java:10) \n\t\n";
        Path xml = this.root.resolve("g1/test-results/test/TEST-test.First.xml");
        String content = Files.readString(xml).replace("failed assertion", reason);
        Files.writeString(xml, content, StandardCharsets.UTF_8);
        Files.copy(xml, this.report("g1").resolve("test-results/TEST-test.First.xml"), //
                StandardCopyOption.REPLACE_EXISTING);

        // 只有 Markdown 展示展开缩进和清除尾部空白，摘要及 XML 仍保留原文。
        Map<String, Object> summary = this.aggregate();
        assertEquals(reason, entries(summary, "failedCases").get(0).get("failure"));
        Map<String, Object> stored = readMap(this.build.resolve("interoperability/summary.json"));
        assertEquals(reason, entries(stored, "failedCases").get(0).get("failure"));
        Path copied = this.build.resolve("test-results/test/TEST-test.First.xml");
        assertEquals(content, Files.readString(copied));
        String markdown = Files.readString(this.build.resolve("interoperability/report.md"));
        assertFalse(markdown.contains("\t"));
        assertTrue(markdown.contains("          at peer.Main.call(Main.java:10)\n\n"));
        assertTrue(markdown.lines().noneMatch(line -> line.endsWith(" ")));
    }

    @Test
    public void executedPlanHasNoUnexecutedCases() throws Exception {
        // 执行前计划与本轮 XML 一致时，不产生未执行记录。
        this.update("g1", "summary.json", map("plannedCases", list("test.First#first[vector]"), //
                "notExecutedCases", list(), "notExecuted", 0));

        // 计划既不增加用例计数，也不改变本轮成功结果。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("aggregationErrors"));
        assertEquals(0, summary.get("notExecuted"));
        assertEquals(list(), summary.get("notExecutedCases"));
    }

    @Test
    public void unexecutedCasesRemainSeparateFromPassedAndSkipped() throws Exception {
        // fail-fast 后的计划叶子没有执行证据，不能伪造为通过或跳过。
        String missing = "test.First#later[vector]";
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", missing), //
                "notExecutedCases", list(missing), "notExecuted", 1));

        // 摘要保留实际执行统计，同时明确列出未执行项并判本轮失败。
        Map<String, Object> summary = this.aggregate();
        assertEquals(2, summary.get("tests"));
        assertEquals(2, summary.get("passed"));
        assertEquals(0, summary.get("skipped"));
        assertEquals(1, summary.get("notExecuted"));
        assertEquals(list(missing), summary.get("notExecutedCases"));
        assertTrue(containsError(strings(summary, "aggregationErrors"), "has unexecuted testcases"));
    }

    @Test
    public void unexecutedCaseListMustMatchPlanAndXml() throws Exception {
        // 声明没有未执行项，不能掩盖计划中缺失的实际执行。
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", "test.First#later[vector]"), //
                "notExecutedCases", list(), "notExecuted", 1));
        this.assertError("notExecutedCases does not match its plan and XML");
    }

    @Test
    public void unexecutedCaseCountMustMatchPlanAndXml() throws Exception {
        // 数量仍需与真实差集核对，不能靠正确的名称列表掩盖错误总数。
        String missing = "test.First#later[vector]";
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", missing), //
                "notExecutedCases", list(missing), "notExecuted", 0));
        this.assertError("summary notExecuted does not match its XML");
    }

    @Test
    public void duplicatePlannedCasesAreRejected() throws Exception {
        // 计划中的同一叶子只能出现一次，不能据此扩大预期用例数量。
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", "test.First#first[vector]"), //
                "notExecutedCases", list(), "notExecuted", 0));
        this.assertError("testcase plan contains duplicate identities");
    }

    @Test
    public void commandLineFailsWhenPlannedCasesWereNotExecuted() throws Exception {
        // 即便现存 XML 全部成功，缺少计划用例也必须在最终返回非零。
        String missing = "test.First#later[vector]";
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", missing), //
                "notExecutedCases", list(missing), "notExecuted", 1));
        assertEquals(1, this.runReport());
    }

    @Test
    public void markdownReportIncludesFailureAndErrorPolicy() throws Exception {
        // 失败报告同时说明本轮策略和真实失败，供归档及总报告直接引用。
        this.manifest.put("skipError", false);
        this.replaceFirstGroup("failed");
        this.aggregate();

        // Markdown 使用完整 caseId 和原因，不依赖外层再次解析 XML。
        Path report = this.build.resolve("interoperability/report.md");
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("结果：失败。"));
        assertTrue(markdown.contains("skipError：`false`。"));
        assertTrue(markdown.contains("`test.First#first[vector]`"));
        assertTrue(markdown.contains("failed assertion"));
        assertTrue(markdown.contains("[summary.json](summary.json)"));
        assertFalse(markdown.contains("current-run"));
    }

    @Test
    public void markdownReportListsUnexecutedCasesSeparately() throws Exception {
        // fail-fast 后的未执行叶子与 JUnit 跳过是两类状态。
        String missing = "test.First#later[vector]";
        this.update("g1", "summary.json", map( //
                "plannedCases", list("test.First#first[vector]", missing), //
                "notExecutedCases", list(missing), "notExecuted", 1));
        this.aggregate();

        // 摘要明确显示已确认的未执行项，跳过数量保持真实值。
        Path report = this.build.resolve("interoperability/report.md");
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("跳过 0 项，已确认未执行 1 项，结果未知 0 项。"));
        assertTrue(markdown.contains("## 未执行用例\n\n- `" + missing + "`"));
        assertTrue(markdown.contains("结果：失败。"));
    }

    @Test
    public void markdownReportIdentifiesUnknownGroupCoverage() throws Exception {
        // 工作组没有生成报告时，只知道分配范围，不能伪造叶子执行情况。
        Files.delete(this.report("g1").resolve("summary.json"));
        Map<String, Object> summary = this.aggregate();

        // 数量未知必须明确标出，不能让已确认未执行为零暗示完整覆盖。
        assertEquals(true, summary.get("notExecutedUnknown"));
        Map<String, Object> group = entries(summary, "groups").get(0);
        assertEquals(list("test.First"), group.get("notExecutedScope"));
        Path report = this.build.resolve("interoperability/report.md");
        String markdown = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("未执行总数不确定。"));
        assertTrue(markdown.contains("`g1`，分配范围：`test.First`。"));
        assertTrue(markdown.contains("## 汇总异常"));
        assertTrue(markdown.contains("结果：失败。"));
    }

    @Test
    public void skippedCasePreventsSuccessfulAggregate() throws Exception {
        // 跳过用例不能计入完整验收。
        this.replaceFirstGroup("skipped");
        this.assertError("contains failed or skipped");
    }

    @Test
    public void releasedRegistryClientIsAccepted() throws Exception {
        // 注册中心客户端释放完成后可以通过清理验证。
        Path lifecycle = this.root.resolve("g1/work/registry-services/nacos/lifecycle.properties");
        this.writeText(lifecycle, "# last JVM shutdown\nstate=released\n");
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void borrowedRegistryClientIsNotCleanupSuccess() throws Exception {
        // JVM 退出后仍处于借用状态表示没有完成释放。
        Path lifecycle = this.root.resolve("g1/work/registry-services/nacos/lifecycle.properties");
        this.writeText(lifecycle, "state=borrowed\n");
        this.assertError("registry client was not released");
    }

    @Test
    public void registryCleanupErrorFileIsRejected() throws Exception {
        // 关闭失败日志不能被已释放标记掩盖。
        Path directory = this.root.resolve("g1/work/registry-services/nacos");
        this.writeText(directory.resolve("lifecycle.properties"), "state=released\n");
        this.writeText(directory.resolve("cleanup-error.log"), "close failed");
        this.assertError("registry cleanup failed");
    }

    @Test
    public void pendingRegistrySnapshotIsValidatedAfterJvmExit() throws Exception {
        // JUnit 结束时待验证的快照由 JVM 退出后的证据补全。
        Map<String, Object> cleanup = map("successful", null, "verificationPending", true);
        this.update("g1", "summary.json", map("registryCleanup", cleanup));
        Path lifecycle = this.root.resolve("g1/container-scenarios/registry-services/nacos/lifecycle.properties");
        this.writeText(lifecycle, "state=released\n");
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void missingRegistryLifecycleIsRejected() throws Exception {
        // 注册中心目录存在时必须有最终生命周期状态。
        Files.createDirectories(this.root.resolve("g1/container-scenarios/registry-services/nacos"));
        this.assertError("registry client was not released");
    }

    @Test
    public void legacyClosedRegistryFixtureIsAccepted() throws Exception {
        // 旧 H2 夹具的 closed 状态仍然代表已经释放。
        Path lifecycle = this.root.resolve("g1/container-scenarios/registry-services/h2/lifecycle.properties");
        this.writeText(lifecycle, "state=closed\n");
        assertEquals(list(), this.aggregate().get("aggregationErrors"));
    }

    @Test
    public void baselineIdentityCoverageIsAccepted() throws Exception {
        // 完整覆盖显式基线的 caseId 集合时允许验收。
        Path baseline = this.root.resolve("baseline.json");
        List<Map<String, Object>> cases = list(map("caseId", "test.First#first[vector]"), //
                map("caseId", "test.Second#second[vector]"));
        NxnReport.writeJson(baseline, cases);
        assertEquals(list(), this.aggregate(baseline).get("aggregationErrors"));
    }

    @Test
    public void baselineMissingIdentityIsRejected() throws Exception {
        // 基线存在本轮没有执行的用例时必须失败。
        Path baseline = this.root.resolve("baseline.json");
        List<Map<String, Object>> cases = list(map("caseId", "test.First#first[vector]"), //
                map("caseId", "test.Second#second[vector]"), map("caseId", "test.Missing#case"));
        NxnReport.writeJson(baseline, cases);
        List<String> errors = strings(this.aggregate(baseline), "aggregationErrors");
        assertTrue(containsError(errors, "supplied baseline"));
    }

    @Test
    public void baselineIsNotAppliedToFilteredRun() throws Exception {
        // 基线验收不能与局部筛选混用。
        this.manifest.put("userIncludes", list("test.First", "test.Second"));
        Path baseline = this.root.resolve("baseline.json");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.aggregate(baseline));
        assertTrue(failure.getMessage().contains("unfiltered run"));
    }

    @Test
    public void baselineIsNotAppliedToRpcRun() throws Exception {
        // 全量基线不能用来评价指定 RPC 子集。
        this.declareRpcSelection();
        Path baseline = this.root.resolve("baseline.json");

        // 提前拒绝比较，避免把其它协议记为丢失用例。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> this.aggregate(baseline));
        assertTrue(failure.getMessage().contains("unfiltered run"));
    }

    @Test
    public void archivedGroupsResolveContainerPathsWithoutTheWorkspace() throws Exception {
        // 归档保留容器内原路径，宿主只拥有相邻的分组证据。
        this.remotePeer(map(), false);
        Path containerGroups = Path.of("/workspace/meteor/meteor-test/build/groups");
        this.archiveFixture(containerGroups);
        assertFalse(Files.exists(containerGroups.resolve("g1")));

        // 对端 launchFile 的旧前缀同样映射到当前归档，离线汇总仍完整。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("aggregationErrors"));
        assertEquals(2, summary.get("tests"));
    }

    @Test
    public void archivedGroupsNeverReadAnExistingOldWorkspace() throws Exception {
        // 归档完成后故意在原路径写入另一轮的损坏证据。
        this.remotePeer(map(), false);
        this.archiveFixture(this.root);
        Path staleReport = this.root.resolve("g1/interoperability/summary.json");
        this.writeText(staleReport, "not current JSON");
        Path staleProcess = this.root.resolve("g1/process-evidence/peer-one/status.json");
        this.writeText(staleProcess, "not current process status");

        // 即便旧目录仍然存在，也只读取归档清单相邻的工作组目录。
        Map<String, Object> summary = this.aggregate();
        assertEquals(list(), summary.get("aggregationErrors"));
        Map<String, Object> first = entries(summary, "groups").get(0);
        Path archivedGroup = this.manifestPath.getParent().resolve("g1");
        assertEquals(archivedGroup.toString(), first.get("outputDirectory"));
    }

    @Test
    public void orchestrationErrorsRemainInTheArchivedSummary() throws Exception {
        // 编排失败即便发生在用例通过之后，也必须进入最终验收结果。
        String error = "Cannot stop the owned remote program";
        this.manifest.put("orchestrationErrors", list(error));
        Map<String, Object> summary = this.aggregate();
        assertTrue(strings(summary, "aggregationErrors").contains(error));
        assertTrue(((String) summary.get("taskFailure")).contains(error));

        // 落盘摘要保存相同失败原因，离线阅读不会丢失编排错误。
        Path archived = this.build.resolve("interoperability/summary.json");
        Map<String, Object> stored = readMap(archived);
        assertEquals(summary.get("aggregationErrors"), stored.get("aggregationErrors"));
        assertEquals(summary.get("taskFailure"), stored.get("taskFailure"));
    }

    @Test
    public void commandLineRejectsMissingRequiredArguments() {
        // 命令入口缺少输出目录时直接返回失败，不能开始聚合。
        String[] arguments = { "--manifest", this.manifestPath.toString() };
        assertEquals(1, NxnReport.run(arguments));
    }

    @Test
    public void commandLineRejectsUnknownOptions() {
        // 未知参数不能被忽略并意外启动一轮报告。
        String[] arguments = { "--unknown", "value" };
        assertEquals(1, NxnReport.run(arguments));
    }

    @Test
    public void commandLineAcceptsACompleteCurrentRun() throws Exception {
        // 合法清单通过公开命令入口完成聚合并返回零。
        assertEquals(0, this.runReport());
    }

    @Test
    public void commandLineReturnsFailureForAnUnsuccessfulGroup() throws Exception {
        // 工作组失败需要传递到命令退出码，而不是只写入报告。
        this.group(0).put("exitCode", 1);
        assertEquals(1, this.runReport());
    }

    private void archiveFixture(Path declaredGroups) throws Exception {
        // 模拟 Docker 归档，只迁移本轮工作组文件，清单保留容器内声明路径。
        Path archiveGroups = this.root.resolve("archive/groups");
        Files.createDirectories(archiveGroups);
        for (Map<String, Object> group : entries(this.manifest, "groups")) {
            String identity = (String) group.get("id");
            Path source = this.root.resolve(identity);
            Path declared = declaredGroups.resolve(identity);
            for (Map<String, Object> process : entries(child(group, "remoteCleanup"), "processes")) {
                Path launch = Path.of((String) process.get("launchFile"));
                process.put("launchFile", declared.resolve(source.relativize(launch)).toString());
            }
            Files.move(source, archiveGroups.resolve(identity));
            group.put("outputDirectory", declared.toString());
        }
        this.manifestPath = archiveGroups.resolve("manifest.json");
    }

    private int runReport() throws Exception {
        NxnReport.writeJson(this.manifestPath, this.manifest);
        String[] arguments = { "--manifest", this.manifestPath.toString(), //
                "--build-directory", this.build.toString() };
        return NxnReport.run(arguments);
    }

    private void addGroup(String identity, String className, String scenario, String status, String selection) throws Exception {
        // 清单、原始 XML、逻辑报告和进程证据共同描述同一次执行。
        Path directory = this.root.resolve(identity);
        List<String> filters = list(selection == null ? className : selection);
        Map<String, Object> cleanup = map("checked", 0, "stopped", 0, "successful", true, //
                "processes", list(), "failures", list());
        Map<String, Object> group = map("id", identity, "classes", list(className), "filters", filters, //
                "outputDirectory", directory.toString(), "exitCode", 0, "cleanupFailures", list(), //
                "remoteCleanup", cleanup);
        entries(this.manifest, "groups").add(group);
        Path report = this.report(identity);
        Path result = directory.resolve("test-results/test/TEST-" + className + ".xml");
        String caseResult = "";
        boolean failed = StringUtils.equals("failed", status);
        boolean skipped = StringUtils.equals("skipped", status);
        if (failed) {
            caseResult = "<failure>failed assertion</failure>";
        } else if (skipped) {
            caseResult = "<skipped/>";
        }
        String xml = "<testsuite name=\"" + className + "\" tests=\"1\" time=\"2.5\">" //
                + "<testcase classname=\"" + className + "\" name=\"" + scenario + "\" time=\"2.5\">" //
                + caseResult + "</testcase></testsuite>";
        this.writeText(result, xml);
        Files.createDirectories(report.resolve("test-results"));
        Files.copy(result, report.resolve("test-results/" + result.getFileName()));

        // 逻辑报告保留本轮身份与启动过滤器，聚合时与原始 XML 逐项核对。
        Map<String, Object> entry = map("caseId", className + "#" + scenario, "className", className, //
                "scenario", scenario, "seconds", "2.5", "status", status);
        Map<String, Object> common = map("runId", "current-run-" + identity, //
                "orchestrationRunId", "current-run", "groupId", identity, "includes", filters);
        Map<String, Object> configuration = new LinkedHashMap<>(common);
        configuration.put("classNames", list(className));
        configuration.put("matrixSelection", map());
        NxnReport.writeJson(report.resolve("configuration.json"), configuration);
        NxnReport.writeJson(report.resolve("selected-cases.json"), list(entry));
        Map<String, Object> summary = new LinkedHashMap<>(common);
        summary.putAll(map("tests", 1, "passed", failed || skipped ? 0 : 1, "failed", failed ? 1 : 0, //
                "skipped", skipped ? 1 : 0, "testTaskStarted", true, "resultsAvailable", true, //
                "reusedResults", false, "taskFailure", null));
        NxnReport.writeJson(report.resolve("summary.json"), summary);
        this.addProcess(directory.resolve("process"), identity, identity + "-jvm");
    }

    private Map<String, Object> declareMatrix(String groupId, String caseId, String provider, String consumer) throws Exception {
        // 能力清单可包含进程配置，报告结果只能保留明确允许的双方身份字段。
        Map<String, Object> endpoint = map("id", "provider-" + provider, "language", provider, //
                "implementation", "Provider SDK", "version", "1.0", //
                "command", list("java", "peer.Main"), "environment", map("PRIVATE_TOKEN", "not-result-data"));
        Map<String, Object> peer = map("id", "consumer-" + consumer, "language", consumer, //
                "implementation", "Consumer SDK", "version", "2.0");
        Map<String, Object> testCase = map("caseId", caseId, "id", "vector", "api", "order-v1", //
                "scenario", "save-find", "protocol", "dubbo", "protocolVersion", "", //
                "protocolVersions", list("2", "3"), "wire", "dubbo-hessian2-tcp-clear", "discovery", "direct", //
                "provider", endpoint, "consumer", peer);
        this.manifest.computeIfAbsent("matrixCases", ignored -> new ArrayList<>());
        entries(this.manifest, "matrixCases").add(testCase);
        Map<String, Object> group = entries(this.manifest, "groups").stream() //
                .filter(entry -> StringUtils.equals(groupId, (String) entry.get("id"))).findFirst().orElseThrow();
        group.computeIfAbsent("matrixCases", ignored -> new ArrayList<>());
        entries(group, "matrixCases").add(new LinkedHashMap<>(testCase));

        // 每组显式持有其预定叶子，实际 XML 之外的计划项保持未执行。
        List<String> planned = new ArrayList<>();
        entries(group, "matrixCases").forEach(entry -> planned.add((String) entry.get("caseId")));
        List<String> executed = new ArrayList<>();
        readList(this.report(groupId).resolve("selected-cases.json")) //
                .forEach(entry -> executed.add((String) entry.get("caseId")));
        List<String> missing = new ArrayList<>(planned);
        missing.removeAll(executed);
        this.update(groupId, "summary.json", map("matrixCases", entries(group, "matrixCases"), //
                "plannedCases", planned, "notExecutedCases", missing, "notExecuted", missing.size(), //
                "unknownCases", list(), "unknown", 0));
        return testCase;
    }

    private void declareRpcSelection() throws Exception {
        // 声明同一线协议版本，测试计划来自已有的真实 XML 夹具。
        Map<String, Object> declarations = new LinkedHashMap<>();
        for (String className : strings(this.manifest, "selectedClasses")) {
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            String methodName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
            Map<String, Object> declaration = map("name", "soap", "version", "1.2", "cases", "*");
            declarations.put(className, map(methodName, list(declaration)));
        }
        this.manifest.putAll(map("rpc", "soap", "rpcVersion", "1.2", "rpcCases", declarations));
        for (Map<String, Object> group : entries(this.manifest, "groups")) {
            Map<String, Object> assigned = new LinkedHashMap<>();
            for (String className : strings(group, "classes")) {
                assigned.put(className, declarations.get(className));
            }
            Map<String, Object> selection = map("rpc", "soap", "rpcVersion", "1.2", "rpcCases", assigned);
            group.putAll(selection);
            String identity = (String) group.get("id");
            this.update(identity, "configuration.json", selection);
            List<String> planned = new ArrayList<>();
            Path selected = this.report(identity).resolve("selected-cases.json");
            for (Map<String, Object> testcase : readList(selected)) {
                planned.add((String) testcase.get("caseId"));
            }
            Map<String, Object> summary = new LinkedHashMap<>(selection);
            summary.putAll(map("plannedCases", planned, "notExecutedCases", list(), //
                    "notExecuted", 0, "selectionEmpty", false));
            this.update(identity, "summary.json", summary);
        }
    }

    private void emptyRpcGroup(String identity) throws Exception {
        // 正常筛除参数不会产生 XML、跳过项或失败项，保留进程清理证据。
        deleteTree(this.root.resolve(identity).resolve("test-results/test"));
        deleteTree(this.report(identity).resolve("test-results"));
        NxnReport.writeJson(this.report(identity).resolve("selected-cases.json"), list());
        this.update(identity, "summary.json", map("tests", 0, "passed", 0, "failed", 0, "skipped", 0, //
                "selectionEmpty", true, "resultsAvailable", false, "plannedCases", list(), //
                "notExecutedCases", list(), "notExecuted", 0));
    }

    private void firstRpcDeclaration(Map<String, Object> declaration) throws Exception {
        this.firstRpcDeclarationValues(map("first", list(declaration)));
    }

    private void firstRpcDeclarationValues(Map<String, Object> methods) throws Exception {
        // 四份范围声明保持一致，使断言只验证叶子与声明的语义匹配。
        child(this.manifest, "rpcCases").put("test.First", methods);
        Map<String, Object> assigned = child(this.group(0), "rpcCases");
        assigned.put("test.First", methods);
        this.update("g1", "configuration.json", map("rpcCases", assigned));
        this.update("g1", "summary.json", map("rpcCases", assigned));
    }

    private void rewriteFirstRpcCase(String scenario) throws Exception {
        // 原始 XML、归档 XML 与选中项同步更新，避免其它完整性校验先行失败。
        Path selected = this.report("g1").resolve("selected-cases.json");
        List<Map<String, Object>> cases = readList(selected);
        Map<String, Object> testcase = cases.get(0);
        String previous = (String) testcase.get("scenario");
        Path current = this.root.resolve("g1/test-results/test/TEST-test.First.xml");
        String xml = Files.readString(current).replace("name=\"" + previous + "\"", "name=\"" + scenario + "\"");
        this.writeText(current, xml);
        Path archived = this.report("g1").resolve("test-results/TEST-test.First.xml");
        Files.copy(current, archived, StandardCopyOption.REPLACE_EXISTING);
        String caseId = "test.First#" + scenario;
        testcase.put("scenario", scenario);
        testcase.put("caseId", caseId);
        NxnReport.writeJson(selected, cases);
        this.update("g1", "summary.json", map("plannedCases", list(caseId)));
    }

    private void addSdkGroup(String identity, String parameter, String status) throws Exception {
        this.addSdkMethodGroup(identity, "call", parameter, status);
    }

    private void addSdkMethodGroup(String identity, String method, String parameter, String status) throws Exception {
        // 原始 XML 和计划清单同时登记实际参数化用例，不绕过报告证据校验。
        String className = "test.Sdk" + identity;
        for (String field : List.of("discoveredClasses", "selectedClasses", "selectedSelections")) {
            strings(this.manifest, field).add(className);
        }
        this.addGroup(identity, className, method + "[" + parameter + "]", status, null);
    }

    private void addProcess(Path directory, String groupId, String processId) throws Exception {
        // 启动和退出记录使用相同身份，输出日志单独归档。
        Map<String, Object> launch = map("processId", processId, "runId", "current-run", "groupId", groupId, //
                "command", list("java", "Main"));
        NxnReport.writeJson(directory.resolve("launch.json"), launch);
        Map<String, Object> status = map("processId", processId, "runId", "current-run", "groupId", groupId, //
                "state", "exited", "exitCode", 0, "rawExitCode", 0, "processGroupEmpty", true, //
                "remainingPids", list(), "temporaryDirectoryRemoved", true);
        NxnReport.writeJson(directory.resolve("status.json"), status);
        Files.createFile(directory.resolve("stdout.log"));
        Files.createFile(directory.resolve("stderr.log"));
    }

    private Path report(String identity) {
        return this.root.resolve(identity).resolve("interoperability");
    }

    private Map<String, Object> group(int index) {
        return entries(this.manifest, "groups").get(index);
    }

    private void update(String group, String filename, Map<String, Object> values) throws Exception {
        Path path = this.report(group).resolve(filename);
        Map<String, Object> current = readMap(path);
        current.putAll(values);
        NxnReport.writeJson(path, current);
    }

    private void updateProcess(Map<String, Object> values) throws Exception {
        Path path = this.root.resolve("g1/process/status.json");
        Map<String, Object> current = readMap(path);
        current.putAll(values);
        NxnReport.writeJson(path, current);
    }

    private Map<String, Object> remotePeer(Map<String, Object> statusUpdates, boolean requiredStop) throws Exception {
        // 对端清理记录引用真实的启动与最终退出文件。
        Path directory = this.root.resolve("g1/process-evidence/peer-one");
        this.addProcess(directory, "g1", "peer-one");
        Map<String, Object> status = readMap(directory.resolve("status.json"));
        status.putAll(statusUpdates);
        NxnReport.writeJson(directory.resolve("status.json"), status);
        Map<String, Object> process = map("processId", "peer-one", "container", "python-service", //
                "launchFile", directory.resolve("launch.json").toString(), "requiredStop", requiredStop, //
                "before", null, "after", status, "failures", list());
        Map<String, Object> cleanup = map("checked", 1, "stopped", requiredStop ? 1 : 0, "successful", true, //
                "processes", list(process), "failures", list());
        this.group(0).put("remoteCleanup", cleanup);
        return cleanup;
    }

    private Map<String, Object> aggregate() throws Exception {
        return this.aggregate(null);
    }

    private Map<String, Object> aggregate(Path baseline) throws Exception {
        NxnReport.writeJson(this.manifestPath, this.manifest);
        return NxnReport.aggregate(this.manifestPath, this.build, baseline);
    }

    private void assertError(String text) throws Exception {
        List<String> errors = strings(this.aggregate(), "aggregationErrors");
        assertTrue(containsError(errors, text), text + ": " + errors);
    }

    private static boolean containsError(List<String> errors, String text) {
        for (String error : errors) {
            if (error.contains(text)) {
                return true;
            }
        }
        return false;
    }

    private void splitClass(String second) throws Exception {
        // 保留同一个类的两个方法选择，验证跨工作组合并和方法覆盖。
        deleteTree(this.root.resolve("g1"));
        deleteTree(this.root.resolve("g2"));
        this.manifest.put("discoveredClasses", list("test.Matrix"));
        this.manifest.put("selectedClasses", list("test.Matrix"));
        this.manifest.put("selectedSelections", list("test.Matrix.provider", "test.Matrix.consumer"));
        this.manifest.put("groups", list());
        this.addGroup("g1", "test.Matrix", "provider[vector]", "passed", "test.Matrix.provider");
        this.addGroup("g2", "test.Matrix", second, "passed", "test.Matrix.consumer");
    }

    private void replaceFirstGroup(String status) throws Exception {
        deleteTree(this.root.resolve("g1"));
        entries(this.manifest, "groups").remove(0);
        this.addGroup("g1", "test.First", "first[vector]", status, null);
    }

    private void writeText(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path directory) throws Exception {
        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> deletion = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : deletion) {
                Files.delete(path);
            }
        }
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    @SafeVarargs
    private static <T> List<T> list(T... values) {
        // 参数顺序用于用例覆盖及 XML 合并顺序，夹具保持调用处的顺序。
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(Path path) throws Exception {
        return (Map<String, Object>) NxnReport.readJson(path);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> readList(Path path) throws Exception {
        return (List<Map<String, Object>>) NxnReport.readJson(path);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> source, String key) {
        return (List<Map<String, Object>>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Map<String, Object> source, String key) {
        return (List<String>) source.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> source, String key) {
        return (Map<String, Object>) source.get(key);
    }
}
