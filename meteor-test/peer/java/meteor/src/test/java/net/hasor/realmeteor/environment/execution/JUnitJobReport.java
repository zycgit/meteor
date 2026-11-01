/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.realmeteor.environment.execution;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.test.nxn.NxnRpcSelection;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** 保存本 JVM 实际执行的 JUnit 实例、原始输出和与现有聚合兼容的 XML/JSON。 */
final class JUnitJobReport implements TestExecutionListener, AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path output;
    private final Path report;
    private final Map<String, Object> configuration;
    private TestPlan plan;
    private boolean failureStopped;
    private final Set<String> startedIdentities = new LinkedHashSet<>();
    private final Set<TestIdentifier> plannedCases = new LinkedHashSet<>();
    private final Map<String, Document> suites = new LinkedHashMap<>();
    private final Map<TestIdentifier, Element> cases = new LinkedHashMap<>();
    private final Map<TestIdentifier, Long> starts = new LinkedHashMap<>();
    private final Set<TestIdentifier> startedCases = new LinkedHashSet<>();
    private final Set<TestIdentifier> failedCases = new LinkedHashSet<>();
    private final Set<TestIdentifier> skippedCases = new LinkedHashSet<>();
    private final Map<String, Long> suiteStarts = new LinkedHashMap<>();
    private final Map<String, Long> suiteEnds = new LinkedHashMap<>();
    private final Map<String, OutputStream> captured = new LinkedHashMap<>();
    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private final BufferedWriter progressWriter;
    private final long startedNanos = System.nanoTime();
    private volatile String currentClass;
    private Throwable executionFailure;
    private int failed;
    private int skipped;
    private int total;
    private boolean testTaskStarted;

    JUnitJobReport(Path output, Map<String, Object> selection) throws Exception {
        this.output = output.toAbsolutePath();
        this.configuration = new LinkedHashMap<>(selection);
        this.configuration.putIfAbsent("rpc", "");
        this.configuration.putIfAbsent("rpcVersion", "");
        this.configuration.putIfAbsent("rpcCases", Map.of());
        String runId = selection.get("orchestrationRunId") + "-" + selection.get("groupId");
        this.report = this.output.resolve("interoperability");
        Files.createDirectories(this.report.getParent());
        Files.createDirectory(this.report);
        Path results = Files.createDirectories(this.output.resolve("test-results/test"));
        try (Stream<Path> files = Files.list(results)) {
            if (files.anyMatch(path -> path.getFileName().toString().startsWith("TEST-"))) {
                throw new IOException("Refusing to reuse existing JUnit XML: " + results);
            }
        }
        Files.createDirectories(this.output.resolve("test-output"));
        this.configuration.put("runId", runId);
        this.configuration.put("testStartedAt", System.currentTimeMillis());
        this.configuration.put("matrixSelection", properties("meteor.matrix."));
        this.configuration.put("libraryVersions", properties("lib."));
        this.configuration.put("nacosClientLimitTime", System.getProperty("limitTime"));
        Map<String, String> environment = containerEnvironment();
        boolean configured = environment.keySet().stream().anyMatch(name -> name.startsWith("METEOR_TEST_SERVICE_"));
        this.configuration.put("containerRuntime", Map.of("configured", configured, "environment", environment));
        this.configuration.put("executionModel", "jupiter-independent-jvm");
        this.configuration.put("xmlOutputScope", "JUnit execution; the process supervisor retains JVM shutdown output");
        this.configuration.put("processId", ProcessHandle.current().pid());
        this.configuration.put("executionPlatform", Map.of("os", System.getProperty("os.name"),
                "architecture", System.getProperty("os.arch"), "javaVersion", System.getProperty("java.version")));
        writeJson(this.report.resolve("configuration.json"), this.configuration);

        // 机器进度独占追加文件，控制台只承担原始输出，不混入供编排器解析的事件。
        BufferedWriter progress = null;
        try {
            progress = Files.newBufferedWriter(this.output.resolve("progress.jsonl"), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException problem) {
            this.originalErr.println("Cannot create JUnit progress file");
            problem.printStackTrace(this.originalErr);
        }
        this.progressWriter = progress;
    }

    void captureOutput() throws IOException {
        this.captured.put("stdout", Files.newOutputStream(this.output.resolve("stdout.log")));
        this.captured.put("stderr", Files.newOutputStream(this.output.resolve("stderr.log")));
        System.setOut(this.capture("stdout", this.originalOut));
        System.setErr(this.capture("stderr", this.originalErr));
    }

    private PrintStream capture(String name, PrintStream original) {
        OutputStream stream = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                byte[] bytes = { (byte) value };
                this.write(bytes, 0, 1);
            }

            @Override
            public void write(byte[] bytes, int offset, int length) throws IOException {
                synchronized (captured) {
                    original.write(bytes, offset, length);
                    captured.get(name).write(bytes, offset, length);
                    String suite = currentClass;
                    if (suite != null) {
                        String key = suite + "." + name;
                        OutputStream target = captured.get(key);
                        if (target == null) {
                            target = Files.newOutputStream(output.resolve("test-output").resolve(key + ".log"));
                            captured.put(key, target);
                        }
                        target.write(bytes, offset, length);
                    }
                }
            }

            @Override
            public void flush() throws IOException {
                synchronized (captured) {
                    original.flush();
                    for (OutputStream target : captured.values()) {
                        target.flush();
                    }
                }
            }
        };
        return new PrintStream(stream, true, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized void testPlanExecutionStarted(TestPlan plan) {
        this.plan = plan;
        this.testTaskStarted = true;
        for (TestIdentifier root : plan.getRoots()) {
            this.collectPlannedCases(root);
        }
        this.printProgress("preparing", null, null);
    }

    @Override
    public synchronized void dynamicTestRegistered(TestIdentifier identifier) {
        this.collectPlannedCases(identifier);
    }

    private void collectPlannedCases(TestIdentifier identifier) {
        if (identifier.isTest() && this.selected(identifier)) {
            this.plannedCases.add(identifier);
        }
        for (TestIdentifier child : this.plan.getChildren(identifier)) {
            this.collectPlannedCases(child);
        }
        this.total = this.plannedCases.size();
    }

    @Override
    public synchronized void executionStarted(TestIdentifier identifier) {
        String className = this.className(identifier);
        if (!identifier.isTest()) {
            if (className != null) {
                this.currentClass = className;
                this.suiteStarts.putIfAbsent(className, System.nanoTime());
                this.printProgress("preparing", null, null);
            }
            return;
        }
        if (!this.selected(identifier)) {
            return;
        }
        try {
            // UniqueId 保留引擎身份；显示名称重复时仍拒绝一个矩阵场景被悄悄合并。
            String caseId = this.caseId(identifier);
            if (!this.startedCases.add(identifier) || !this.startedIdentities.add(caseId)) {
                this.executionFailed(new IllegalStateException("Duplicate JUnit testcase identity: " + caseId));
            }
            this.plannedCases.add(identifier);
            this.total = this.plannedCases.size();
            this.currentClass = className;
            this.addCase(identifier);
            this.starts.put(identifier, System.nanoTime());
            this.originalOut.println("[JUnit] " + caseId);
            this.printProgress("testing", null, null);
        } catch (Exception failure) {
            this.executionFailed(failure);
        }
    }

    @Override
    public synchronized void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
        try {
            if (result.getStatus() == TestExecutionResult.Status.FAILED) {
                Throwable failure = result.getThrowable().orElseThrow();
                if (this.className(identifier) == null) {
                    this.executionFailed(failure);
                } else {
                    this.plannedCases.add(identifier);
                    this.total = this.plannedCases.size();
                    this.addFailure(identifier, failure);
                }
            } else if (identifier.isTest() && result.getStatus() == TestExecutionResult.Status.ABORTED) {
                String reason = result.getThrowable().map(Throwable::getMessage).orElse("Aborted by JUnit");
                this.addSkipped(identifier, reason);
                this.skippedCases.add(identifier);
            }
            Long start = this.starts.remove(identifier);
            Element testcase = this.cases.get(identifier);
            if (testcase != null && start != null) {
                testcase.setAttribute("time", seconds(System.nanoTime() - start));
            }
            String className = this.className(identifier);
            if (className != null) {
                this.suiteEnds.put(className, System.nanoTime());
            }
            this.printProgress(this.starts.isEmpty() ? "cleanup" : "testing", null, null);
        } catch (Exception failure) {
            this.executionFailed(failure);
        }
    }

    @Override
    public synchronized void executionSkipped(TestIdentifier identifier, String reason) {
        // 工作组筛选和快速失败留下真实的未执行计划，不伪造 JUnit 跳过或 XML。
        if (reason.startsWith(JUnitJobCondition.INTERNAL_REASON)) {
            return;
        }
        try {
            if (identifier.isTest()) {
                if (this.selected(identifier)) {
                    this.addSkipped(identifier, reason);
                    this.skippedCases.add(identifier);
                }
            } else {
                for (TestIdentifier child : this.plan.getChildren(identifier)) {
                    this.executionSkipped(child, reason);
                }
            }
            this.printProgress(this.starts.isEmpty() ? "cleanup" : "testing", null, null);
        } catch (Exception failure) {
            this.executionFailed(failure);
        }
    }

    synchronized void addFailure(TestIdentifier identifier, Throwable failure) throws Exception {
        Element testcase = this.addCase(identifier);
        Element failed = testcase.getOwnerDocument().createElement("failure");
        failed.setAttribute("message", xmlText(failure.getMessage()));
        failed.setAttribute("type", failure.getClass().getName());
        failed.setTextContent(xmlText(trace(failure)));
        testcase.appendChild(failed);
        this.failedCases.add(identifier);
        this.printProgress(this.starts.isEmpty() ? "cleanup" : "testing", identifier, failure);
        failure.printStackTrace(this.originalErr);
        this.failureStopped = Boolean.FALSE.equals(this.configuration.get("skipError"));
    }

    boolean failureStopped() {
        return this.failureStopped;
    }

    boolean hasSelectedCases() {
        return !this.plannedCases.isEmpty();
    }

    boolean selected(String className, String method) {
        int parameter = method.indexOf('[');
        List<String> names = new ArrayList<>();
        if (parameter >= 0 && method.endsWith("]")) {
            names.add(method.substring(parameter + 1, method.length() - 1));
        }
        return this.selected(className, method, names);
    }

    boolean selected(String className, String method, List<String> parameterNames) {
        List<String> filters = new ArrayList<>();
        if (this.configuration.get("includes") instanceof List<?> values) {
            values.forEach(value -> filters.add((String) value));
        }
        if (filters.isEmpty() && this.configuration.get("classNames") instanceof List<?> names) {
            names.forEach(value -> filters.add((String) value));
        }
        if (!JUnitJobMain.matchesSelection(filters, className, method)) {
            return false;
        }
        int parameter = method.indexOf('[');
        String declaration = parameter < 0 ? method : method.substring(0, parameter);
        @SuppressWarnings("unchecked")
        Map<String, Object> rpcCases = (Map<String, Object>) this.configuration.get("rpcCases");
        String rpc = (String) this.configuration.get("rpc");
        String version = (String) this.configuration.get("rpcVersion");
        return NxnRpcSelection.selected(rpcCases, rpc, version, className, declaration, parameterNames);
    }

    private boolean selected(TestIdentifier identifier) {
        String className = this.className(identifier);
        if (className == null) {
            return false;
        }
        String method = this.methodName(identifier);
        List<String> parameterNames = this.parameterNames(identifier);
        return this.selected(className, method, parameterNames);
    }

    private List<String> parameterNames(TestIdentifier identifier) {
        List<String> names = new ArrayList<>();
        TestIdentifier parent = this.plan.getParent(identifier).orElse(null);
        while (parent != null) {
            String segment = parent.getUniqueIdObject().getLastSegment().getType();
            if (StringUtils.equals("class-template-invocation", segment)) {
                names.add(parent.getDisplayName());
            }
            parent = this.plan.getParent(parent).orElse(null);
        }
        String segment = identifier.getUniqueIdObject().getLastSegment().getType();
        if (StringUtils.equals("test-template-invocation", segment)) {
            names.add(identifier.getDisplayName());
        }
        return names;
    }

    private String className(TestIdentifier identifier) {
        if (identifier.getSource().orElse(null) instanceof MethodSource source) {
            return source.getClassName();
        }
        if (identifier.getSource().orElse(null) instanceof ClassSource source) {
            return source.getClassName();
        }
        return this.plan == null ? null : this.plan.getParent(identifier).map(this::className).orElse(null);
    }

    private String methodName(TestIdentifier identifier) {
        if (!(identifier.getSource().orElse(null) instanceof MethodSource source)) {
            return "initializationError";
        }
        String name = source.getMethodName();
        // 参数类的显示名称来自父调用节点，普通方法名不附加 Jupiter 的空括号。
        TestIdentifier parent = this.plan.getParent(identifier).orElse(null);
        while (parent != null) {
            String segment = parent.getUniqueIdObject().getLastSegment().getType();
            if (StringUtils.equals("class-template-invocation", segment)) {
                name += "[" + parent.getDisplayName() + "]";
            }
            parent = this.plan.getParent(parent).orElse(null);
        }
        if (StringUtils.equals("test-template-invocation", identifier.getUniqueIdObject().getLastSegment().getType())) {
            name += "[" + identifier.getDisplayName() + "]";
        }
        return name;
    }

    private String caseId(TestIdentifier identifier) {
        return this.className(identifier) + "#" + this.methodName(identifier);
    }

    private synchronized void printProgress(String phase, TestIdentifier failedIdentifier, Throwable failure) {
        if (this.progressWriter == null) {
            return;
        }
        try {
            long now = System.nanoTime();
            String current = this.currentClass;
            long testElapsed = 0;
            for (Map.Entry<TestIdentifier, Long> entry : this.starts.entrySet()) {
                current = this.caseId(entry.getKey());
                testElapsed = (now - entry.getValue()) / 1_000_000;
            }
            Map<String, Object> progress = new LinkedHashMap<>();
            progress.put("runId", this.configuration.get("orchestrationRunId"));
            progress.put("groupId", this.configuration.get("groupId"));
            progress.put("completed", this.cases.size() - this.starts.size());
            progress.put("total", this.total);
            progress.put("failed", this.failedCases.size());
            progress.put("skipped", this.skippedCases.size());
            progress.put("phase", phase);
            progress.put("current", current);
            progress.put("elapsedMillis", (now - this.startedNanos) / 1_000_000);
            progress.put("testElapsedMillis", testElapsed);
            if (failure != null) {
                progress.put("failure", failure.getClass().getName() + ": " + failure.getMessage());
                progress.put("case", this.caseId(failedIdentifier));
            }
            this.progressWriter.write(JSON.writeValueAsString(progress));
            this.progressWriter.newLine();
            this.progressWriter.flush();
        } catch (Exception problem) {
            this.originalErr.println("Cannot append JUnit progress");
            problem.printStackTrace(this.originalErr);
        }
    }

    private void addSkipped(TestIdentifier identifier, String message) throws Exception {
        Element testcase = this.addCase(identifier);
        Element ignored = testcase.getOwnerDocument().createElement("skipped");
        ignored.setAttribute("message", xmlText(message));
        testcase.appendChild(ignored);
    }

    private Element addCase(TestIdentifier identifier) throws Exception {
        Element existing = this.cases.get(identifier);
        if (existing != null) {
            return existing;
        }
        String className = this.className(identifier);
        if (className == null) {
            throw new IOException("JUnit reported a testcase without a class: " + identifier);
        }
        Document document = this.suites.get(className);
        if (document == null) {
            document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
            Element suite = document.createElement("testsuite");
            suite.setAttribute("name", className);
            suite.setAttribute("timestamp", Instant.now().toString());
            suite.setAttribute("hostname", System.getenv().getOrDefault("HOSTNAME", "unknown"));
            suite.appendChild(document.createElement("properties"));
            document.appendChild(suite);
            this.suites.put(className, document);
        }
        this.suiteStarts.putIfAbsent(className, System.nanoTime());
        Element testcase = document.createElement("testcase");
        testcase.setAttribute("name", this.methodName(identifier));
        testcase.setAttribute("classname", className);
        testcase.setAttribute("uniqueId", identifier.getUniqueId());
        testcase.setAttribute("time", "0");
        document.getDocumentElement().appendChild(testcase);
        this.cases.put(identifier, testcase);
        return testcase;
    }

    synchronized void executionFailed(Throwable failure) {
        if (this.executionFailure == null) {
            this.executionFailure = failure;
        } else if (this.executionFailure != failure) {
            this.executionFailure.addSuppressed(failure);
        }
        failure.printStackTrace(this.originalErr);
    }

    int exitCode() {
        if (this.executionFailure != null) {
            return 2;
        }
        return this.failed > 0 ? 1 : 0;
    }

    @Override
    public void close() throws Exception {
        this.printProgress("reporting", null, null);

        try {
            this.writeReports();
        } finally {
            // 报告成功或失败都先追加清理阶段，再释放自己创建的进度文件。
            this.printProgress("cleanup", null, null);
            if (this.progressWriter != null) {
                try {
                    this.progressWriter.close();
                } catch (IOException problem) {
                    this.originalErr.println("Cannot close JUnit progress file");
                    problem.printStackTrace(this.originalErr);
                }
            }
        }
    }

    private void writeReports() throws Exception {
        // 先恢复借入的控制台，再关闭本报告创建的文件，避免关闭调用方的标准流。
        System.out.flush();
        System.err.flush();
        System.setOut(this.originalOut);
        System.setErr(this.originalErr);
        synchronized (this.captured) {
            for (OutputStream stream : this.captured.values()) {
                stream.close();
            }
        }

        // XML 与 selected-cases 使用同一组实际事件；初始化失败和跳过也保留独立身份。
        List<Map<String, Object>> selected = new ArrayList<>();
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        for (Map.Entry<String, Document> entry : this.suites.entrySet()) {
            String className = entry.getKey();
            Document document = entry.getValue();
            Element suite = document.getDocumentElement();
            int failures = 0;
            int ignored = 0;
            var children = suite.getElementsByTagName("testcase");
            for (int index = 0; index < children.getLength(); index++) {
                Element testcase = (Element) children.item(index);
                boolean failure = testcase.getElementsByTagName("failure").getLength() != 0;
                boolean skip = testcase.getElementsByTagName("skipped").getLength() != 0;
                failures += failure ? 1 : 0;
                ignored += skip ? 1 : 0;
                Map<String, Object> outcome = new LinkedHashMap<>();
                outcome.put("caseId", className + "#" + testcase.getAttribute("name"));
                outcome.put("className", className);
                outcome.put("scenario", testcase.getAttribute("name"));
                outcome.put("seconds", testcase.getAttribute("time"));
                String status = "passed";
                if (skip) {
                    status = "skipped";
                }
                if (failure) {
                    status = "failed";
                }
                outcome.put("status", status);
                if (failure) {
                    StringBuilder traces = new StringBuilder();
                    var nodes = testcase.getElementsByTagName("failure");
                    for (int failureIndex = 0; failureIndex < nodes.getLength(); failureIndex++) {
                        traces.append(nodes.item(failureIndex).getTextContent());
                    }
                    outcome.put("failure", traces.toString());
                }
                selected.add(outcome);
            }
            this.failed += failures;
            this.skipped += ignored;
            suite.setAttribute("tests", Integer.toString(children.getLength()));
            suite.setAttribute("failures", Integer.toString(failures));
            suite.setAttribute("errors", "0");
            suite.setAttribute("skipped", Integer.toString(ignored));
            long finish = this.suiteEnds.getOrDefault(className, System.nanoTime());
            suite.setAttribute("time", seconds(finish - this.suiteStarts.get(className)));
            this.appendOutput(document, className, "stdout", "system-out");
            this.appendOutput(document, className, "stderr", "system-err");
            Path xml = this.output.resolve("test-results/test/TEST-" + className + ".xml");
            transformer.transform(new DOMSource(document), new StreamResult(xml.toFile()));
            Path copy = Files.createDirectories(this.report.resolve("test-results")).resolve(xml.getFileName());
            Files.copy(xml, copy, StandardCopyOption.REPLACE_EXISTING);
        }
        writeJson(this.report.resolve("selected-cases.json"), selected);
        this.writeSummary(selected);
    }

    private void appendOutput(Document document, String className, String stream, String tag) throws IOException {
        Path file = this.output.resolve("test-output").resolve(className + "." + stream + ".log");
        Element node = document.createElement(tag);
        if (Files.isRegularFile(file)) {
            node.setTextContent(xmlText(Files.readString(file, StandardCharsets.UTF_8)));
        }
        document.getDocumentElement().appendChild(node);
    }

    private void writeSummary(List<Map<String, Object>> selected) throws IOException {
        Map<String, Object> cleanup = this.registryCleanup();
        Map<String, Object> summary = new LinkedHashMap<>();
        List<String> identity = List.of("runId", "orchestrationRunId", "groupId", "includes",
                "rpc", "rpcVersion", "rpcCases");
        for (String key : identity) {
            summary.put(key, this.configuration.get(key));
        }
        summary.put("executionModel", "jupiter-independent-jvm");
        summary.put("timings", Map.of("testWallSeconds", (System.nanoTime() - this.startedNanos) / 1e9,
                "compilationTaskSeconds", 0, "tasks", List.of()));
        summary.put("tests", selected.size());
        summary.put("passed", selected.size() - this.failed - this.skipped);
        summary.put("failed", this.failed);
        summary.put("skipped", this.skipped);
        // 未开始的计划项单独归档，不伪造 XML，也不计入通过或跳过数。
        Set<String> executed = new LinkedHashSet<>();
        for (Map<String, Object> testcase : selected) {
            executed.add((String) testcase.get("caseId"));
        }
        List<String> planned = new ArrayList<>();
        List<String> notExecuted = new ArrayList<>();
        for (TestIdentifier identifier : this.plannedCases) {
            String caseId = this.caseId(identifier);
            planned.add(caseId);
            if (!executed.contains(caseId)) {
                notExecuted.add(caseId);
            }
        }
        summary.put("plannedCases", planned);
        summary.put("notExecuted", notExecuted.size());
        summary.put("notExecutedCases", notExecuted);
        summary.put("skipError", this.configuration.getOrDefault("skipError", true));
        summary.put("testTaskStarted", this.testTaskStarted);
        summary.put("resultsAvailable", !selected.isEmpty());
        String rpc = (String) this.configuration.get("rpc");
        boolean selectionEmpty = !rpc.isBlank() && this.plannedCases.isEmpty() && this.executionFailure == null;
        summary.put("selectionEmpty", selectionEmpty);
        summary.put("reusedResults", false);
        summary.put("excludedPreviousResultFiles", 0);
        summary.put("taskFailure", this.executionFailure == null ? null : trace(this.executionFailure));
        summary.put("registryCleanup", cleanup);
        summary.put("resourceScope", "Job fixture leases; JVM termination and Compose cleanup are verified externally");
        writeJson(this.report.resolve("summary.json"), summary);
    }

    private Map<String, Object> registryCleanup() throws IOException {
        Map<String, Object> services = new LinkedHashMap<>();
        List<String> observedErrors = new ArrayList<>();
        String configured = System.getProperty("meteor.test.container.workDirectory");
        Path work = configured == null ? this.output.resolve("container-scenarios") : Path.of(configured);
        Path directory = work.resolve("registry-services");
        if (Files.isDirectory(directory)) {
            try (Stream<Path> children = Files.list(directory)) {
                for (Path child : children.filter(Files::isDirectory).sorted().toList()) {
                    Properties state = new Properties();
                    Path lifecycle = child.resolve("lifecycle.properties");
                    if (Files.isRegularFile(lifecycle)) {
                        try (var input = Files.newInputStream(lifecycle)) {
                            state.load(input);
                        }
                    }
                    String status = state.getProperty("state", "missing");
                    Map<String, String> service = Map.of("directory", child.toString(), "state", status);
                    services.put(child.getFileName().toString(), service);
                    if (Files.exists(child.resolve("cleanup-error.log"))) {
                        observedErrors.add("Registry fixture cleanup failed: " + child.resolve("cleanup-error.log"));
                    }
                }
            }
        }
        // 关闭钩子尚未执行，外层必须在真实 JVM 退出后读取 lifecycle 和 cleanup-error 再验收。
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("successful", null);
        snapshot.put("verificationPending", true);
        snapshot.put("services", services);
        snapshot.put("observedErrors", observedErrors);
        snapshot.put("directory", directory.toString());
        snapshot.put("scope", "Validate fixture leases after JVM exit; Compose owns registry services");
        return snapshot;
    }

    private static Map<String, String> properties(String prefix) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String name : System.getProperties().stringPropertyNames().stream().sorted().toList()) {
            if (name.startsWith(prefix)) {
                values.put(name, System.getProperty(name));
            }
        }
        return values;
    }

    private static Map<String, String> containerEnvironment() {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> included = Set.of("METEOR_TEST_NETWORK", "METEOR_TEST_PLATFORM", "METEOR_TEST_RUN_ID",
                "METEOR_TEST_HOST_PLATFORM", "METEOR_TEST_RUNNER_HOST", "METEOR_TEST_GROUP_ID");
        for (String name : System.getenv().keySet().stream().sorted().toList()) {
            boolean service = name.startsWith("METEOR_TEST_SERVICE_") || name.startsWith("METEOR_TEST_HOST_");
            if (name.startsWith("METEOR_TEST_IMAGE_") || service || included.contains(name)) {
                values.put(name, System.getenv(name));
            }
        }
        return values;
    }

    private static void writeJson(Path file, Object value) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }

    private static String seconds(long nanoseconds) {
        return Double.toString(Math.max(0, nanoseconds) / 1e9);
    }

    private static String trace(Throwable failure) {
        StringWriter text = new StringWriter();
        failure.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static String xmlText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder text = new StringBuilder(value.length());
        value.codePoints().filter(JUnitJobReport::validXmlCharacter).forEach(text::appendCodePoint);
        return text.toString();
    }

    private static boolean validXmlCharacter(int code) {
        boolean whitespace = code == 9 || code == 10 || code == 13;
        boolean basic = code >= 32 && code <= 0xd7ff;
        boolean supplementary = code >= 0xe000 && code <= 0xfffd;
        boolean extended = code >= 0x10000 && code <= 0x10ffff;
        return whitespace || basic || supplementary || extended;
    }
}
