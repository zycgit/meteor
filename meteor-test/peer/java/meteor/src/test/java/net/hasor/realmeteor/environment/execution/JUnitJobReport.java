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
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** 保存本 JVM 实际执行的 JUnit 实例、原始输出和与现有聚合兼容的 XML/JSON。 */
final class JUnitJobReport extends RunListener implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path output;
    private final Path report;
    private final Map<String, Object> configuration;
    private final RunNotifier notifier;
    private final Set<Description> plannedCases = new LinkedHashSet<>();
    private final Map<String, Document> suites = new LinkedHashMap<>();
    private final Map<Description, Element> cases = new LinkedHashMap<>();
    private final Map<Description, Long> starts = new LinkedHashMap<>();
    private final Set<Description> startedCases = new LinkedHashSet<>();
    private final Set<Description> failedCases = new LinkedHashSet<>();
    private final Set<Description> skippedCases = new LinkedHashSet<>();
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

    JUnitJobReport(Path output, Map<String, Object> selection, RunNotifier notifier) throws Exception {
        this.output = output.toAbsolutePath();
        this.configuration = new LinkedHashMap<>(selection);
        this.notifier = notifier;
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
        this.configuration.put("executionModel", "junit4-independent-jvm");
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
    public synchronized void testRunStarted(Description description) {
        this.testTaskStarted = true;
        this.total = description.testCount();
        this.collectPlannedCases(description);
        this.printProgress("preparing", null);
    }

    private void collectPlannedCases(Description description) {
        if (description.getChildren().isEmpty()) {
            this.plannedCases.add(description);
            return;
        }
        for (Description child : description.getChildren()) {
            this.collectPlannedCases(child);
        }
    }

    @Override
    public synchronized void testSuiteStarted(Description description) {
        String name = description.getClassName();
        if (this.configuration.get("classNames") instanceof List<?> names && names.contains(name)) {
            this.currentClass = name;
            this.suiteStarts.putIfAbsent(name, System.nanoTime());
            this.printProgress("preparing", null);
        }
    }

    @Override
    public synchronized void testSuiteFinished(Description description) {
        String name = description.getClassName();
        if (this.suiteStarts.containsKey(name)) {
            this.suiteEnds.put(name, System.nanoTime());
            this.printProgress("cleanup", null);
        }
    }

    @Override
    public synchronized void testStarted(Description description) throws Exception {
        if (!this.startedCases.add(description)) {
            this.executionFailed(new IllegalStateException("Duplicate JUnit testcase identity: " + description));
        }
        this.currentClass = description.getClassName();
        this.addCase(description);
        this.starts.put(description, System.nanoTime());
        this.originalOut.println("[JUnit] " + description.getClassName() + "#" + description.getMethodName());
        this.printProgress("testing", null);
    }

    @Override
    public synchronized void testFinished(Description description) {
        Long start = this.starts.remove(description);
        Element testcase = this.cases.get(description);
        if (testcase != null && start != null) {
            testcase.setAttribute("time", seconds(System.nanoTime() - start));
            this.suiteEnds.put(testcase.getAttribute("classname"), System.nanoTime());
        }
        this.printProgress(this.starts.isEmpty() ? "cleanup" : "testing", null);
    }

    @Override
    public synchronized void testFailure(Failure failure) throws Exception {
        Element testcase = this.addCase(failure.getDescription());
        Element failed = testcase.getOwnerDocument().createElement("failure");
        failed.setAttribute("message", xmlText(failure.getMessage()));
        failed.setAttribute("type", failure.getException().getClass().getName());
        failed.setTextContent(xmlText(failure.getTrace()));
        testcase.appendChild(failed);
        this.failedCases.add(failure.getDescription());
        this.printProgress(this.starts.isEmpty() ? "cleanup" : "testing", failure);

        // 自定义 JUnit 监听器自行输出完整异常，外层运行日志才能立即显示失败堆栈。
        failure.getException().printStackTrace(this.originalErr);
        if (Boolean.FALSE.equals(this.configuration.get("skipError"))) {
            this.notifier.pleaseStop();
        }
    }

    @Override
    public synchronized void testAssumptionFailure(Failure failure) {
        try {
            this.addSkipped(failure.getDescription(), failure.getMessage());
            this.skippedCases.add(failure.getDescription());
            this.printProgress(this.starts.isEmpty() ? "cleanup" : "testing", null);
        } catch (Exception reportFailure) {
            this.executionFailed(reportFailure);
        }
    }

    @Override
    public synchronized void testIgnored(Description description) throws Exception {
        this.addSkipped(description, "Ignored by JUnit");
        this.skippedCases.add(description);
        this.printProgress(this.starts.isEmpty() ? "cleanup" : "testing", null);
    }

    private synchronized void printProgress(String phase, Failure failure) {
        if (this.progressWriter == null) {
            return;
        }
        try {
            // 沿用实际 JUnit 身份，逐事件追加并刷新，编排器独立读取本组的进度文件。
            long now = System.nanoTime();
            String current = this.currentClass;
            long testElapsed = 0;
            for (Map.Entry<Description, Long> entry : this.starts.entrySet()) {
                Description description = entry.getKey();
                current = description.getClassName() + "#" + description.getMethodName();
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
                Throwable cause = failure.getException();
                Description description = failure.getDescription();
                String method = description.getMethodName();
                if (method == null) {
                    method = "initializationError";
                }
                progress.put("failure", cause.getClass().getName() + ": " + failure.getMessage());
                progress.put("case", description.getClassName() + "#" + method);
            }
            this.progressWriter.write(JSON.writeValueAsString(progress));
            this.progressWriter.newLine();
            this.progressWriter.flush();
        } catch (Exception problem) {
            // 进度展示不参与结果判定，不能改变原有 XML、退出码和资源清理流程。
            this.originalErr.println("Cannot append JUnit progress");
            problem.printStackTrace(this.originalErr);
        }
    }

    private void addSkipped(Description description, String message) throws Exception {
        Element testcase = this.addCase(description);
        Element ignored = testcase.getOwnerDocument().createElement("skipped");
        ignored.setAttribute("message", xmlText(message));
        testcase.appendChild(ignored);
    }

    private Element addCase(Description description) throws Exception {
        Element existing = this.cases.get(description);
        if (existing != null) {
            return existing;
        }
        String className = description.getClassName();
        if (className == null) {
            throw new IOException("JUnit reported a testcase without a class: " + description);
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
        String method = description.getMethodName();
        Element testcase = document.createElement("testcase");
        testcase.setAttribute("name", method == null ? "initializationError" : method);
        testcase.setAttribute("classname", className);
        testcase.setAttribute("time", "0");
        document.getDocumentElement().appendChild(testcase);
        this.cases.put(description, testcase);
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
        this.printProgress("reporting", null);

        try {
            this.writeReports();
        } finally {
            // 报告成功或失败都先追加清理阶段，再释放自己创建的进度文件。
            this.printProgress("cleanup", null);
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
        for (String key : List.of("runId", "orchestrationRunId", "groupId", "includes")) {
            summary.put(key, this.configuration.get(key));
        }
        summary.put("executionModel", "junit4-independent-jvm");
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
        for (Description description : this.plannedCases) {
            String method = description.getMethodName();
            if (method == null) {
                method = "initializationError";
            }
            String caseId = description.getClassName() + "#" + method;
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
