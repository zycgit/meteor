/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.hasor.net
 */
package net.hasor.meteor.test.nxn;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.ObjectUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Validates and merges evidence from the current persistent-container JUnit groups. */
public final class NxnReport {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SDK_CASE = Pattern.compile(
            "\\[sdk/([^/\\[\\]\\r\\n]+)/([^/\\[\\]\\r\\n]+)/([^/@\\[\\]\\r\\n]+)@([^/\\[\\]\\r\\n]+)/"
                    + "([^/\\[\\]\\r\\n]+)(?:/(SDK-to-Meteor|Meteor-to-SDK))?\\]$");

    private NxnReport() {
    }

    public static int run(String[] arguments) {
        try {
            // 命令入口只解析报告参数，不启动环境或复用历史测试结果。
            Map<String, String> options = new LinkedHashMap<>();
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                boolean recognized = StringUtils.equals(option, "--manifest");
                recognized |= StringUtils.equals(option, "--build-directory");
                recognized |= StringUtils.equals(option, "--baseline");
                if (!recognized || index + 1 == arguments.length || options.containsKey(option)) {
                    throw new IllegalArgumentException("Invalid report argument: " + option);
                }
                options.put(option, arguments[++index]);
            }
            if (!options.containsKey("--manifest") || !options.containsKey("--build-directory")) {
                throw new IllegalArgumentException("Report requires --manifest and --build-directory");
            }
            Path baseline = null;
            if (options.containsKey("--baseline")) {
                baseline = Path.of(options.get("--baseline"));
            }
            Path manifest = Path.of(options.get("--manifest"));
            Path buildDirectory = Path.of(options.get("--build-directory"));
            Map<String, Object> summary = aggregate(manifest, buildDirectory, baseline);
            List<String> errors = strings(summary.get("aggregationErrors"));
            for (String error : errors) {
                System.err.println(error);
            }
            return errors.isEmpty() ? 0 : 1;
        } catch (Exception failure) {
            System.err.println("Cannot aggregate current test groups: " + failure.getMessage());
            return 1;
        }
    }

    public static Map<String, Object> aggregate(Path manifestPath, Path buildDirectory, Path baselinePath)
            throws Exception {
        Map<String, Object> manifest = object(readJson(manifestPath));
        if (!integerEquals(manifest.get("schemaVersion"), 3)) {
            throw new IllegalArgumentException("Expected persistent-container manifest schemaVersion 3");
        }
        String runId = string(manifest.get("runId"));
        if (runId.isEmpty() || !StringUtils.equals(Path.of(runId).getFileName().toString(), runId)) {
            throw new IllegalArgumentException("Invalid run identity");
        }
        Path report = buildDirectory.resolve("interoperability");
        if (Files.exists(report)) {
            try (Stream<Path> paths = Files.walk(report)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }

        // 本轮清单是唯一输入，清理总结果目录中的旧 XML 后再收集当前分组。
        List<String> errors = new ArrayList<>();
        validateAssignments(manifest, errors);
        errors.addAll(strings(manifest.getOrDefault("orchestrationErrors", List.of())));
        Files.createDirectories(report);
        Path results = buildDirectory.resolve("test-results/test");
        Files.createDirectories(results);
        for (Path old : xmlFiles(results)) {
            Files.delete(old);
        }
        List<Map<String, Object>> cases = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();
        List<String> plannedCases = new ArrayList<>();
        List<String> notExecutedCases = new ArrayList<>();
        Map<String, Map<String, Path>> suites = new LinkedHashMap<>();
        Set<String> executedClasses = new LinkedHashSet<>();
        boolean rpcSelection = present(manifest.get("rpc"));
        for (Map<String, Object> group : objects(manifest.get("groups"))) {
            List<Map<String, Object>> current = new ArrayList<>();
            List<Path> files = new ArrayList<>();
            Path manifestDirectory = manifestPath.toAbsolutePath().getParent();
            Map<String, Object> detail = collectGroup(group, manifest, manifestDirectory, errors, current, files);
            details.add(detail);
            plannedCases.addAll(strings(detail.getOrDefault("plannedCases", List.of())));
            notExecutedCases.addAll(strings(detail.getOrDefault("notExecutedCases", List.of())));
            Set<String> currentClasses = new LinkedHashSet<>();
            for (Map<String, Object> testCase : current) {
                currentClasses.add(string(testCase.get("className")));
            }
            executedClasses.addAll(currentClasses);
            String groupId = string(group.get("id"));
            Set<String> assignedClasses = new LinkedHashSet<>(strings(group.get("classes")));
            boolean rpcVerified = Boolean.TRUE.equals(detail.get("rpcSelectionVerified"));
            boolean classesCovered = currentClasses.equals(assignedClasses);
            if (rpcVerified) {
                // 参数展开后可能没有该协议向量，已核验的叶子计划决定实际执行范围。
                classesCovered = assignedClasses.containsAll(currentClasses);
            }
            if (!classesCovered) {
                errors.add("Group " + groupId + " executed classes differ from its assignment");
            }
            List<String> filters = strings(group.getOrDefault("filters", group.get("classes")));
            checkSelections(groupId, current, filters, !rpcVerified, errors);
            cases.addAll(current);
            for (Path source : files) {
                String filename = source.getFileName().toString();
                suites.computeIfAbsent(filename, ignored -> new LinkedHashMap<>()).put(groupId, source);
            }
        }
        for (Map.Entry<String, Map<String, Path>> suite : suites.entrySet()) {
            NxnJUnitReport.mergeSuites(suite.getValue(), results.resolve(suite.getKey()));
        }
        Set<String> selectedClasses = new LinkedHashSet<>(strings(manifest.get("selectedClasses")));
        boolean rpcCovered = rpcSelection && allTrue(details, "rpcSelectionVerified");
        boolean classesCovered = executedClasses.equals(selectedClasses);
        if (rpcCovered) {
            classesCovered = selectedClasses.containsAll(executedClasses);
        }
        if (!classesCovered) {
            errors.add("Executed classes do not cover selected classes");
        }
        if (cases.isEmpty()) {
            errors.add("No testcases were executed");
        }

        // 校验每个参数化用例恰好执行一次，失败和跳过都不能被汇总成功掩盖。
        List<String> duplicateCases = new ArrayList<>();
        for (Map.Entry<String, Integer> count : caseIdentities(cases).entrySet()) {
            if (count.getValue() != 1) {
                duplicateCases.add(count.getKey());
            }
        }
        duplicateCases.sort(Comparator.naturalOrder());
        if (!duplicateCases.isEmpty()) {
            errors.add("Duplicate testcase identities across group results");
        }
        cases.sort(Comparator.comparing(testCase -> string(testCase.get("caseId"))));
        plannedCases.sort(Comparator.naturalOrder());
        notExecutedCases.sort(Comparator.naturalOrder());
        if (hasDuplicate(counts(plannedCases))) {
            errors.add("Duplicate planned testcase identities across group results");
        }
        int failed = countStatus(cases, "failed");
        int skipped = countStatus(cases, "skipped");
        if (failed != 0 || skipped != 0) {
            errors.add("The aggregate contains failed or skipped testcases");
        }
        List<Map<String, Object>> failedCases = new ArrayList<>();
        for (Map<String, Object> testCase : cases) {
            if (StringUtils.equals("failed", string(testCase.get("status")))) {
                Map<String, Object> failure = new LinkedHashMap<>();
                failure.put("caseId", testCase.get("caseId"));
                failure.put("failure", testCase.get("failure"));
                failedCases.add(failure);
            }
        }
        Map<String, Object> baseline = baseline(manifest, cases, baselinePath, errors);

        // 输出结构与原报告保持一致，原始进程和配置快照一并归档。
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("orchestrationRunId", runId);
        summary.put("parallel", true);
        summary.put("skipError", manifest.getOrDefault("skipError", true));
        summary.put("tests", cases.size());
        summary.put("passed", cases.size() - failed - skipped);
        summary.put("failed", failed);
        summary.put("failedCases", failedCases);
        summary.put("skipped", skipped);
        summary.put("sdkCompatibility", sdkCompatibility(cases));
        summary.put("plannedCases", plannedCases);
        summary.put("notExecuted", notExecutedCases.size());
        summary.put("notExecutedCases", notExecutedCases);
        summary.put("notExecutedUnknown", anyTrue(details, "notExecutedUnknown"));
        summary.put("classes", executedClasses.size());
        summary.put("resultsAvailable", !cases.isEmpty());
        summary.put("testTaskStarted", !details.isEmpty() && allTrue(details, "testTaskStarted"));
        summary.put("reusedResults", anyTrue(details, "reusedResults"));
        summary.put("taskFailure", errors.isEmpty() ? null : String.join("; ", errors));
        summary.put("aggregationErrors", errors);
        summary.put("duplicateCases", duplicateCases);
        summary.put("includes", manifest.getOrDefault("userIncludes", List.of()));
        summary.put("rpc", manifest.getOrDefault("rpc", ""));
        summary.put("rpcVersion", manifest.getOrDefault("rpcVersion", ""));
        summary.put("rpcCases", manifest.getOrDefault("rpcCases", Map.of()));
        Map<String, Object> mergedSuites = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Path>> suite : suites.entrySet()) {
            if (suite.getValue().size() > 1) {
                mergedSuites.put(suite.getKey(), new ArrayList<>(suite.getValue().keySet()));
            }
        }
        summary.put("mergedSuites", mergedSuites);
        Map<String, Object> timings = new LinkedHashMap<>(object(manifest.getOrDefault("timings", Map.of())));
        double seconds = 0;
        for (Map<String, Object> testCase : cases) {
            seconds += Double.parseDouble(string(testCase.get("seconds")));
        }
        timings.put("junitSeconds", seconds);
        summary.put("timings", timings);
        summary.put("baseline", baseline);
        summary.put("groups", details);
        Path copiedResults = report.resolve("test-results");
        Files.createDirectories(copiedResults);
        for (Path source : xmlFiles(results)) {
            Files.copy(source, copiedResults.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
        writeJson(report.resolve("selected-cases.json"), cases);
        writeJson(report.resolve("configuration.json"), manifest);
        writeJson(report.resolve("summary.json"), summary);
        writeMarkdown(report.resolve("report.md"), summary);
        System.out.println("Interoperability results: " + report);
        return summary;
    }

    private static void writeMarkdown(Path destination, Map<String, Object> summary) throws IOException {
        // 报告所有者直接使用已核验的数据写摘要，外层无需重新读取大型进程证据。
        List<String> errors = strings(summary.get("aggregationErrors"));
        StringBuilder markdown = new StringBuilder("# Meteor RPC 测试报告\n\n");
        markdown.append("结果：").append(errors.isEmpty() ? "通过" : "失败").append("。\n\n");
        markdown.append("skipError：`").append(summary.get("skipError")).append("`。\n\n");
        boolean rpcSelection = present(summary.get("rpc"));
        if (rpcSelection) {
            markdown.append("RPC：`").append(summary.get("rpc")).append('`');
            if (present(summary.get("rpcVersion"))) {
                markdown.append("，协议版本：`").append(summary.get("rpcVersion")).append('`');
            } else {
                markdown.append("，协议版本不限");
            }
            markdown.append("。\n\n");
        }
        List<String> includes = strings(summary.get("includes"));
        markdown.append("用例筛选：");
        if (includes.isEmpty()) {
            markdown.append(rpcSelection ? "该协议声明范围" : "全部");
        } else {
            markdown.append('`').append(String.join("`、`", includes)).append('`');
        }
        markdown.append("。\n\n通过 ").append(summary.get("passed"));
        markdown.append(" 项，失败 ").append(summary.get("failed"));
        markdown.append(" 项，跳过 ").append(summary.get("skipped"));
        markdown.append(" 项，已确认未执行 ").append(summary.get("notExecuted")).append(" 项。\n\n");
        boolean unknown = Boolean.TRUE.equals(summary.get("notExecutedUnknown"));
        if (unknown) {
            markdown.append("部分工作组缺少完整执行报告，未执行总数不确定。\n\n");
        }
        markdown.append("详细结果：[summary.json](summary.json)\n");

        // 版本与方向只来自已执行用例，清单中声明的依赖不能充当通过证据。
        List<Map<String, Object>> sdkVersions = objects(summary.get("sdkCompatibility"));
        if (!sdkVersions.isEmpty()) {
            markdown.append("\n## SDK 版本兼容\n\n");
            markdown.append("— 表示本轮未执行该方向，不表示 SDK 不支持。\n\n");
            markdown.append("| 语言 | 协议 | SDK | 版本 | 运行图 | SDK → Meteor | Meteor → SDK |\n");
            markdown.append("| --- | --- | --- | --- | --- | --- | --- |\n");
            for (Map<String, Object> sdk : sdkVersions) {
                for (String field : List.of("language", "protocol", "sdk", "version", "profile")) {
                    String value = string(sdk.get(field)).replace("|", "\\|");
                    markdown.append("| ").append(value).append(' ');
                }
                markdown.append("| ").append(sdkStatus(sdk.get("sdkToMeteor")));
                markdown.append(" | ").append(sdkStatus(sdk.get("meteorToSdk"))).append(" |\n");
            }
        }

        // 原始原因保留于 XML 和 JSON；Markdown 展开制表符，避免缩进混用和尾部空白。
        List<Map<String, Object>> failures = objects(summary.get("failedCases"));
        if (!failures.isEmpty()) {
            markdown.append("\n## 失败用例\n");
            for (Map<String, Object> failure : failures) {
                markdown.append("\n- `").append(failure.get("caseId")).append("`\n\n");
                for (String line : string(failure.get("failure")).split("\n", -1)) {
                    String rendered = line.replace("\t", "    ").stripTrailing();
                    if (!rendered.isEmpty()) {
                        markdown.append("      ").append(rendered);
                    }
                    markdown.append('\n');
                }
            }
        }
        List<String> notExecuted = strings(summary.get("notExecutedCases"));
        if (!notExecuted.isEmpty()) {
            markdown.append("\n## 未执行用例\n\n");
            for (String testcase : notExecuted) {
                markdown.append("- `").append(testcase).append("`\n");
            }
        }
        if (unknown) {
            markdown.append("\n## 执行情况未知的工作组\n\n");
            for (Map<String, Object> group : objects(summary.get("groups"))) {
                if (Boolean.TRUE.equals(group.get("notExecutedUnknown"))) {
                    List<String> scope = strings(group.get("notExecutedScope"));
                    markdown.append("- `").append(group.get("id")).append("`，分配范围：`");
                    markdown.append(String.join("`、`", scope)).append("`。\n");
                }
            }
        }
        if (!errors.isEmpty()) {
            markdown.append("\n## 汇总异常\n\n");
            for (String error : errors) {
                markdown.append("- ").append(error.replace("\n", "\n  ")).append('\n');
            }
        }
        Files.writeString(destination, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static List<Map<String, Object>> sdkCompatibility(List<Map<String, Object>> cases) {
        Map<List<String>, Map<String, Object>> versions = new LinkedHashMap<>();
        for (Map<String, Object> testCase : cases) {
            // 语言、SDK 版本和运行图均参与身份，防止不同依赖或编码的结果互相覆盖。
            String caseId = string(testCase.get("caseId"));
            Matcher parameter = SDK_CASE.matcher(caseId);
            if (!parameter.find()) {
                continue;
            }
            String direction = sdkDirection(caseId, parameter);
            if (direction == null) {
                continue;
            }
            List<String> identity = List.of(parameter.group(1), parameter.group(2), //
                    parameter.group(3), parameter.group(4), parameter.group(5));
            Map<String, Object> sdk = versions.get(identity);
            if (sdk == null) {
                sdk = new LinkedHashMap<>();
                sdk.put("language", identity.get(0));
                sdk.put("protocol", identity.get(1));
                sdk.put("sdk", identity.get(2));
                sdk.put("version", identity.get(3));
                sdk.put("profile", identity.get(4));
                sdk.put("sdkToMeteor", "not-executed");
                sdk.put("meteorToSdk", "not-executed");
                versions.put(identity, sdk);
            }

            // 同一方向有多项用例时，失败优先，其次跳过，不能被其它通过项冲掉。
            String previous = string(sdk.get(direction));
            String status = string(testCase.get("status"));
            boolean replace = StringUtils.equals("not-executed", previous);
            replace |= StringUtils.equals("failed", status);
            boolean skipped = StringUtils.equals("skipped", status);
            replace |= skipped && !StringUtils.equals("failed", previous);
            if (replace) {
                sdk.put(direction, status);
            }
        }
        return new ArrayList<>(versions.values());
    }

    private static String sdkDirection(String caseId, Matcher parameter) {
        // 未在参数声明方向时，只接受方法名里的唯一方向标识，不推测消费者或提供者角色。
        int methodStart = caseId.lastIndexOf('#', parameter.start()) + 1;
        String method = caseId.substring(methodStart, parameter.start());
        boolean sdkToMeteor = StringUtils.containsIgnoreCase(method, "SdkToMeteor");
        boolean meteorToSdk = StringUtils.containsIgnoreCase(method, "MeteorToSdk");
        if (sdkToMeteor && meteorToSdk) {
            return null;
        }
        String declared = parameter.group(6);
        if (declared == null) {
            if (sdkToMeteor) {
                return "sdkToMeteor";
            }
            if (meteorToSdk) {
                return "meteorToSdk";
            }
            return null;
        }

        // 参数与方法名都声明方向时必须一致，冲突证据保留在原始报告中，不进入版本表。
        if (StringUtils.equals("SDK-to-Meteor", declared)) {
            return meteorToSdk ? null : "sdkToMeteor";
        }
        return sdkToMeteor ? null : "meteorToSdk";
    }

    private static String sdkStatus(Object status) {
        return switch (string(status)) {
            case "passed" -> "通过";
            case "failed" -> "失败";
            case "skipped" -> "跳过";
            default -> "—";
        };
    }

    private static Map<String, Object> collectGroup(Map<String, Object> group, Map<String, Object> manifest,
            Path manifestDirectory, List<String> errors, List<Map<String, Object>> cases, List<Path> files) {
        String groupId = string(group.get("id"));
        String runId = string(manifest.get("runId"));
        Path declaredDirectory = Path.of(string(group.get("outputDirectory")));
        Path directory = declaredDirectory;
        if (!directory.isAbsolute()) {
            directory = manifestDirectory.resolve(directory);
        }
        // 归档内的 groups 清单只读取相邻分组，宿主离线报告不访问容器原路径。
        if (StringUtils.equals("groups", manifestDirectory.getFileName().toString())) {
            directory = manifestDirectory.resolve(groupId);
        }
        Object filters = group.getOrDefault("includes", group.getOrDefault("filters", group.get("classes")));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", groupId);
        detail.put("classes", group.get("classes"));
        detail.put("filters", filters);
        detail.put("notExecutedUnknown", true);
        detail.put("notExecutedScope", filters);
        detail.put("outputDirectory", directory.toString());
        detail.put("exitCode", group.get("exitCode"));
        detail.put("wallSeconds", group.get("wallSeconds"));
        if (!integerEquals(group.get("exitCode"), 0)) {
            errors.add("Group " + groupId + " exited with " + group.get("exitCode"));
        }
        if (present(group.get("cleanupFailures"))) {
            errors.add("Group " + groupId + " cleanup failed: " + group.get("cleanupFailures"));
        }

        // 即使 JVM 未生成报告，也先验证进程、远端程序和注册中心的释放证据。
        try {
            Map<String, Object> process = checkProcess(directory.resolve("process"), runId, groupId, errors, true);
            detail.put("processExit", process);
            if (!object(process.get("status")).get("exitCode").equals(group.get("exitCode"))) {
                errors.add("Group " + groupId + " process exit does not match successful execution");
            }
        } catch (Exception failure) {
            errors.add("Group " + groupId + " process evidence is invalid: " + failure.getMessage());
        }
        List<Path> launches = new ArrayList<>();
        List<Map<String, Object>> processes = new ArrayList<>();
        detail.put("peerProcesses", processes);
        try {
            launches = namedFiles(directory.resolve("process-evidence"), "launch.json");
            for (Path path : launches) {
                try {
                    processes.add(checkProcess(path.getParent(), runId, groupId, errors, false));
                } catch (Exception failure) {
                    errors.add("Group " + groupId + " peer process evidence is invalid: " + failure.getMessage());
                }
            }
            Map<String, Object> cleanup = checkRemoteCleanup(group, processes, launches,
                    declaredDirectory, directory, errors);
            detail.put("remoteCleanup", cleanup);
        } catch (Exception failure) {
            errors.add("Group " + groupId + " remote cleanup evidence is invalid: " + failure.getMessage());
            detail.put("remoteCleanup", Map.of("successful", false));
        }
        try {
            detail.put("registryCleanup", checkRegistryCleanup(directory, groupId, errors));
        } catch (Exception failure) {
            errors.add("Group " + groupId + " registry cleanup evidence is invalid: " + failure.getMessage());
            detail.put("registryCleanup", Map.of("successful", false));
        }

        // 固定目录中的清单身份阻止拾取旧 XML、旧 selected-cases 或旧配置。
        try {
            String reportId = runId + "-" + groupId;
            Path report = directory.resolve("interoperability");
            Map<String, Object> summary = object(readJson(report.resolve("summary.json")));
            Map<String, Object> configuration = object(readJson(report.resolve("configuration.json")));
            List<Map<String, Object>> reported = objects(readJson(report.resolve("selected-cases.json")));
            checkReportIdentity("summary", summary, groupId, runId, reportId, filters, errors);
            checkReportIdentity("configuration", configuration, groupId, runId, reportId, filters, errors);
            if (!sorted(configuration.getOrDefault("classNames", List.of())).equals(sorted(group.get("classes")))) {
                errors.add("Group " + groupId + " configured classes differ from its assignment");
            }
            Object matrix = manifest.getOrDefault("matrixSelection", Map.of());
            if (!configuration.getOrDefault("matrixSelection", Map.of()).equals(matrix)) {
                errors.add("Group " + groupId + " changed the matrix selection");
            }
            boolean rpcVerified = checkRpcSelection(group, manifest, configuration, summary, errors);
            detail.put("rpcSelectionVerified", rpcVerified);
            boolean selectionEmpty = rpcVerified && Boolean.TRUE.equals(summary.get("selectionEmpty"));
            boolean started = Boolean.TRUE.equals(summary.get("testTaskStarted"));
            boolean fresh = Boolean.FALSE.equals(summary.get("reusedResults"));
            if (!started || !fresh) {
                errors.add("Group " + groupId + " did not execute fresh tests");
            }
            if (!Boolean.TRUE.equals(summary.get("resultsAvailable")) && !selectionEmpty) {
                errors.add("Group " + groupId + " has no current results");
            }
            if (present(summary.get("taskFailure"))) {
                errors.add("Group " + groupId + " test execution failed: " + summary.get("taskFailure"));
            }
            detail.put("testTaskStarted", Boolean.TRUE.equals(summary.get("testTaskStarted")));
            detail.put("reusedResults", !Boolean.FALSE.equals(summary.get("reusedResults")));
            detail.put("configuration", configuration);
            detail.put("timings", summary.getOrDefault("timings", Map.of()));
            Map<String, Object> registry = object(summary.getOrDefault("registryCleanup", Map.of()));
            if (Boolean.FALSE.equals(registry.get("successful"))) {
                errors.add("Group " + groupId + " reported unsuccessful registry cleanup");
            }
            List<Path> currentFiles = xmlFiles(directory.resolve("test-results/test"));
            List<Path> archived = xmlFiles(report.resolve("test-results"));
            boolean missingXml = currentFiles.isEmpty() && !selectionEmpty;
            if (missingXml || !fileHashes(currentFiles).equals(fileHashes(archived))) {
                errors.add("Group " + groupId + " raw XML does not match the current report");
            }
            List<Map<String, Object>> current = NxnJUnitReport.readCases(currentFiles);
            if (selectionEmpty && (!current.isEmpty() || !reported.isEmpty())) {
                errors.add("Group " + groupId + " claims an empty RPC selection but has testcases");
            }
            if (rpcVerified && current.isEmpty() && !selectionEmpty) {
                errors.add("Group " + groupId + " empty RPC result has no explicit zero selection evidence");
            }
            if (rpcVerified) {
                Set<String> planned = new LinkedHashSet<>(strings(summary.get("plannedCases")));
                if (!planned.containsAll(caseIdentities(current).keySet())) {
                    errors.add("Group " + groupId + " executed testcases outside its RPC selection plan");
                }
                planned.addAll(caseIdentities(current).keySet());
                checkRpcCases(group, planned, errors);
            }
            if (!caseCounts(current).equals(caseCounts(reported))) {
                errors.add("Group " + groupId + " selected cases do not match its XML");
            }
            checkSummaryCount(summary, "tests", current.size(), groupId, errors);
            checkSummaryCount(summary, "passed", countStatus(current, "passed"), groupId, errors);
            checkSummaryCount(summary, "failed", countStatus(current, "failed"), groupId, errors);
            checkSummaryCount(summary, "skipped", countStatus(current, "skipped"), groupId, errors);
            detail.put("tests", current.size());
            cases.addAll(current);
            files.addAll(currentFiles);
            checkPlannedCases(summary, current, detail, groupId, errors);
            if (!current.isEmpty() || summary.containsKey("plannedCases")) {
                detail.put("notExecutedUnknown", false);
                detail.remove("notExecutedScope");
            }
        } catch (Exception failure) {
            errors.add("Group " + groupId + " report is invalid: " + failure.getMessage());
        }
        return detail;
    }

    private static boolean checkRpcSelection(Map<String, Object> group, Map<String, Object> manifest,
            Map<String, Object> configuration, Map<String, Object> summary, List<String> errors) {
        boolean selected = present(manifest.get("rpc"));
        String groupId = string(group.get("id"));
        if (!selected) {
            if (Boolean.TRUE.equals(summary.get("selectionEmpty"))) {
                errors.add("Group " + groupId + " cannot claim an empty selection without RPC filtering");
            }
            return false;
        }

        // 仅使用本轮声明和实际下发配置，缺少或改写 RPC 元信息不能放宽完整性校验。
        int before = errors.size();
        for (String field : List.of("rpc", "rpcVersion")) {
            Object expected = manifest.getOrDefault(field, "");
            boolean consistent = ObjectUtils.equals(expected, group.get(field));
            consistent &= ObjectUtils.equals(expected, configuration.get(field));
            consistent &= ObjectUtils.equals(expected, summary.get(field));
            if (!consistent) {
                errors.add("Group " + groupId + " changed the " + field + " selection");
            }
        }
        Map<String, Object> metadata = object(manifest.getOrDefault("rpcCases", Map.of()));
        Map<String, Object> expectedCases = new LinkedHashMap<>();
        for (String className : strings(group.get("classes"))) {
            Object methods = metadata.get(className);
            if (!(methods instanceof Map<?, ?>) || ((Map<?, ?>) methods).isEmpty()) {
                errors.add("Group " + groupId + " has no RPC declarations for " + className);
            }
            expectedCases.put(className, methods);
        }
        boolean consistent = expectedCases.equals(group.get("rpcCases"));
        consistent &= expectedCases.equals(configuration.get("rpcCases"));
        consistent &= expectedCases.equals(summary.get("rpcCases"));
        if (!consistent) {
            errors.add("Group " + groupId + " changed its RPC declarations");
        }
        Map<String, List<String>> catalog = NxnRpcSelection.catalog(expectedCases);
        String rpc = NxnRpcSelection.protocolName(string(manifest.get("rpc")));
        List<String> versions = catalog.getOrDefault(rpc, List.of());
        String version = string(manifest.getOrDefault("rpcVersion", ""));
        if (!catalog.containsKey(rpc) || !version.isEmpty() && !versions.contains(version)) {
            errors.add("Group " + groupId + " has no declarations matching its RPC selection");
        }

        // 参数被筛除属于明确的零选择，不能使用它掩盖执行失败或丢失的叶子计划。
        boolean hasPlan = summary.get("plannedCases") instanceof List<?>;
        hasPlan &= summary.get("notExecutedCases") instanceof List<?>;
        hasPlan &= summary.containsKey("notExecuted");
        if (!hasPlan) {
            errors.add("Group " + groupId + " RPC selection has no explicit testcase plan");
        }
        if (Boolean.TRUE.equals(summary.get("selectionEmpty"))) {
            boolean empty = List.of().equals(summary.get("plannedCases"));
            empty &= List.of().equals(summary.get("notExecutedCases"));
            empty &= integerEquals(summary.get("notExecuted"), 0);
            empty &= Boolean.FALSE.equals(summary.get("resultsAvailable"));
            empty &= !present(summary.get("taskFailure"));
            if (!empty) {
                errors.add("Group " + groupId + " has invalid empty RPC selection evidence");
            }
        }
        return errors.size() == before;
    }

    private static void checkRpcCases(Map<String, Object> group, Set<String> caseIds, List<String> errors) {
        Map<String, Object> metadata = object(group.get("rpcCases"));
        String groupId = string(group.get("id"));
        String rpc = string(group.get("rpc"));
        String version = string(group.get("rpcVersion"));
        for (String caseId : caseIds) {
            // XML 与计划的每个叶子都重新对照声明，二者相互一致不能代替 RPC 范围校验。
            int separator = caseId.indexOf('#');
            if (separator < 1 || separator == caseId.length() - 1) {
                errors.add("Group " + groupId + " has an unknown RPC testcase identity: " + caseId);
                continue;
            }
            String className = caseId.substring(0, separator);
            String scenario = caseId.substring(separator + 1);
            int parameterStart = scenario.indexOf('[');
            String method = parameterStart < 0 ? scenario : scenario.substring(0, parameterStart);
            if (method.endsWith("()")) {
                method = method.substring(0, method.length() - 2);
            }
            Object methods = metadata.get(className);
            if (!(methods instanceof Map<?, ?>) || !((Map<?, ?>) methods).containsKey(method)) {
                errors.add("Group " + groupId + " has an unknown RPC testcase: " + caseId);
                continue;
            }

            // 参数类保留完整原始 displayName；普通方法使用空参数匹配同一套声明选择器。
            List<String> parameters = List.of();
            if (parameterStart >= 0) {
                if (!scenario.endsWith("]")) {
                    errors.add("Group " + groupId + " has an unknown RPC testcase identity: " + caseId);
                    continue;
                }
                parameters = List.of(scenario.substring(parameterStart + 1, scenario.length() - 1));
            }
            if (!NxnRpcSelection.selected(metadata, rpc, version, className, method, parameters)) {
                errors.add("Group " + groupId + " testcase does not match its RPC selection: " + caseId);
            }
        }
    }

    private static void checkPlannedCases(Map<String, Object> summary, List<Map<String, Object>> cases,
            Map<String, Object> detail, String groupId, List<String> errors) {
        // 旧归档没有叶子用例计划；新报告以执行前计划核对未执行项，不补写跳过或通过结果。
        boolean hasPlan = summary.containsKey("plannedCases") || summary.containsKey("notExecutedCases");
        hasPlan |= summary.containsKey("notExecuted");
        if (!hasPlan) {
            return;
        }
        List<String> planned = strings(summary.get("plannedCases"));
        List<String> declaredMissing = strings(summary.get("notExecutedCases"));
        if (hasDuplicate(counts(planned)) || hasDuplicate(counts(declaredMissing))) {
            errors.add("Group " + groupId + " testcase plan contains duplicate identities");
        }
        Set<String> missing = new LinkedHashSet<>(planned);
        missing.removeAll(caseIdentities(cases).keySet());
        List<String> notExecuted = new ArrayList<>(missing);
        notExecuted.sort(Comparator.naturalOrder());
        detail.put("plannedCases", planned);
        detail.put("notExecuted", notExecuted.size());
        detail.put("notExecutedCases", notExecuted);
        declaredMissing.sort(Comparator.naturalOrder());
        if (!declaredMissing.equals(notExecuted)) {
            errors.add("Group " + groupId + " notExecutedCases does not match its plan and XML");
        }
        checkSummaryCount(summary, "notExecuted", notExecuted.size(), groupId, errors);
        if (!notExecuted.isEmpty()) {
            errors.add("Group " + groupId + " has unexecuted testcases: " + notExecuted);
        }
    }

    private static Map<String, Object> checkProcess(Path directory, String runId, String groupId,
            List<String> errors, boolean requireSuccess) throws Exception {
        int before = errors.size();
        Map<String, Object> launch = object(readJson(directory.resolve("launch.json")));
        Map<String, Object> status = object(readJson(directory.resolve("status.json")));
        Object identity = launch.get("processId");
        if (!present(identity) || !ObjectUtils.equals(identity, status.get("processId"))) {
            errors.add("Group " + groupId + " process identity does not match launch: " + directory);
        }
        if (!StringUtils.equals(runId, string(launch.get("runId")))
                || !StringUtils.equals(runId, string(status.get("runId")))
                || !StringUtils.equals(groupId, string(launch.get("groupId")))
                || !StringUtils.equals(groupId, string(status.get("groupId")))) {
            errors.add("Group " + groupId + " process belongs to another run/group: " + directory);
        }
        boolean exited = StringUtils.equals("exited", string(status.get("state")));
        if (!exited || present(status.get("error")) || present(status.get("cleanupFailure"))) {
            errors.add("Group " + groupId + " process did not exit cleanly: " + directory);
        }
        if (requireSuccess && !integerEquals(status.get("exitCode"), 0)) {
            errors.add("Group " + groupId + " JVM process exited unsuccessfully: " + directory);
        }
        boolean groupEmpty = Boolean.TRUE.equals(status.get("processGroupEmpty"));
        boolean noRemaining = List.of().equals(status.get("remainingPids"));
        boolean temporaryRemoved = Boolean.TRUE.equals(status.get("temporaryDirectoryRemoved"));
        if (!groupEmpty || !noRemaining || !temporaryRemoved) {
            errors.add("Group " + groupId + " process cleanup is incomplete: " + directory);
        }
        boolean stdoutPresent = Files.isRegularFile(directory.resolve("stdout.log"));
        boolean stderrPresent = Files.isRegularFile(directory.resolve("stderr.log"));
        if (!stdoutPresent || !stderrPresent) {
            errors.add("Group " + groupId + " process output evidence is incomplete: " + directory);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("directory", directory.toString());
        result.put("launch", launch);
        result.put("status", status);
        result.put("verified", errors.size() == before);
        return result;
    }

    private static Map<String, Object> checkRemoteCleanup(Map<String, Object> group,
            List<Map<String, Object>> processes, List<Path> launches, Path declaredDirectory,
            Path directory, List<String> errors) throws Exception {
        String groupId = string(group.get("id"));
        int before = errors.size();
        Object rawCleanup = group.get("remoteCleanup");
        if (!(rawCleanup instanceof Map<?, ?>)) {
            errors.add("Group " + groupId + " remote cleanup evidence is missing or invalid");
            Map<String, Object> failed = new LinkedHashMap<>();
            failed.put("successful", false);
            failed.put("claimedSuccessful", null);
            return failed;
        }
        Map<String, Object> cleanup = object(rawCleanup);
        if (!Boolean.TRUE.equals(cleanup.get("successful")) || !List.of().equals(cleanup.get("failures"))) {
            errors.add("Group " + groupId + " remote cleanup failed");
        }
        Object rawRecords = cleanup.get("processes");
        if (!(rawRecords instanceof List<?>)) {
            errors.add("Group " + groupId + " remote cleanup process records are missing or invalid");
            return cleanupResult(cleanup, false);
        }
        Map<String, Map<String, Object>> expected = new LinkedHashMap<>();
        for (Map<String, Object> process : processes) {
            Path launch = Path.of(string(process.get("directory"))).resolve("launch.json");
            expected.put(launch.toAbsolutePath().normalize().toString(), process);
            if (!Boolean.TRUE.equals(process.get("verified"))) {
                errors.add("Group " + groupId + " remote cleanup actual process status is invalid");
            }
        }
        List<String> recorded = new ArrayList<>();
        int stopped = 0;
        for (Object rawRecord : (List<?>) rawRecords) {
            if (!(rawRecord instanceof Map<?, ?>) || !(object(rawRecord).get("launchFile") instanceof String)) {
                errors.add("Group " + groupId + " remote cleanup process record is invalid");
                continue;
            }
            Map<String, Object> record = object(rawRecord);
            Path declaredLaunch = Path.of(string(record.get("launchFile")));
            Path launch = declaredLaunch;
            if (declaredLaunch.startsWith(declaredDirectory)) {
                launch = directory.resolve(declaredDirectory.relativize(declaredLaunch));
            }
            String path = launch.toAbsolutePath().normalize().toString();
            recorded.add(path);
            Map<String, Object> process = expected.get(path);
            if (process == null) {
                errors.add("Group " + groupId + " remote cleanup covers an unknown process: " + path);
                continue;
            }
            Object identity = object(process.get("launch")).get("processId");
            if (!ObjectUtils.equals(identity, record.get("processId"))) {
                errors.add("Group " + groupId + " remote cleanup process identity does not match: " + path);
            }
            if (!(record.get("requiredStop") instanceof Boolean)) {
                errors.add("Group " + groupId + " remote cleanup stop state is missing: " + path);
            }
            if (Boolean.TRUE.equals(record.get("requiredStop"))) {
                stopped++;
            }
            if (!List.of().equals(record.get("failures"))) {
                errors.add("Group " + groupId + " remote process cleanup failed: " + path);
            }
            if (!(record.get("after") instanceof Map<?, ?>) || !record.get("after").equals(process.get("status"))) {
                errors.add("Group " + groupId + " remote cleanup final status does not match: " + path);
            }

            // 容器身份来自对端实际执行证据，不能由可修改的展示名称替代。
            Path processDirectory = Path.of(string(process.get("directory")));
            Path evidence = processDirectory.getParent().resolve(string(identity) + ".json");
            if (Files.isRegularFile(evidence)) {
                Object container = object(readJson(evidence)).get("container");
                if (!ObjectUtils.equals(record.get("container"), container)) {
                    errors.add("Group " + groupId + " remote cleanup container does not match: " + path);
                }
            } else if (!present(record.get("container"))) {
                errors.add("Group " + groupId + " remote cleanup target is missing: " + path);
            }
        }
        List<String> expectedPaths = new ArrayList<>();
        for (Path path : launches) {
            expectedPaths.add(path.toAbsolutePath().normalize().toString());
        }
        if (!counts(recorded).equals(counts(expectedPaths))) {
            errors.add("Group " + groupId + " remote cleanup does not cover peer launches exactly once");
        }
        boolean checkedCount = integerEquals(cleanup.get("checked"), expectedPaths.size());
        boolean stoppedCount = integerEquals(cleanup.get("stopped"), stopped);
        if (!checkedCount || !stoppedCount) {
            errors.add("Group " + groupId + " remote cleanup counts do not match its process records");
        }
        return cleanupResult(cleanup, errors.size() == before);
    }

    private static Map<String, Object> cleanupResult(Map<String, Object> cleanup, boolean successful) {
        Map<String, Object> result = new LinkedHashMap<>(cleanup);
        result.put("successful", successful);
        result.put("claimedSuccessful", cleanup.get("successful"));
        return result;
    }

    private static Map<String, Object> checkRegistryCleanup(Path directory, String groupId, List<String> errors)
            throws Exception {
        int before = errors.size();
        List<Map<String, Object>> services = new ArrayList<>();
        List<Path> serviceDirectories = new ArrayList<>();
        if (Files.isDirectory(directory)) {
            try (Stream<Path> paths = Files.walk(directory)) {
                List<Path> roots = paths.filter(Files::isDirectory) //
                        .filter(path -> StringUtils.equals("registry-services", path.getFileName().toString())) //
                        .toList();
                for (Path root : roots) {
                    try (Stream<Path> children = Files.list(root)) {
                        serviceDirectories.addAll(children.filter(Files::isDirectory).toList());
                    }
                }
            }
        }
        serviceDirectories.sort(Comparator.naturalOrder());
        for (Path service : serviceDirectories) {
            Path path = service.resolve("lifecycle.properties");
            Map<String, String> properties = new LinkedHashMap<>();
            if (Files.isRegularFile(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    String text = line.trim();
                    boolean comment = text.startsWith("#") || text.startsWith("!");
                    int separator = text.indexOf('=');
                    if (!text.isEmpty() && !comment && separator >= 0) {
                        properties.put(text.substring(0, separator).trim(), text.substring(separator + 1).trim());
                    }
                }
            }
            String state = properties.get("state");
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("path", path.toString());
            detail.put("state", state);
            services.add(detail);
            if (!StringUtils.equals("released", state) && !StringUtils.equals("closed", state)) {
                errors.add("Group " + groupId + " registry client was not released: " + path);
            }
            if (Files.exists(service.resolve("cleanup-error.log"))) {
                errors.add("Group " + groupId + " registry cleanup failed: " + service);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", services);
        result.put("successful", errors.size() == before);
        return result;
    }

    private static void validateAssignments(Map<String, Object> manifest, List<String> errors) {
        List<Map<String, Object>> groups = objects(manifest.get("groups"));
        List<String> identities = new ArrayList<>();
        List<String> directories = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            identities.add(string(group.get("id")));
            directories.add(string(group.get("outputDirectory")));
        }
        boolean duplicateIds = identities.size() != new LinkedHashSet<>(identities).size();
        if (groups.isEmpty() || identities.contains("") || duplicateIds) {
            errors.add("Group IDs must be present and unique");
        }
        if (directories.contains("") || directories.size() != new LinkedHashSet<>(directories).size()) {
            errors.add("Groups must have unique output directories");
        }
        Map<String, Integer> selected = counts(strings(manifest.get("selectedClasses")));
        Map<String, Integer> discovered = counts(strings(manifest.get("discoveredClasses")));
        Map<String, Integer> planned = counts(strings(manifest.get("selectedSelections")));
        if (selected.isEmpty() || planned.isEmpty()) {
            errors.add("Test selection must not be empty");
        }
        if (hasDuplicate(selected) || hasDuplicate(discovered) || hasDuplicate(planned)) {
            errors.add("Selected, discovered and selection identities must be unique");
        }
        if (!discovered.keySet().containsAll(selected.keySet())) {
            errors.add("Selected classes are absent from discovery");
        }
        boolean filtered = present(manifest.get("userIncludes")) || present(manifest.get("rpc"));
        if (!filtered && !selected.equals(discovered)) {
            errors.add("An unfiltered run must select every discovered class");
        }
        List<String> assigned = new ArrayList<>();
        for (Map<String, Object> group : groups) {
            assigned.addAll(strings(group.getOrDefault("filters", group.get("classes"))));
        }
        if (!counts(assigned).equals(planned)) {
            errors.add("Group assignments do not cover selected selections exactly once");
        }
        for (String selection : planned.keySet()) {
            int owners = 0;
            for (String name : selected.keySet()) {
                if (StringUtils.equals(selection, name) || selection.startsWith(name + ".")) {
                    owners++;
                }
            }
            if (owners != 1) {
                errors.add("Selection does not identify one selected class: " + selection);
            }
        }
        for (Map<String, Object> group : groups) {
            List<String> names = strings(group.get("classes"));
            boolean invalid = names.isEmpty() || names.size() != new LinkedHashSet<>(names).size();
            invalid |= !selected.keySet().containsAll(names);
            if (invalid) {
                errors.add("Group " + group.get("id") + " contains invalid class assignments");
            }
        }
    }

    private static void checkSelections(String groupId, List<Map<String, Object>> cases,
            List<String> filters, boolean requireEverySelection, List<String> errors) {
        for (Map<String, Object> testCase : cases) {
            boolean assigned = false;
            for (String selection : filters) {
                assigned |= matchesSelection(testCase, selection);
            }
            if (!assigned) {
                errors.add("Group " + groupId + " executed an unassigned testcase: " + testCase.get("caseId"));
            }
        }
        if (!requireEverySelection) {
            return;
        }
        for (String selection : filters) {
            boolean executed = false;
            for (Map<String, Object> testCase : cases) {
                executed |= matchesSelection(testCase, selection);
            }
            if (!executed) {
                errors.add("Group " + groupId + " did not execute its selection: " + selection);
            }
        }
    }

    static boolean matchesSelection(Map<String, Object> testCase, String selection) {
        String scenario = string(testCase.get("scenario"));
        String method = scenario.split("\\[", 2)[0];
        if (method.endsWith("()")) {
            method = method.substring(0, method.length() - 2);
        }
        String expression = Pattern.quote(selection).replace("*", "\\E.*\\Q");
        Pattern pattern = Pattern.compile(expression);
        String className = string(testCase.get("className"));
        return pattern.matcher(className).matches() || pattern.matcher(className + "." + scenario).matches()
                || pattern.matcher(className + "." + method).matches();
    }

    private static void checkReportIdentity(String kind, Map<String, Object> data, String groupId,
            String runId, String reportId, Object filters, List<String> errors) {
        boolean current = StringUtils.equals(reportId, string(data.get("runId")));
        current &= StringUtils.equals(runId, string(data.get("orchestrationRunId")));
        if (!current) {
            errors.add("Group " + groupId + " " + kind + " belongs to another run");
        }
        if (!StringUtils.equals(groupId, string(data.get("groupId")))) {
            errors.add("Group " + groupId + " " + kind + " belongs to another group");
        }
        if (!sorted(data.getOrDefault("includes", List.of())).equals(sorted(filters))) {
            errors.add("Group " + groupId + " " + kind + " filters differ from its assignment");
        }
    }

    private static void checkSummaryCount(Map<String, Object> summary, String key, int count,
            String groupId, List<String> errors) {
        if (!integerEquals(summary.get(key), count)) {
            errors.add("Group " + groupId + " summary " + key + " does not match its XML");
        }
    }

    private static Map<String, Object> baseline(Map<String, Object> manifest, List<Map<String, Object>> cases,
            Path baselinePath, List<String> errors) throws IOException {
        if (baselinePath == null) {
            return null;
        }
        boolean filtered = present(manifest.get("userIncludes")) || present(manifest.get("matrixSelection"));
        filtered |= present(manifest.get("rpc"));
        if (filtered) {
            throw new IllegalArgumentException("Baseline coverage comparison requires an unfiltered run");
        }
        Map<String, Integer> before = caseIdentities(objects(readJson(baselinePath)));
        Map<String, Integer> after = caseIdentities(cases);
        List<String> missing = difference(before, after);
        List<String> unexpected = difference(after, before);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", baselinePath.toString());
        result.put("missingCases", missing);
        result.put("unexpectedCases", unexpected);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            errors.add("Current testcase identities differ from the supplied baseline");
        }
        return result;
    }

    static Object readJson(Path path) throws IOException {
        return JSON.readValue(path.toFile(), Object.class);
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

    private static Map<String, String> fileHashes(List<Path> files) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path path : files) {
            byte[] hash = digest.digest(Files.readAllBytes(path));
            result.put(path.getFileName().toString(), HexFormat.of().formatHex(hash));
        }
        return result;
    }

    private static List<Path> xmlFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().startsWith("TEST-")) //
                    .filter(path -> path.getFileName().toString().endsWith(".xml")) //
                    .sorted().toList();
        }
    }

    private static List<Path> namedFiles(Path directory, String name) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(path -> StringUtils.equals(name, path.getFileName().toString())).sorted().toList();
        }
    }

    private static Map<List<Object>, Integer> caseCounts(List<Map<String, Object>> cases) {
        Map<List<Object>, Integer> result = new LinkedHashMap<>();
        for (Map<String, Object> testCase : cases) {
            List<Object> identity = List.of(testCase.get("caseId"), testCase.get("status"));
            result.merge(identity, 1, Integer::sum);
        }
        return result;
    }

    private static Map<String, Integer> caseIdentities(List<Map<String, Object>> cases) {
        List<String> identities = new ArrayList<>();
        for (Map<String, Object> testCase : cases) {
            identities.add(string(testCase.get("caseId")));
        }
        return counts(identities);
    }

    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String value : values) {
            result.merge(value, 1, Integer::sum);
        }
        return result;
    }

    private static boolean hasDuplicate(Map<String, Integer> values) {
        return values.values().stream().anyMatch(count -> count != 1);
    }

    private static List<String> difference(Map<String, Integer> left, Map<String, Integer> right) {
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : left.entrySet()) {
            int amount = entry.getValue() - right.getOrDefault(entry.getKey(), 0);
            for (int index = 0; index < amount; index++) {
                result.add(entry.getKey());
            }
        }
        result.sort(Comparator.naturalOrder());
        return result;
    }

    private static int countStatus(List<Map<String, Object>> cases, String status) {
        int count = 0;
        for (Map<String, Object> testCase : cases) {
            if (StringUtils.equals(status, string(testCase.get("status")))) {
                count++;
            }
        }
        return count;
    }

    private static boolean allTrue(List<Map<String, Object>> values, String key) {
        return values.stream().allMatch(value -> Boolean.TRUE.equals(value.get(key)));
    }

    private static boolean anyTrue(List<Map<String, Object>> values, String key) {
        return values.stream().anyMatch(value -> Boolean.TRUE.equals(value.get(key)));
    }

    private static boolean integerEquals(Object value, int expected) {
        return value instanceof Number && ((Number) value).doubleValue() == expected;
    }

    private static boolean present(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        if (value instanceof Collection<?>) {
            return !((Collection<?>) value).isEmpty();
        }
        if (value instanceof Map<?, ?>) {
            return !((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected a JSON object");
        }
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Expected a JSON array");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object element : (List<?>) value) {
            result.add(object(element));
        }
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("Expected a JSON string array");
        }
        List<String> result = new ArrayList<>();
        for (Object element : (List<?>) value) {
            result.add(string(element));
        }
        return result;
    }

    private static List<String> sorted(Object value) {
        List<String> values = strings(value);
        values.sort(Comparator.naturalOrder());
        return values;
    }

    private static String string(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("Expected a JSON string");
        }
        return (String) value;
    }
}
