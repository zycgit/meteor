/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.realmeteor.environment.execution;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.platform.engine.support.descriptor.MethodSource.from;

/** 独立 JUnit 入口的选择、原始报告和失败路径，不启动协议或 Docker。 */
public class JUnitJobMainTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir
    public Path temporary;
    private final String previousWorkDirectory = System.getProperty("meteor.test.container.workDirectory");

    @AfterEach
    public void restoreFixtureDirectory() {
        // 测试只能恢复自己修改的属性，不能影响随后执行的真实注册场景。
        if (this.previousWorkDirectory == null) {
            System.clearProperty("meteor.test.container.workDirectory");
        } else {
            System.setProperty("meteor.test.container.workDirectory", this.previousWorkDirectory);
        }
    }

    @Test
    public void plainMethodKeepsTheOriginalCaseIdentity() throws Exception {
        // 选择标准 Jupiter 方法并写入当前工作组的独占报告目录。
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(PassingCase.class, List.of())));

        // testcase 名称与 Gradle JUnit XML 一致，不额外附加括号。
        JsonNode cases = this.read(output, "selected-cases.json");
        assertEquals(PassingCase.class.getName() + "#passes", cases.get(0).path("caseId").asText());
        assertEquals("passed", cases.get(0).path("status").asText());
    }

    @Test
    public void methodSelectionRetainsEveryParameterizedVector() throws Exception {
        // 方法筛选只拆分调用方向，不拆掉或重命名参数实例。
        Path output = this.output();
        String filter = ParameterCase.class.getName() + ".selected";
        assertEquals(0, JUnitJobMain.execute(output, selection(ParameterCase.class, List.of(filter))));

        // 向量中的斜线、Unicode和方括号仍是原始 JUnit 身份的一部分。
        JsonNode cases = this.read(output, "selected-cases.json");
        List<String> names = new ArrayList<>();
        cases.forEach(value -> names.add(value.path("scenario").asText()));
        assertEquals(List.of("selected[types/甲/[value]]", "selected[types/乙/[value]]"), names);
    }

    @Test
    public void duplicateParameterizedNamesCannotSilentlyBecomeOneSuccessfulCase() throws Exception {
        // JUnit允许参数工厂给出相同显示名称，矩阵报告必须拒绝身份冲突。
        Path output = this.output();
        assertEquals(2, JUnitJobMain.execute(output, selection(DuplicateParameterCase.class, List.of())));

        // 即便显示名称相等，也不能给外层聚合一个悄悄少测的成功报告。
        JsonNode summary = this.read(output, "summary.json");
        assertTrue(summary.path("taskFailure").asText().contains("Duplicate JUnit testcase identity"));
    }

    @Test
    public void exactParameterizedFilterSelectsOnlyItsOwnVector() throws Exception {
        // 精确参数名采用文本匹配，不把用户的方括号当正则语法。
        Path output = this.output();
        String filter = ParameterCase.class.getName() + ".selected[types/乙/[value]]";
        assertEquals(0, JUnitJobMain.execute(output, selection(ParameterCase.class, List.of(filter))));

        // 本次报告只包含明确选中的一个向量。
        JsonNode cases = this.read(output, "selected-cases.json");
        assertEquals(1, cases.size());
        assertEquals("selected[types/乙/[value]]", cases.get(0).path("scenario").asText());
    }

    @Test
    public void wildcardMethodFilterKeepsBothParameterizedVectors() throws Exception {
        // 同时接受原Gradle传入的method*表达，不改变选择范围。
        Path output = this.output();
        String filter = ParameterCase.class.getName() + ".selected*";
        assertEquals(0, JUnitJobMain.execute(output, selection(ParameterCase.class, List.of(filter))));

        // 未分配的另一个方法不会混进当前组。
        JsonNode cases = this.read(output, "selected-cases.json");
        assertEquals(2, cases.size());
        cases.forEach(value -> assertTrue(value.path("scenario").asText().startsWith("selected[")));
    }

    @Test
    public void rpcVersionSelectsOnlyTheDeclaredParameterizedVector() throws Exception {
        // 参数显示名明确对应 SOAP 1.1 与 1.2，不从文本中的 SDK 版本推测协议。
        Path output = this.output();
        Map<String, Object> configuration = rpcSelection("1.2", List.of());
        assertEquals(0, JUnitJobMain.execute(output, configuration));

        // 只运行对应版本，未选向量不计跳过或未执行。
        JsonNode cases = this.read(output, "selected-cases.json");
        assertEquals(1, cases.size());
        assertEquals("selected[types/乙/[value]]", cases.get(0).path("scenario").asText());
        JsonNode summary = this.read(output, "summary.json");
        assertEquals(0, summary.path("skipped").asInt());
        assertEquals(0, summary.path("notExecuted").asInt());
    }

    @Test
    public void rpcSelectionIntersectsTheExactCaseFilter() throws Exception {
        // 协议选择覆盖两个版本，具体用例只选择其中一个参数实例。
        Path output = this.output();
        String filter = ParameterCase.class.getName() + ".selected[types/甲/[value]]";
        Map<String, Object> configuration = rpcSelection("", List.of(filter));
        assertEquals(0, JUnitJobMain.execute(output, configuration));

        // 两种筛选取交集，不能将未指定的方法或参数重新加入执行范围。
        JsonNode cases = this.read(output, "selected-cases.json");
        assertEquals(1, cases.size());
        assertEquals("selected[types/甲/[value]]", cases.get(0).path("scenario").asText());
    }

    @Test
    public void rpcParameterMismatchHasExplicitEmptySelectionEvidence() throws Exception {
        // 声明与实际参数没有交集时，本组保留可核验的空选择证据。
        Path output = this.output();
        Map<String, Object> configuration = rpcSelection("1.2", List.of());
        Map<String, Object> declaration = Map.of("name", "soap", "version", "1.2", "cases", "absent-vector");
        Map<String, Object> methods = Map.of("selected", List.of(declaration));
        configuration.put("rpcCases", Map.of(ParameterCase.class.getName(), methods));
        assertEquals(0, JUnitJobMain.execute(output, configuration));

        // 外层汇总可区分明确无匹配与丢失结果，整轮零项由汇总器拒绝。
        JsonNode summary = this.read(output, "summary.json");
        assertTrue(summary.path("selectionEmpty").asBoolean());
        assertFalse(summary.path("resultsAvailable").asBoolean());
        assertEquals(0, summary.path("tests").asInt());
        assertTrue(this.read(output, "selected-cases.json").isEmpty());
    }

    @Test
    public void rpcSelectionIncludesADeclaredPlainMethod() throws Exception {
        // 普通方法没有参数显示名，星号声明仍应选择该方法。
        Path output = this.output();
        Map<String, Object> configuration = selection(PassingCase.class, List.of());
        configuration.put("rpc", "soap");
        configuration.put("rpcVersion", "1.2");
        Map<String, Object> declaration = Map.of("name", "soap", "version", "1.2", "cases", "*");
        Map<String, Object> methods = Map.of("passes", List.of(declaration));
        configuration.put("rpcCases", Map.of(PassingCase.class.getName(), methods));
        assertEquals(0, JUnitJobMain.execute(output, configuration));

        // 普通方法保持原始身份，声明版本出现在本组报告中。
        JsonNode summary = this.read(output, "summary.json");
        assertEquals(1, summary.path("passed").asInt());
        assertEquals("1.2", summary.path("rpcVersion").asText());
        assertFalse(summary.path("selectionEmpty").asBoolean());
    }

    @Test
    public void failurePreservesTypeMessageAndFullStack() throws Exception {
        // 报告失败场景自身，不能仅记录JUnit进程是否成功返回。
        Path output = this.output();
        assertEquals(1, JUnitJobMain.execute(output, selection(FailingCase.class, List.of())));

        // XML保留可解析的特殊字符和原始异常堆栈。
        Document xml = this.xml(output, FailingCase.class);
        var failure = xml.getElementsByTagName("failure").item(0);
        String failureType = failure.getAttributes().getNamedItem("type").getNodeValue();
        assertEquals("org.opentest4j.AssertionFailedError", failureType);
        assertTrue(failure.getTextContent().contains("expected <甲> & actual"));
        assertTrue(failure.getTextContent().contains("FailingCase.fails"));
        assertEquals(1, this.read(output, "summary.json").path("failed").asInt());
    }

    @Test
    public void absentSkipErrorContinuesAfterTheFailedCase() throws Exception {
        // 默认模式保留失败，再执行本组后续方法及每层清理。
        Path output = this.output();
        assertEquals(1, JUnitJobMain.execute(output, selection(FailFastCase.class, List.of())));

        // 已执行用例分别记录真实结果，不把继续执行误报为整组通过。
        JsonNode summary = this.read(output, "summary.json");
        assertEquals(2, summary.path("tests").asInt());
        assertEquals(1, summary.path("failed").asInt());
        assertEquals(1, summary.path("passed").asInt());
        assertEquals(0, summary.path("notExecuted").asInt());
        assertTrue(summary.path("skipError").asBoolean());
        assertEquals(List.of("first", "after", "second", "after", "afterClass"), FailFastCase.events);
    }

    @Test
    public void disabledSkipErrorStopsBeforeTheNextCaseAfterRunningCleanup() throws Exception {
        // 显式关闭继续模式，在首个失败后让 JUnit runner 完成当前方法与类的清理。
        Path output = this.output();
        Map<String, Object> selection = selection(FailFastCase.class, List.of());
        selection.put("skipError", false);
        assertEquals(1, JUnitJobMain.execute(output, selection));

        // 停止行为不是任务异常，未执行项单独列出，不伪造跳过项或 XML testcase。
        JsonNode summary = this.read(output, "summary.json");
        assertTrue(summary.path("taskFailure").isNull());
        assertEquals(1, summary.path("tests").asInt());
        assertEquals(1, summary.path("failed").asInt());
        assertEquals(0, summary.path("passed").asInt());
        assertEquals(0, summary.path("skipped").asInt());
        assertEquals(1, summary.path("notExecuted").asInt());
        String remaining = FailFastCase.class.getName() + "#secondPasses";
        assertEquals(remaining, summary.path("notExecutedCases").get(0).asText());
        assertEquals(2, summary.path("plannedCases").size());
        assertEquals(1, this.xml(output, FailFastCase.class).getElementsByTagName("testcase").getLength());
        assertEquals(List.of("first", "after", "afterClass"), FailFastCase.events);
    }

    @Test
    public void failFastRetainsTheFullIdentityOfUnexecutedParameterizedCases() throws Exception {
        // 参数向量各自拥有完整身份，停止后不能按方法名合并剩余实例。
        Path output = this.output();
        Map<String, Object> selection = selection(FailFastParameterCase.class, List.of());
        selection.put("skipError", false);
        assertEquals(1, JUnitJobMain.execute(output, selection));

        // 两个向量各含两个方法，首个失败后只报告一个执行结果和三个未执行身份。
        JsonNode summary = this.read(output, "summary.json");
        assertEquals(4, summary.path("plannedCases").size());
        assertEquals(3, summary.path("notExecuted").asInt());
        List<String> remaining = new ArrayList<>();
        summary.path("notExecutedCases").forEach(value -> remaining.add(value.asText()));
        String prefix = FailFastParameterCase.class.getName() + "#";
        assertEquals(List.of(prefix + "secondPasses[甲]", prefix + "firstFails[乙]",
                prefix + "secondPasses[乙]"), remaining);
        assertEquals(1, this.read(output, "selected-cases.json").size());
        assertEquals(0, summary.path("skipped").asInt());
    }

    @Test
    public void nonBooleanSkipErrorIsRejectedBeforeTheJobStarts() throws Exception {
        // 内部选择参数也必须使用布尔值，不能把文本 false 当成默认 true。
        Map<String, Object> selection = selection(PassingCase.class, List.of());
        selection.put("skipError", "false");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> JUnitJobMain.execute(this.output(), selection));

        // 错误直接指出参数约束，不创建一个看似成功的测试任务。
        assertEquals("skipError must be true or false", failure.getMessage());
    }

    @Test
    public void commandLineEnabledSkipErrorContinuesAfterFailure() throws Exception {
        // 独立 JVM 从公开命令行接受显式 true，仍保留失败退出码。
        Path output = this.output();
        Process process = this.launch(output, "g1", FailFastCase.class, "--skipError", "true");
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
        } finally {
            this.close(process);
        }

        // 参数进入选择配置，后续 testcase 继续产生真实执行结果。
        assertTrue(this.read(output, "configuration.json").path("skipError").asBoolean());
        assertEquals(2, this.read(output, "summary.json").path("tests").asInt());
    }

    @Test
    public void commandLineDisabledSkipErrorStopsAfterFailure() throws Exception {
        // 独立 JVM 将显式 false 传到 JUnit 通知器，首个失败后正常完成报告。
        Path output = this.output();
        Process process = this.launch(output, "g1", FailFastCase.class, "--skipError", "false");
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, process.exitValue());
        } finally {
            this.close(process);
        }

        // 停止后保留失败和未执行项，选择配置没有回退成默认 true。
        JsonNode summary = this.read(output, "summary.json");
        assertFalse(this.read(output, "configuration.json").path("skipError").asBoolean());
        assertEquals(1, summary.path("tests").asInt());
        assertEquals(1, summary.path("notExecuted").asInt());
    }

    @Test
    public void commandLineRejectsAnInvalidSkipErrorValue() throws Exception {
        // 非布尔文本属于启动参数错误，不能悄悄按 true 继续执行。
        Path output = this.output();
        Process process = this.launch(output, "g1", PassingCase.class, "--skipError", "sometimes");
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, process.exitValue());
        } finally {
            this.close(process);
        }

        // 参数校验发生在测试启动前，控制台明确打印原始错误。
        String log = Files.readString(output.resolve("process.log"));
        assertTrue(log.contains("--skipError must be true or false: sometimes"));
        assertFalse(Files.exists(output.resolve("interoperability")));
    }

    @Test
    public void commandLineRequiresAValueForSkipError() throws Exception {
        // 缺失布尔值时保留常规参数错误，不启动测试 JVM 内的 runner。
        Path output = this.output();
        Process process = this.launch(output, "g1", PassingCase.class, "--skipError");
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, process.exitValue());
        } finally {
            this.close(process);
        }

        // 诊断指出缺少哪个参数值，当前代没有伪造的 JUnit 结果。
        String log = Files.readString(output.resolve("process.log"));
        assertTrue(log.contains("Missing value for --skipError"));
        assertFalse(Files.exists(output.resolve("interoperability")));
    }

    @Test
    public void failurePrintsTheFullStackToBorrowedStderrWithoutClosingIt() throws Exception {
        // 独立任务借用调用方的标准错误流，失败应直接出现在运行日志中。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.err;
        Path output = this.output();
        try (PrintStream captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            try {
                assertEquals(1, JUnitJobMain.execute(output, selection(FailingCase.class, List.of())));

                // 控制台保留异常类型、原始消息和调用栈，正式报告仍记录同一个失败。
                String trace = bytes.toString(StandardCharsets.UTF_8);
                assertTrue(trace.contains("org.opentest4j.AssertionFailedError: expected <甲> & actual"));
                assertTrue(trace.contains("at " + FailingCase.class.getName() + ".fails("));
                assertEquals(1, this.read(output, "summary.json").path("failed").asInt());

                // 报告结束仅恢复借入的流，调用方仍然可以继续写入。
                assertSame(captured, System.err);
                captured.println("stderr remains writable");
                assertFalse(captured.checkError());
                assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("stderr remains writable"));
            } finally {
                System.setErr(original);
            }
        }
    }

    @Test
    public void ignoredMethodRemainsAnExplicitSkippedCase() throws Exception {
        // JUnit忽略方法也必须占据报告中的独立身份。
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(IgnoredCase.class, List.of())));

        // 聚合器可以区分没有执行和执行后通过。
        JsonNode summary = this.read(output, "summary.json");
        assertEquals(1, summary.path("skipped").asInt());
        assertEquals("skipped", this.read(output, "selected-cases.json").get(0).path("status").asText());
    }

    @Test
    public void ignoredClassUsesTheSameIdentityInThePlanAndXml() throws Exception {
        // 类级禁用沿用 Jupiter 已发现的方法身份，计划与实际 XML 保持一致。
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(IgnoredClass.class, List.of())));

        // 类级禁用按方法记录，不能再次列入未执行项，也不能生成 Class#null 身份。
        JsonNode summary = this.read(output, "summary.json");
        String caseId = IgnoredClass.class.getName() + "#cannotRun";
        assertEquals(caseId, summary.path("plannedCases").get(0).asText());
        assertEquals(caseId, this.read(output, "selected-cases.json").get(0).path("caseId").asText());
        assertEquals(0, summary.path("notExecuted").asInt());
        assertEquals(1, summary.path("skipped").asInt());
        Document xml = this.xml(output, IgnoredClass.class);
        var testcase = xml.getElementsByTagName("testcase").item(0);
        String xmlName = testcase.getAttributes().getNamedItem("name").getNodeValue();
        assertEquals("cannotRun", xmlName);
    }

    @Test
    public void assumptionFailureRemainsAnExplicitSkippedCase() throws Exception {
        // 假设不满足不能作为正常通过上报。
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(AssumptionCase.class, List.of())));

        // 保留假设失败的原因以供全量零跳过门禁使用。
        Document xml = this.xml(output, AssumptionCase.class);
        assertEquals(1, xml.getElementsByTagName("skipped").getLength());
        assertEquals(1, this.read(output, "summary.json").path("skipped").asInt());
    }

    @Test
    public void beforeClassFailureCannotBecomeAnEmptySuccessfulRun() throws Exception {
        // 在首个测试方法开始前失败的类仍需产生失败XML。
        Path output = this.output();
        assertEquals(1, JUnitJobMain.execute(output, selection(BeforeClassFailure.class, List.of())));

        // 初始化错误有明确身份和堆栈，不被遗漏或计为零用例成功。
        JsonNode cases = this.read(output, "selected-cases.json");
        assertEquals("initializationError", cases.get(0).path("scenario").asText());
        assertEquals("failed", cases.get(0).path("status").asText());
    }

    @Test
    public void unmatchedMethodFilterFailsWithAnExplicitTaskFailure() throws Exception {
        // 错误筛选条件必须失败，不能生成可复用的空绿报告。
        Path output = this.output();
        assertEquals(2, JUnitJobMain.execute(output,
                selection(PassingCase.class, List.of(PassingCase.class.getName() + ".missing"))));

        // 当前代报告明确没有可用结果，且记录失败原因。
        JsonNode summary = this.read(output, "summary.json");
        assertFalse(summary.path("resultsAvailable").asBoolean());
        assertTrue(summary.path("taskFailure").asText().contains("JUnit selection contains no testcase"));
    }

    @Test
    public void duplicateClassesAreRejectedBeforeExecution() throws Exception {
        // 同一类重复分配不能静默去重，以免掩盖矩阵分片错误。
        Map<String, Object> selection = selection(PassingCase.class, List.of());
        selection.put("classNames", List.of(PassingCase.class.getName(), PassingCase.class.getName()));
        assertThrows(IllegalArgumentException.class, () -> JUnitJobMain.execute(this.output(), selection));
    }

    @Test
    public void existingXmlIsRejectedWithoutReplacingItsContents() throws Exception {
        // 拒绝继承旧代结果，即便调用者为新运行传入了相同输出目录。
        Path output = this.output();
        Path xml = output.resolve("test-results/test/TEST-old.xml");
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, "old-generation");
        assertThrows(Exception.class,
                () -> JUnitJobMain.execute(output, selection(PassingCase.class, List.of())));

        // 保留原证据，让编排层自己决定如何归档或清理。
        assertEquals("old-generation", Files.readString(xml));
    }

    @Test
    public void rawAndArchivedXmlAreTheSameGeneration() throws Exception {
        // 单组保留独占的运行身份、逻辑筛选条件和原始XML副本。
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(PassingCase.class, List.of())));

        // 聚合时可以进行字节校验，不从最近的XML目录推断本轮结果。
        String file = "TEST-" + PassingCase.class.getName() + ".xml";
        assertArrayEquals(Files.readAllBytes(output.resolve("test-results/test").resolve(file)),
                Files.readAllBytes(output.resolve("interoperability/test-results").resolve(file)));
        JsonNode config = this.read(output, "configuration.json");
        assertEquals("current", config.path("orchestrationRunId").asText());
        assertEquals("current-g1", config.path("runId").asText());
        assertEquals("g1", config.path("groupId").asText());
        assertEquals(PassingCase.class.getName(), config.path("classNames").get(0).asText());
    }

    @Test
    public void configurationRetainsEffectiveDependencyAndNacosSettings() throws Exception {
        // 版本和SDK节流参数来自实际JUnit JVM，而不是写死的报告常量。
        String previousVersion = System.getProperty("lib.junit");
        String previousLimit = System.getProperty("limitTime");
        Path output = this.output();
        try {
            System.setProperty("lib.junit", "4.13.2");
            System.setProperty("limitTime", "37");
            assertEquals(0, JUnitJobMain.execute(output, selection(PassingCase.class, List.of())));

            // 审计可以核对编排最终传入的值，不丢失原有Gradle报告维度。
            JsonNode config = this.read(output, "configuration.json");
            assertEquals("4.13.2", config.path("libraryVersions").path("lib.junit").asText());
            assertEquals("37", config.path("nacosClientLimitTime").asText());
        } finally {
            restoreProperty("lib.junit", previousVersion);
            restoreProperty("limitTime", previousLimit);
        }
    }

    @Test
    public void stdoutAndStderrArePreservedAndBorrowedStreamsAreRestored() throws Exception {
        // 当前组捕获原始输出，控制台由调用方拥有。
        PrintStream outputStream = System.out;
        PrintStream errorStream = System.err;
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(OutputCase.class, List.of())));

        // 包含非法XML控制字符的原始日志保持原样，XML只移除不能表达的字符。
        assertSame(outputStream, System.out);
        assertSame(errorStream, System.err);
        assertTrue(Files.readString(output.resolve("stdout.log")).contains("stdout\u0001甲"));
        Document xml = this.xml(output, OutputCase.class);
        assertTrue(xml.getElementsByTagName("system-out").item(0).getTextContent().contains("stdout甲"));
        assertTrue(xml.getElementsByTagName("system-err").item(0).getTextContent().contains("stderr乙"));
    }

    @Test
    public void progressLookingBusinessOutputRemainsRawConsoleOutput() throws Exception {
        // 业务日志可以具有任意前缀，不能被编排器当成机器进度截走。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.out;
        Path output = this.output();
        try (PrintStream captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(captured);
            try {
                assertEquals(0, JUnitJobMain.execute(output, selection(OutputCase.class, List.of())));
            } finally {
                System.setOut(original);
            }
        }

        // 原始控制台和归档都保留相同业务文本，机器事件单独写入进度文件。
        String message = "[JUnit progress] ordinary business output";
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains(message));
        assertTrue(Files.readString(output.resolve("stdout.log")).contains(message));
        assertFalse(Files.readString(output.resolve("progress.jsonl")).contains(message));
        long progressLines = bytes.toString(StandardCharsets.UTF_8).lines() //
                .filter(line -> line.startsWith("[JUnit progress] ")).count();
        assertEquals(1, progressLines);
    }

    @Test
    public void progressIsFlushedBeforeTheReportCloses() throws Exception {
        // 运行中的编排器需要立即看到事件，不能等 JUnit 结束后才读取缓冲。
        Path output = this.output();
        TestIdentifier description = identifier(PassingCase.class, "passes");
        try (JUnitJobReport report = new JUnitJobReport(output, selection(PassingCase.class, List.of()))) {
            report.testPlanExecutionStarted(plan());
            assertEquals("preparing", this.progress(output).get(0).path("phase").asText());

            // 后续事件继续追加到同一个文件，早先事件保持原样。
            String initial = Files.readString(output.resolve("progress.jsonl"));
            report.executionStarted(description);
            assertEquals(2, this.progress(output).size());
            assertTrue(Files.readString(output.resolve("progress.jsonl")).startsWith(initial));
            report.executionFinished(description, TestExecutionResult.successful());
        }
    }

    @Test
    public void progressFileFailurePrintsTheFullErrorWithoutChangingTheTestResult() throws Exception {
        // 已存在的进度文件不能被覆盖，创建失败只影响进度展示。
        Path output = this.output();
        Files.writeString(output.resolve("progress.jsonl"), "previous generation\n");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (PrintStream captured = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            try {
                assertEquals(0, JUnitJobMain.execute(output, selection(PassingCase.class, List.of())));
            } finally {
                System.setErr(original);
            }
        }

        // 错误及其调用栈直接输出到借入的 stderr，正式成功报告与旧进度文件均保留。
        String trace = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(trace.contains("java.nio.file.FileAlreadyExistsException:"));
        assertTrue(trace.contains("at " + JUnitJobReport.class.getName() + ".<init>("));
        assertEquals("passed", this.read(output, "selected-cases.json").get(0).path("status").asText());
        assertEquals("previous generation\n", Files.readString(output.resolve("progress.jsonl")));
    }

    @Test
    public void progressKeepsUnicodeAndNewlinesInsideOneJsonLine() throws Exception {
        // 测试名与异常消息中的中文和换行沿用真实身份，由 JSON 完成转义。
        Path output = this.output();
        TestIdentifier description = identifier(PassingCase.class, "调用\n乙");
        try (JUnitJobReport report = new JUnitJobReport(output, selection(PassingCase.class, List.of()))) {
            report.testPlanExecutionStarted(plan());
            report.executionStarted(description);
            report.addFailure(description, new IllegalStateException("失败甲\n乙"));
            report.executionFinished(description, TestExecutionResult.successful());
        }

        // 每条进度独立可解析，消费者能恢复完整名称和异常而不必拼接多行。
        List<JsonNode> progress = this.progress(output);
        JsonNode failure = progress.stream().filter(item -> item.has("failure")).findFirst().orElseThrow();
        assertEquals(PassingCase.class.getName() + "#调用\n乙", failure.path("current").asText());
        assertEquals("java.lang.IllegalStateException: 失败甲\n乙", failure.path("failure").asText());
        assertTrue(Files.readString(output.resolve("progress.jsonl")).contains("调用\\n乙"));
        assertEquals("current", failure.path("runId").asText());
        assertEquals("g1", failure.path("groupId").asText());
    }

    @Test
    public void initializationFailureProgressDoesNotRequireATestStart() throws Exception {
        // 类初始化失败不会触发 testStarted，仍须立即上报失败身份与完成数。
        Path output = this.output();
        assertEquals(1, JUnitJobMain.execute(output, selection(BeforeClassFailure.class, List.of())));

        // 初始化错误与最终 XML 中的 initializationError 使用同一个身份。
        List<JsonNode> progress = this.progress(output);
        JsonNode failure = progress.stream().filter(item -> item.has("failure")).findFirst().orElseThrow();
        assertEquals(BeforeClassFailure.class.getName() + "#initializationError", failure.path("case").asText());
        assertEquals(1, failure.path("completed").asInt());
        assertEquals(1, failure.path("failed").asInt());
        assertFalse(progress.stream().anyMatch(item -> StringUtils.equals("testing", item.path("phase").asText())));
    }

    @Test
    public void ignoredProgressCountsTheCaseWithoutATestStart() throws Exception {
        // 忽略项只有 ignored 事件，完成数必须增加，失败数保持不变。
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(IgnoredCase.class, List.of())));

        // 汇总能明确区分跳过与通过，不依赖一个不存在的 START/DONE 配对。
        List<JsonNode> progress = this.progress(output);
        JsonNode last = progress.get(progress.size() - 1);
        assertEquals(1, last.path("total").asInt());
        assertEquals(1, last.path("completed").asInt());
        assertEquals(1, last.path("skipped").asInt());
        assertEquals(0, last.path("failed").asInt());
    }

    @Test
    public void repeatedFailureEventsCountOneFailedCaseInProgress() throws Exception {
        // 一个 testcase 可以报告多条失败，进度按 testcase 身份计数。
        Path output = this.output();
        TestIdentifier description = identifier(PassingCase.class, "multipleFailures");
        try (JUnitJobReport report = new JUnitJobReport(output, selection(PassingCase.class, List.of()))) {
            report.testPlanExecutionStarted(plan());
            report.executionStarted(description);
            report.addFailure(description, new AssertionError("first"));
            report.addFailure(description, new AssertionError("second"));
            report.executionFinished(description, TestExecutionResult.successful());
        }

        // 保留两条异常事件，但失败总数始终只增加一次。
        List<JsonNode> progress = this.progress(output);
        assertEquals(2, progress.stream().filter(item -> item.has("failure")).count());
        assertEquals(1, progress.get(progress.size() - 1).path("failed").asInt());
    }

    @Test
    public void repeatedAssumptionEventsCountOneSkippedCaseInProgress() throws Exception {
        // 跳过事件也按 testcase 去重，不能重复增加矩阵完成数。
        Path output = this.output();
        TestIdentifier description = identifier(PassingCase.class, "missingPrerequisite");
        try (JUnitJobReport report = new JUnitJobReport(output, selection(PassingCase.class, List.of()))) {
            report.testPlanExecutionStarted(plan());
            report.executionStarted(description);
            report.executionFinished(description, TestExecutionResult.aborted(new IllegalStateException("first")));
            report.executionFinished(description, TestExecutionResult.aborted(new IllegalStateException("second")));
            report.executionFinished(description, TestExecutionResult.successful());
        }

        // 进度与最终报告都保留一个已完成的跳过项。
        List<JsonNode> progress = this.progress(output);
        JsonNode last = progress.get(progress.size() - 1);
        assertEquals(1, last.path("skipped").asInt());
        assertEquals(1, last.path("completed").asInt());
    }

    @Test
    public void completedTestsStillReportTheReportingAndCleanupPhases() throws Exception {
        // 测试全部完成后仍需生成报告并等待 JVM 和外层工作组清理。
        Path output = this.output();
        assertEquals(0, JUnitJobMain.execute(output, selection(PassingCase.class, List.of())));

        // 最后保留清理阶段，不能把 testcase 完成误报成整个工作组已经结束。
        List<JsonNode> progress = this.progress(output);
        JsonNode reporting = progress.get(progress.size() - 2);
        JsonNode cleanup = progress.get(progress.size() - 1);
        assertEquals("reporting", reporting.path("phase").asText());
        assertEquals("cleanup", cleanup.path("phase").asText());
        assertEquals(1, cleanup.path("completed").asInt());
        assertEquals(0, cleanup.path("testElapsedMillis").asLong());
        assertTrue(cleanup.path("elapsedMillis").asLong() >= reporting.path("elapsedMillis").asLong());
    }

    @Test
    public void borrowedRegistryFixtureDefersValidationUntilTheJvmExits() throws Exception {
        // 静态fixture仍需在shutdown hook释放，JUnit结束时不能误判为泄漏。
        Path output = this.output();
        Path work = output.resolve("container-scenarios");
        Path state = work.resolve("registry-services/nacos/lifecycle.properties");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "state=borrowed\n");
        System.setProperty("meteor.test.container.workDirectory", work.toString());
        assertEquals(0, JUnitJobMain.execute(output, selection(PassingCase.class, List.of())));

        // 未经退出后的外部核对不能宣称清理成功，当前状态只作为快照保留。
        JsonNode summary = this.read(output, "summary.json");
        assertTrue(summary.path("registryCleanup").path("successful").isNull());
        assertTrue(summary.path("registryCleanup").path("verificationPending").asBoolean());
        assertEquals("borrowed", summary.path("registryCleanup").path("services").path("nacos").path("state").asText());
    }

    @Test
    public void independentJvmsReuseCompiledClassesAndKeepSeparateReports() throws Exception {
        // 两个工作组直接复用当前已编译的class和classpath，不启动Gradle或重新编译。
        Path first = this.output();
        Path second = this.output();
        Process firstProcess = this.launch(first, "first", PassingCase.class);
        Process secondProcess = null;
        try {
            secondProcess = this.launch(second, "second", PassingCase.class);
            assertTrue(firstProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(secondProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, firstProcess.exitValue());
            assertEquals(0, secondProcess.exitValue());
        } finally {
            this.close(firstProcess);
            this.close(secondProcess);
        }

        // JVM身份与筛选范围分别归档，两个进程只写自己的输出树。
        Path firstReport = first.resolve("interoperability/configuration.json");
        Path secondReport = second.resolve("interoperability/configuration.json");
        JsonNode firstConfig = JSON.readTree(firstReport.toFile());
        JsonNode secondConfig = JSON.readTree(secondReport.toFile());
        assertNotEquals(firstConfig.path("processId").asLong(), secondConfig.path("processId").asLong());
        assertEquals("first", firstConfig.path("matrixSelection").path("meteor.matrix.jobMarker").asText());
        assertEquals("second", secondConfig.path("matrixSelection").path("meteor.matrix.jobMarker").asText());
    }

    @Test
    public void registryReleaseInShutdownHookRemainsAvailableAfterTheJobExits() throws Exception {
        // 复现真实fixture在System.exit的关闭钩子中才完成释放的时序。
        Path output = this.output();
        Process process = this.launch(output, "hook", ShutdownHookCase.class);
        try {
            assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
        } finally {
            this.close(process);
        }

        // 报告保留待核对快照，外层在进程结束后能够读到真正的released终态。
        Path summaryFile = output.resolve("interoperability/summary.json");
        JsonNode snapshot = JSON.readTree(summaryFile.toFile()).path("registryCleanup");
        assertTrue(snapshot.path("verificationPending").asBoolean());
        Path lifecycle = output.resolve("container-scenarios/registry-services/fake/lifecycle.properties");
        assertEquals("state=released\n", Files.readString(lifecycle));
        assertTrue(Files.readString(output.resolve("process.log")).contains("fixture released in shutdown hook"));
    }

    private static TestPlan plan() {
        var request = LauncherDiscoveryRequestBuilder.request() //
                .selectors(DiscoverySelectors.selectClass(PassingCase.class)) //
                .build();
        return LauncherFactory.create().discover(request);
    }

    private static TestIdentifier identifier(Class<?> type, String method) {
        // 独立监听器夹具保留真实的 Platform 节点结构，不依赖旧 Runner 的 Description。
        var source = from(type.getName(), method);
        var id = UniqueId.forEngine("junit-jupiter").append("class", type.getName()).append("method", method);
        TestDescriptor descriptor = new AbstractTestDescriptor(id, method, source) {
            @Override
            public Type getType() {
                return Type.TEST;
            }
        };
        return TestIdentifier.from(descriptor);
    }

    private Process launch(Path output, String group, Class<?> type, String... options) throws Exception {
        Path classes = output.resolve("classes.txt");
        Path filters = output.resolve("filters.txt");
        Files.writeString(classes, type.getName() + "\n");
        Files.writeString(filters, type.getName() + "\n");
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>();
        // JVM参数必须位于入口类之前；源文件和构建配置不进入工作组执行命令。
        command.add(java.toString());
        command.add("-Dmeteor.test.run.id=current");
        command.add("-Dmeteor.test.group.id=" + group);
        command.add("-Dmeteor.matrix.jobMarker=" + group);
        command.add("-Dmeteor.test.container.workDirectory=" + output.resolve("container-scenarios"));
        command.add("-Djava.io.tmpdir=" + output);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(JUnitJobMain.class.getName());
        command.add("--output");
        command.add(output.toString());
        command.add("--classes-file");
        command.add(classes.toString());
        command.add("--filters-file");
        command.add(filters.toString());
        command.addAll(List.of(options));
        return new ProcessBuilder(command).redirectErrorStream(true) //
                .redirectOutput(output.resolve("process.log").toFile()).start();
    }

    private List<JsonNode> progress(Path output) throws Exception {
        List<JsonNode> progress = new ArrayList<>();
        for (String line : Files.readAllLines(output.resolve("progress.jsonl"), StandardCharsets.UTF_8)) {
            progress.add(JSON.readTree(line));
        }
        return progress;
    }

    private void close(Process process) throws Exception {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
            assertTrue(process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private Path output() throws Exception {
        Path directory = Files.createTempDirectory(this.temporary, "case-");
        System.setProperty("meteor.test.container.workDirectory", directory.resolve("container-scenarios").toString());
        return directory;
    }

    private JsonNode read(Path output, String file) throws Exception {
        return JSON.readTree(output.resolve("interoperability").resolve(file).toFile());
    }

    private Document xml(Path output, Class<?> type) throws Exception {
        Path file = output.resolve("test-results/test/TEST-" + type.getName() + ".xml");
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
    }

    private static Map<String, Object> selection(Class<?> type, List<String> filters) {
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("orchestrationRunId", "current");
        selection.put("groupId", "g1");
        selection.put("classNames", List.of(type.getName()));
        selection.put("includes", filters);
        return selection;
    }

    private static Map<String, Object> rpcSelection(String version, List<String> filters) {
        Map<String, Object> configuration = selection(ParameterCase.class, filters);
        configuration.put("rpc", "soap");
        configuration.put("rpcVersion", version);
        List<Map<String, Object>> declarations = new ArrayList<>();
        declarations.add(Map.of("name", "soap", "version", "1.1", "cases", "types/甲/[value]"));
        declarations.add(Map.of("name", "soap", "version", "1.2", "cases", "types/乙/[value]"));
        configuration.put("rpcCases", Map.of(ParameterCase.class.getName(), Map.of("selected", declarations)));
        return configuration;
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    public static class PassingCase {
        @Test
        public void passes() {
            assertTrue(true);
        }
    }

    @ParameterizedClass(name = "types/{0}/[value]", quoteTextArguments = false, autoCloseArguments = false)
    @MethodSource("vectors")
    public static class ParameterCase {
        public ParameterCase(String name) {
        }

        public static List<Object[]> vectors() {
            List<Object[]> values = new ArrayList<>();
            values.add(new Object[] { "甲" });
            values.add(new Object[] { "乙" });
            return values;
        }

        @Test
        public void selected() {
        }

        @Test
        public void other() {
        }
    }

    public static class FailingCase {
        @Test
        public void fails() {
            fail("expected <甲> & actual");
        }
    }

    @TestMethodOrder(MethodOrderer.MethodName.class)
    public static class FailFastCase {
        private static final List<String> events = new ArrayList<>();

        @BeforeAll
        public static void reset() {
            events.clear();
        }

        @AfterEach
        public void after() {
            events.add("after");
        }

        @AfterAll
        public static void afterClass() {
            events.add("afterClass");
        }

        @Test
        public void firstFails() {
            events.add("first");
            fail("fail-fast fixture");
        }

        @Test
        public void secondPasses() {
            events.add("second");
        }
    }

    @ParameterizedClass(name = "{0}", quoteTextArguments = false, autoCloseArguments = false)
    @MethodSource("vectors")
    @TestMethodOrder(MethodOrderer.MethodName.class)
    public static class FailFastParameterCase {
        public FailFastParameterCase(String name) {
        }

        public static List<Object[]> vectors() {
            // 向量顺序用于验证首个失败后保留其余参数实例的完整身份。
            List<Object[]> values = new ArrayList<>();
            values.add(new Object[] { "甲" });
            values.add(new Object[] { "乙" });
            return values;
        }

        @Test
        public void firstFails() {
            fail("parameterized fail-fast fixture");
        }

        @Test
        public void secondPasses() {
        }
    }

    @ParameterizedClass(name = "{0}", quoteTextArguments = false, autoCloseArguments = false)
    @MethodSource("vectors")
    public static class DuplicateParameterCase {
        public DuplicateParameterCase(String name) {
        }

        public static List<Object[]> vectors() {
            List<Object[]> values = new ArrayList<>();
            values.add(new Object[] { "same-name" });
            values.add(new Object[] { "same-name" });
            return values;
        }

        @Test
        public void selected() {
        }
    }

    public static class IgnoredCase {
        @Disabled("explicit ignored fixture")
        @Test
        public void ignored() {
            fail("Ignored method was executed");
        }
    }

    @Disabled("explicit ignored class fixture")
    public static class IgnoredClass {
        @Test
        public void cannotRun() {
            fail("Ignored class was executed");
        }
    }

    public static class AssumptionCase {
        @Test
        public void missingPrerequisite() {
            Assumptions.assumeTrue(false, "explicit assumption fixture");
        }
    }

    public static class BeforeClassFailure {
        @BeforeAll
        public static void setup() {
            throw new IllegalStateException("before class fixture");
        }

        @Test
        public void cannotStart() {
            fail("Class setup failed");
        }
    }

    public static class OutputCase {
        @Test
        public void output() {
            System.out.println("stdout\u0001甲");
            System.out.println("[JUnit progress] ordinary business output");
            System.err.println("stderr乙");
        }
    }

    public static class ShutdownHookCase {
        @Test
        public void borrowsFixtureUntilJvmShutdown() throws Exception {
            Path work = Path.of(System.getProperty("meteor.test.container.workDirectory"));
            Path lifecycle = work.resolve("registry-services/fake/lifecycle.properties");
            Files.createDirectories(lifecycle.getParent());
            Files.writeString(lifecycle, "state=borrowed\n");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Files.writeString(lifecycle, "state=released\n");
                    System.out.println("fixture released in shutdown hook");
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }));
        }
    }
}
