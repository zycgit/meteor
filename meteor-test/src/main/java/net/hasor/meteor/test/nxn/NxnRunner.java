package net.hasor.meteor.test.nxn;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;

/** Own the execution and cleanup of independent JUnit jobs in Compose-owned containers. */
final class NxnRunner {
    private NxnRunner() {
    }

    static int run(String[] arguments) throws Exception {
        NxnPlan.Options options = NxnPlan.parseArguments(arguments);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch completed = new CountDownLatch(1);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("schemaVersion", 1);
        state.put("runId", options.runId);
        state.put("skipError", options.skipError);
        state.put("state", "running");
        state.put("startedAt", NxnPlan.now());
        state.put("exitCode", null);
        state.put("cleanupComplete", true);

        // A host cancellation is acknowledged only after all owned jobs have stopped and their audit is durable.
        Thread shutdown = new Thread(() -> {
            interrupted.set(true);
            try {
                if (!completed.await(180, TimeUnit.SECONDS)) {
                    System.err.println("Timed out waiting for JUnit group cancellation and peer cleanup");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }, "nxn-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        int exitCode = 1;
        try {
            NxnPlan.writeJson(controlPath(options, ".runner-state.json"), state);
            exitCode = runGroups(options, interrupted, state);
            if (exitCode != 0) {
                state.put("error", interrupted.get() ? "Run cancelled" : "Run exited with code " + exitCode);
            }
            return exitCode;
        } catch (Exception | Error failure) {
            interrupted.set(true);
            state.put("error", failure.toString());
            throw failure;
        } finally {
            try {
                state.put("state", "finished");
                state.put("finishedAt", NxnPlan.now());
                state.put("exitCode", exitCode);
                state.put("interrupted", interrupted.get());
                NxnPlan.writeJson(controlPath(options, ".runner-state.json"), state);
            } finally {
                completed.countDown();
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // JVM shutdown is already running; its hook now observes the completed latch.
                }
            }
        }
    }

    private static int runGroups(NxnPlan.Options options, AtomicBoolean interrupted, Map<String, Object> state) throws Exception {
        long started = System.nanoTime();
        Map<String, Object> runtime = NxnPlan.readObject(options.runtime);
        Map<String, Object> manifest = NxnPlan.prepare(options, runtime);
        Path manifestPath = options.output.resolve("manifest.json");
        Map<String, Object> timings = new LinkedHashMap<>();
        timings.put("startedAt", NxnPlan.now());
        manifest.put("timings", timings);
        NxnPlan.writeJson(manifestPath, manifest);
        NxnPlan.printSummary(manifest, System.out);
        List<Map<String, Object>> groups = groups(manifest);
        // 进度只由调度线程维护，不并发读取工作线程正在修改的分组结果。
        Map<String, Map<String, Object>> progress = new LinkedHashMap<>();
        for (Map<String, Object> group : groups) {
            Map<String, Object> observation = new LinkedHashMap<>();
            Path directory = Path.of((String) group.get("outputDirectory"));
            observation.put("directory", directory);
            observation.put("stdoutOffset", 0L);
            observation.put("stderrOffset", 0L);
            observation.put("progressOffset", 0L);
            observation.put("peerEventsOffset", 0L);
            observation.put("peers", new LinkedHashMap<String, Map<String, Object>>());
            observation.put("completedPeers", new HashSet<String>());
            progress.put((String) group.get("id"), observation);
        }
        ExecutorService executor = Executors.newFixedThreadPool(groups.size());
        AtomicBoolean failureStop = new AtomicBoolean();
        state.put("cleanupComplete", false);
        try {
            List<String> orchestrationFailures = new ArrayList<>();
            checkCancellation(options, interrupted, orchestrationFailures);
            List<Future<?>> pending = new ArrayList<>();
            for (Map<String, Object> group : groups) {
                pending.add(executor.submit(() -> {
                    try {
                        executeGroup(group, options, interrupted, failureStop);
                        // 快速失败使用独立信号，不将测试失败误记为用户取消。
                        if (!options.skipError && truthy(group.get("exitCode"))) {
                            failureStop.set(true);
                        }
                    } catch (RuntimeException | Error failure) {
                        if (!options.skipError) {
                            failureStop.set(true);
                        }
                        throw failure;
                    }
                }));
            }
            long lastRead = started;
            long lastPrinted = started;
            for (Future<?> future : pending) {
                boolean waiting = true;
                while (waiting) {
                    checkCancellation(options, interrupted, orchestrationFailures);
                    // 实时转发分组输出；结构化事件维护进度，常规摘要每十五秒输出。
                    long now = System.nanoTime();
                    if (now - lastRead >= TimeUnit.MILLISECONDS.toNanos(250)) {
                        readGroupOutput(progress, pending, options.runId, now);
                        lastRead = now;
                    }
                    if (now - lastPrinted >= TimeUnit.SECONDS.toNanos(15)) {
                        printProgress(progress, pending, started, now);
                        lastPrinted = now;
                    }
                    try {
                        future.get(250, TimeUnit.MILLISECONDS);
                        waiting = false;
                    } catch (TimeoutException ignored) {
                        // Poll one run-level control file while group workers observe the shared cancellation flag.
                    } catch (InterruptedException failure) {
                        interrupted.set(true);
                    } catch (ExecutionException failure) {
                        if (!options.skipError) {
                            failureStop.set(true);
                        }
                        orchestrationFailures.add(failure.getCause().toString());
                        failure.getCause().printStackTrace(System.err);
                        waiting = false;
                    }
                }
            }

            // The report verifies case identity and lifecycle evidence before the run is marked completed.
            checkCancellation(options, interrupted, orchestrationFailures);
            long finished = System.nanoTime();
            readGroupOutput(progress, pending, options.runId, finished);
            printProgress(progress, pending, started, finished);
            System.out.println("Aggregating test reports and verifying execution evidence...");
            double wallSeconds = elapsed(started);
            timings.put("finishedAt", NxnPlan.now());
            timings.put("wallSeconds", wallSeconds);
            timings.put("groupsWallSeconds", wallSeconds);
            manifest.put("interrupted", interrupted.get());
            manifest.put("failureStopped", failureStop.get());
            state.put("failureStopped", failureStop.get());
            manifest.put("orchestrationErrors", orchestrationFailures);
            NxnPlan.writeJson(manifestPath, manifest);
            Map<String, Object> summary = NxnReport.aggregate(manifestPath, options.buildDirectory, options.baseline);
            Files.writeString(options.output.resolve("completed"), "Group execution and report validation completed.\n", StandardCharsets.UTF_8);
            List<String> errors = NxnPlan.strings(summary.get("aggregationErrors"));
            errors.forEach(System.err::println);
            if (interrupted.get()) {
                return 130;
            }
            return errors.isEmpty() ? 0 : 1;
        } catch (Exception | Error failure) {
            interrupted.set(true);
            throw failure;
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(180, TimeUnit.SECONDS)) {
                throw new IOException("Owned JUnit group workers did not finish cleanup within 180 seconds");
            }
            state.put("cleanupComplete", true);
        }
    }

    private static void readGroupOutput(Map<String, Map<String, Object>> progress, List<Future<?>> pending, String runId, long now) {
        int index = 0;
        for (Map.Entry<String, Map<String, Object>> entry : progress.entrySet()) {
            boolean finished = pending.get(index++).isDone();
            String groupId = entry.getKey();
            Map<String, Object> observation = entry.getValue();
            Path directory = (Path) observation.get("directory");
            // attached exec 同时携带 JVM 输出和 Docker 自身诊断，作为唯一实时来源避免重复打印。
            readProcessOutput(groupId, observation, directory.resolve("runner.log"), directory.resolve("runner-stderr.log"), finished);

            // 机器进度与业务输出分开读取，任何标准输出都不承担内部控制协议。
            readLines(directory.resolve("progress.jsonl"), observation, "progressOffset", json -> recordProgress(groupId, observation, runId, now, json), groupId);
            readPeerOutput(groupId, observation, runId, finished);
        }
    }

    private static void readProcessOutput(String identity, Map<String, Object> observation, Path stdout, Path stderr, boolean finished) {
        readOutput(identity, observation, stdout, "stdout", finished);
        readOutput(identity, observation, stderr, "stderr", finished);
    }

    private static void readOutput(String identity, Map<String, Object> observation, Path log, String stream, boolean finished) {
        if (!Files.isRegularFile(log)) {
            return;
        }
        String offset = stream + "Offset";
        try (RandomAccessFile input = new RandomAccessFile(log.toFile(), "r")) {
            input.seek((Long) observation.get(offset));
            byte[] bytes = new byte[8192];
            int count;
            while ((count = input.read(bytes)) > 0) {
                long start = input.getFilePointer() - count;
                ByteBuffer encoded = ByteBuffer.wrap(bytes, 0, count);
                CharBuffer decoded = CharBuffer.allocate(count);
                var decoder = StandardCharsets.UTF_8.newDecoder() //
                        .onMalformedInput(CodingErrorAction.REPLACE) //
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
                boolean terminal = finished && input.getFilePointer() == input.length();
                decoder.decode(encoded, decoded, terminal);
                if (terminal) {
                    decoder.flush(decoded);
                }

                // print()/提示语无需等换行；仅留下末尾尚未写完整的 UTF-8 字节。
                long consumed = start + encoded.position();
                observation.put(offset, consumed);
                decoded.flip();
                printOutput(identity, observation, stream, decoded.toString());
                if (encoded.position() < count) {
                    input.seek(consumed);
                    if (input.length() - consumed < 4 && !terminal) {
                        break;
                    }
                }
            }
        } catch (IOException failure) {
            outputWarning(observation, identity + " " + log.getFileName(), failure);
        }
    }

    private static void printOutput(String identity, Map<String, Object> observation, String stream, String text) {
        PrintStream output = StringUtils.equals(stream, "stdout") ? System.out : System.err;
        String lineStart = stream + "LineStart";
        boolean prefix = !Boolean.FALSE.equals(observation.get(lineStart));
        boolean afterCarriageReturn = Boolean.TRUE.equals(observation.get(stream + "CarriageReturn"));
        synchronized (output) {
            int start = 0;
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (prefix && !(afterCarriageReturn && character == '\n')) {
                    output.print("[" + identity + "][" + stream + "] ");
                    prefix = false;
                }
                if (character == '\n' || character == '\r') {
                    output.print(text.substring(start, index + 1));
                    start = index + 1;
                    prefix = true;
                }
                afterCarriageReturn = character == '\r';
            }
            if (start < text.length()) {
                output.print(text.substring(start));
            }
            output.flush();
        }
        observation.put(lineStart, prefix);
        observation.put(stream + "CarriageReturn", afterCarriageReturn);
    }

    private static void readLines(Path log, Map<String, Object> observation, String offset, Consumer<String> consumer, String identity) {
        if (!Files.isRegularFile(log)) {
            return;
        }
        try (RandomAccessFile input = new RandomAccessFile(log.toFile(), "r")) {
            input.seek((Long) observation.get(offset));
            String line;
            while ((line = input.readLine()) != null) {
                // 事件按整行提交，UTF-8 与 JSON 尚未写完时保留读取位置。
                long end = input.getFilePointer();
                input.seek(end - 1);
                int terminator = input.read();
                boolean complete = terminator == '\n' || terminator == '\r';
                if (!complete) {
                    break;
                }
                observation.put(offset, end);
                byte[] bytes = line.getBytes(StandardCharsets.ISO_8859_1);
                String text = new String(bytes, StandardCharsets.UTF_8);
                consumer.accept(text);
            }
        } catch (IOException failure) {
            outputWarning(observation, identity + " " + log.getFileName(), failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void readPeerOutput(String groupId, Map<String, Object> observation, String runId, boolean groupFinished) {
        Path directory = ((Path) observation.get("directory")).resolve("process-evidence");
        Map<String, Map<String, Object>> peers = (Map<String, Map<String, Object>>) observation.get("peers");
        Set<String> completed = (Set<String>) observation.get("completedPeers");

        // 增量消费原有登记事件，只保留活跃进程；已结束进程不会每次轮询重新扫描。
        readLines(directory.resolve("peers.jsonl"), observation, "peerEventsOffset", json -> {
            try {
                JsonNode event = NxnPlan.JSON.readTree(json);
                boolean owned = event != null && StringUtils.equals(runId, event.path("runId").asText()) && StringUtils.equals(groupId, event.path("groupId").asText());
                if (!owned) {
                    return;
                }
                String processId = event.path("processId").asText();
                Path artifact = Path.of(event.path("artifactDirectory").asText()).toAbsolutePath().normalize();
                Path expected = directory.resolve(processId).toAbsolutePath().normalize();
                if (processId.isEmpty() || !expected.equals(artifact) || completed.contains(processId)) {
                    return;
                }
                Map<String, Object> peer = peers.computeIfAbsent(processId, key -> {
                    Map<String, Object> cursor = new LinkedHashMap<>();
                    cursor.put("stdoutOffset", 0L);
                    cursor.put("stderrOffset", 0L);
                    cursor.put("directory", artifact);
                    return cursor;
                });
                if (event.hasNonNull("finishedAt")) {
                    peer.put("finished", true);
                }
            } catch (IOException | RuntimeException failure) {
                outputWarning(observation, groupId + " peer events", failure);
            }
        }, groupId);
        Iterator<Map.Entry<String, Map<String, Object>>> entries = peers.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<String, Map<String, Object>> entry = entries.next();
            Map<String, Object> peer = entry.getValue();
            boolean finished = groupFinished || Boolean.TRUE.equals(peer.get("finished"));
            Path peerDirectory = (Path) peer.get("directory");
            readProcessOutput(groupId + "][" + entry.getKey(), peer, peerDirectory.resolve("stdout.log"), peerDirectory.resolve("stderr.log"), finished);
            if (finished) {
                completed.add(entry.getKey());
                entries.remove();
            }
        }
    }

    private static void recordProgress(String groupId, Map<String, Object> observation, String runId, long now, String json) {
        try {
            JsonNode snapshot = NxnPlan.JSON.readTree(json);
            boolean valid = snapshot != null && snapshot.isObject() && StringUtils.equals(runId, snapshot.path("runId").asText()) && StringUtils.equals(groupId, snapshot.path("groupId").asText());
            if (!valid) {
                return;
            }
            observation.put("snapshot", snapshot);
            observation.put("observedAt", now);
            if (snapshot.hasNonNull("failure")) {
                String message = snapshot.path("case").asText() + ": " + snapshot.path("failure").asText();
                System.out.println("[FAIL] " + groupId + " " + progressLine(message));
                JsonNode matrix = snapshot.path("matrix");
                if (matrix.isObject()) {
                    String call = endpointLabel(matrix.path("consumer")) + " -> " + endpointLabel(matrix.path("provider"));
                    System.out.println("[FAIL][matrix] " + matrix.path("api").asText() + "/" + matrix.path("scenario").asText() + " | " + matrix.path("protocol").asText() + " | " + matrix.path("wire").asText() + " | " + call + " | discovery=" + matrix.path("discovery").asText() + " | phase=" + snapshot.path("phase").asText());
                    System.out.println("[FAIL][caseId] " + snapshot.path("caseId").asText());
                    JsonNode logs = snapshot.path("logs");
                    logs.fields().forEachRemaining(entry -> System.out.println("[FAIL][" + entry.getKey() + "] " + entry.getValue().asText()));
                }
            }
        } catch (IOException failure) {
            progressWarning(observation, groupId, failure);
        }
    }

    private static String endpointLabel(JsonNode endpoint) {
        String label = endpoint.path("language").asText() + "/" + endpoint.path("id").asText();
        String version = endpoint.path("version").asText();
        if (!version.isBlank()) {
            label += "@" + version;
        }
        return label;
    }

    private static void progressWarning(Map<String, Object> observation, String groupId, IOException failure) {
        // 进度读取不参与测试判定，原始日志和正式 XML 仍由现有验收链路核对。
        if (!Boolean.TRUE.equals(observation.put("warned", true))) {
            System.err.println("Cannot read progress for " + groupId + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void outputWarning(Map<String, Object> observation, String identity, Exception failure) {
        if (!Boolean.TRUE.equals(observation.put("outputWarned:" + identity, true))) {
            System.err.println("Cannot read output for " + identity + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void printProgress(Map<String, Map<String, Object>> progress, List<Future<?>> pending, long started, long now) {
        long completedGroups = pending.stream().filter(Future::isDone).count();
        int completed = 0;
        int failed = 0;
        int skipped = 0;
        for (Map<String, Object> observation : progress.values()) {
            JsonNode snapshot = (JsonNode) observation.get("snapshot");
            if (snapshot != null) {
                completed += snapshot.path("completed").asInt();
                failed += snapshot.path("failed").asInt();
                skipped += snapshot.path("skipped").asInt();
            }
        }
        String elapsed = progressDuration(TimeUnit.NANOSECONDS.toMillis(now - started));
        System.out.println("Progress: groups " + completedGroups + "/" + pending.size() + " finished; cases " + completed + " completed; failed " + failed + "; skipped " + skipped + "; elapsed " + elapsed);

        // 已完成组不反复展示；当前用例完成后，报告和远端进程清理仍属于工作组的生命周期。
        int index = 0;
        for (Map.Entry<String, Map<String, Object>> entry : progress.entrySet()) {
            if (pending.get(index++).isDone()) {
                continue;
            }
            Map<String, Object> observation = entry.getValue();
            JsonNode snapshot = (JsonNode) observation.get("snapshot");
            if (snapshot == null) {
                System.out.println("  " + entry.getKey() + ": waiting for JUnit startup");
                continue;
            }
            String phase = snapshot.path("phase").asText();
            String current = progressLine(snapshot.path("current").asText("-"));
            String counts = snapshot.path("completed").asInt() + "/" + snapshot.path("total").asInt();
            String detail = "  " + entry.getKey() + ": " + phase + " " + current + "; cases " + counts;
            if (StringUtils.equals(phase, "testing")) {
                long observedAt = (Long) observation.get("observedAt");
                long millis = snapshot.path("testElapsedMillis").asLong();
                millis += TimeUnit.NANOSECONDS.toMillis(now - observedAt);
                detail += "; current " + progressDuration(millis);
            }
            System.out.println(detail);
        }
    }

    private static String progressLine(String text) {
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    private static String progressDuration(long millis) {
        long seconds = Math.max(0L, millis) / 1000;
        return seconds / 60 + "m " + seconds % 60 + "s";
    }

    private static Path controlPath(NxnPlan.Options options, String suffix) {
        Path output = options.output.toAbsolutePath().normalize();
        return output.resolveSibling(output.getFileName() + suffix);
    }

    private static void checkCancellation(NxnPlan.Options options, AtomicBoolean interrupted, List<String> failures) {
        if (interrupted.get()) {
            return;
        }
        Path request = controlPath(options, ".cancel.json");
        if (!Files.exists(request)) {
            return;
        }
        try {
            Map<String, Object> control = NxnPlan.readObject(request);
            Object runId = control.get("runId");
            boolean valid = runId instanceof String && ((String) runId).matches("[A-Za-z0-9][A-Za-z0-9_.-]*");
            if (!valid) {
                throw new IOException("Cancellation request has no valid runId");
            }
            if (same(runId, options.runId)) {
                interrupted.set(true);
            }
        } catch (IOException | RuntimeException failure) {
            failures.add("Cannot read run cancellation: " + failure);
            failure.printStackTrace(System.err);
            interrupted.set(true);
        }
    }

    static List<String> groupCommand(Map<String, Object> group, NxnPlan.Options options) throws IOException {
        List<String> command = new ArrayList<>();
        // A group borrows its prepared language service; no container is created here.
        command.add(options.dockerExecutable);
        command.add("exec");
        command.add("-i");
        command.add("--workdir");
        command.add(options.workspace);
        command.add("--env");
        command.add("METEOR_TEST_RUN_ID=" + options.runId);
        command.add("--env");
        command.add("METEOR_TEST_GROUP_ID=" + group.get("id"));
        command.add("--env");
        Path output = Path.of((String) group.get("outputDirectory"));
        command.add("METEOR_TEST_EVIDENCE_DIR=" + output.resolve("process-evidence"));
        command.add(options.container);
        Path launchFile = Path.of((String) group.get("launchFile"));
        List<String> invocation = new ArrayList<>(NxnProcess.runCommand(launchFile));
        invocation.set(0, options.processWrapper);
        command.addAll(invocation);
        return command;
    }

    static Map<String, Object> executeGroup(Map<String, Object> group, NxnPlan.Options options, AtomicBoolean interrupted, AtomicBoolean failureStop) {
        Path directory = Path.of((String) group.get("outputDirectory"));
        long started = System.nanoTime();
        group.put("startedAt", NxnPlan.now());
        group.put("exitCode", null);
        List<String> cleanupFailures = new ArrayList<>();
        group.put("cleanupFailures", cleanupFailures);
        Process attached = null;
        try {
            NxnPlan.writeJson(directory.resolve("group.json"), group);
            int classCount = NxnPlan.strings(group.get("classes")).size();
            System.out.println("Starting " + group.get("id") + ": " + classCount + " JUnit classes");
            if (interrupted.get()) {
                throw new IOException("Run interrupted before group startup");
            }
            if (failureStop.get()) {
                group.put("failureStopped", true);
                throw new IOException("Group stopped before startup after another group failed");
            }
            ProcessBuilder builder = new ProcessBuilder(groupCommand(group, options));
            builder.redirectError(directory.resolve("runner-stderr.log").toFile());
            builder.redirectOutput(directory.resolve("runner.log").toFile());
            attached = builder.start();
            attached.getOutputStream().close();
            while (!attached.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (interrupted.get() || failureStop.get()) {
                    if (failureStop.get() && !interrupted.get()) {
                        group.put("failureStopped", true);
                        System.err.println("[" + group.get("id") + "][stop] Another group failed; stopping this group");
                    }
                    cancelGroup(group, options, false);
                    if (!attached.waitFor(15, TimeUnit.SECONDS)) {
                        throw new IOException("JUnit exec did not exit after cancellation");
                    }
                    break;
                }
            }
            group.put("attachExitCode", attached.exitValue());

            // The native supervisor proves the actual JVM exit independently of Docker's transport status.
            Map<String, Object> status = status(Path.of((String) group.get("launchFile")));
            String expectedProcess = options.runId + "-" + group.get("id") + "-junit";
            if (!same(status.get("runId"), options.runId) || !same(status.get("groupId"), group.get("id")) || !same(status.get("processId"), expectedProcess)) {
                throw new IllegalArgumentException("JVM exit evidence belongs to another run/group");
            }
            Object exitCode = status.get("exitCode");
            if (!(exitCode instanceof Integer) && !(exitCode instanceof Long)) {
                throw new IllegalArgumentException("JVM exit evidence has no integer exitCode");
            }
            group.put("exitCode", ((Number) exitCode).intValue());
            boolean clean = same(status.get("state"), "exited") && peerTreeCleaned(status) && !truthy(status.get("cleanupFailure")) && !truthy(status.get("error"));
            if (!clean) {
                cleanupFailures.add("Owned JVM process cleanup was not confirmed");
            }
            if (attached.exitValue() != 0 && ((Number) exitCode).intValue() == 0) {
                group.put("failure", "Docker exec exited unsuccessfully after the JVM reported success");
                group.put("exitCode", 1);
            }
        } catch (IOException | RuntimeException failure) {
            group.put("failure", failure.toString());
            group.put("exitCode", interrupted.get() ? 130 : 1);
            failure.printStackTrace(System.err);
            try {
                cancelGroup(group, options, false);
            } catch (IOException | InterruptedException | RuntimeException cleanup) {
                cleanupFailures.add(cleanup.toString());
                cleanup.printStackTrace(System.err);
            }
        } catch (InterruptedException failure) {
            interrupted.set(true);
            group.put("failure", failure.toString());
            group.put("exitCode", 130);
            failure.printStackTrace(System.err);
        } finally {
            // Reclaim a detached JVM first, then audit remote language processes outside its local process tree.
            if (attached != null && attached.isAlive()) {
                try {
                    cancelGroup(group, options, true);
                    if (!attached.waitFor(10, TimeUnit.SECONDS)) {
                        closeTransport(attached);
                    }
                } catch (IOException | InterruptedException | RuntimeException cleanup) {
                    cleanupFailures.add(cleanup.toString());
                    cleanup.printStackTrace(System.err);
                    closeTransport(attached);
                }
            }
            Map<String, Object> remoteCleanup = cleanupRemotePeers(group, options);
            group.put("remoteCleanup", remoteCleanup);
            cleanupFailures.addAll(NxnPlan.strings(remoteCleanup.get("failures")));
            if (!cleanupFailures.isEmpty() && !truthy(group.get("exitCode"))) {
                group.put("exitCode", 1);
            }
            for (String failure : cleanupFailures) {
                System.err.println("[" + group.get("id") + "][cleanup] " + failure);
            }
            group.put("finishedAt", NxnPlan.now());
            group.put("wallSeconds", elapsed(started));
            try {
                NxnPlan.writeJson(directory.resolve("group.json"), group);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot preserve group execution evidence: " + directory, failure);
            }
            System.out.println("Finished " + group.get("id") + ": exit " + group.get("exitCode") + ", " + group.get("wallSeconds") + " seconds");
        }
        return group;
    }

    private static void closeTransport(Process process) {
        // Only this owned Docker CLI process is terminated here; native supervision owns the container process tree.
        try {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException failure) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            process.destroyForcibly();
            System.err.println("Docker exec transport cleanup failed: " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void cancelGroup(Map<String, Object> group, NxnPlan.Options options, boolean force) throws IOException, InterruptedException {
        Path launch = Path.of((String) group.get("launchFile"));
        Map<String, Object> control = stopPeer(options.container, launch, force, options, 30);
        int exitCode = ((Number) control.get("exitCode")).intValue();
        if (exitCode != 0) {
            throw new IOException("Cannot cancel " + group.get("id") + ": " + control.get("output"));
        }
    }

    static boolean peerTreeCleaned(Map<String, Object> status) {
        if (status == null) {
            return false;
        }
        Object state = status.get("state");
        Object pids = status.get("remainingPids");
        boolean terminal = same(state, "exited") || same(state, "failed");
        return terminal && Boolean.TRUE.equals(status.get("processGroupEmpty")) && pids instanceof List && ((List<?>) pids).isEmpty() && Boolean.TRUE.equals(status.get("temporaryDirectoryRemoved"));
    }

    static Map<String, Object> cleanupRemotePeers(Map<String, Object> group, NxnPlan.Options options) {
        Path evidence = Path.of((String) group.get("outputDirectory")).resolve("process-evidence");
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        List<Path> launchFiles = new ArrayList<>();
        if (Files.exists(evidence)) {
            try (Stream<Path> entries = Files.walk(evidence)) {
                launchFiles = entries.filter(path -> same(path.getFileName().toString(), "launch.json")).sorted().collect(Collectors.toList());
            } catch (IOException failure) {
                failures.add("Cannot enumerate remote peer launches: " + failure);
                failure.printStackTrace(System.err);
            }
        }
        for (Path launchFile : launchFiles) {
            Map<String, Object> record = auditRemotePeer(group, options, launchFile);
            records.add(record);
            for (String failure : NxnPlan.strings(record.get("failures"))) {
                failures.add(launchFile + ": " + failure);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", records.size());
        long stopped = records.stream().filter(record -> Boolean.TRUE.equals(record.get("requiredStop"))).count();
        result.put("stopped", stopped);
        result.put("successful", failures.isEmpty());
        result.put("processes", records);
        result.put("failures", failures);
        return result;
    }

    private static Map<String, Object> auditRemotePeer(Map<String, Object> group, NxnPlan.Options options, Path launchFile) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("processId", null);
        record.put("container", null);
        record.put("launchFile", launchFile.toAbsolutePath().normalize().toString());
        record.put("requiredStop", false);
        record.put("before", null);
        record.put("after", null);
        List<Map<String, Object>> controls = new ArrayList<>();
        record.put("controls", controls);
        List<String> failures = new ArrayList<>();
        record.put("failures", failures);
        Path directory = launchFile.getParent();
        try {
            Map<String, Object> launch = NxnPlan.readObject(launchFile);
            Object identity = launch.get("processId");
            if (!(identity instanceof String) || ((String) identity).isEmpty()) {
                throw new IllegalArgumentException("Remote launch has no process identity");
            }
            String processId = (String) identity;
            record.put("processId", processId);
            Path metadataFile = directory.getParent().resolve(processId + ".json");
            Map<String, Object> metadata;
            try {
                metadata = NxnPlan.readObject(metadataFile);
            } catch (IOException | RuntimeException failure) {
                // Atomic replacement can be interrupted; the durable start event still establishes ownership.
                failures.add("Cannot read original peer metadata: " + failure);
                failure.printStackTrace(System.err);
                metadata = recoverMetadata(directory.getParent().resolve("peers.jsonl"), processId);
                if (metadata == null) {
                    throw new IllegalArgumentException("No durable remote peer service identity is available");
                }
            }
            if (metadata == null) {
                throw new IllegalArgumentException("Remote peer metadata must be an object");
            }
            for (Map<String, Object> source : Arrays.asList(launch, metadata)) {
                if (!same(source.get("processId"), processId) || !same(source.get("runId"), options.runId) || !same(source.get("groupId"), group.get("id"))) {
                    throw new IllegalArgumentException("Remote peer identity does not match its owning run/group");
                }
            }
            Path artifact = Path.of((String) metadata.get("artifactDirectory")).toRealPath();
            if (!artifact.equals(directory.toRealPath())) {
                throw new IllegalArgumentException("Remote peer artifact directory does not match its launch");
            }
            Object containerValue = metadata.get("container");
            if (!(containerValue instanceof String) || ((String) containerValue).isEmpty()) {
                throw new IllegalArgumentException("Remote peer has no actual language service container");
            }
            String container = (String) containerValue;
            record.put("container", container);
            if (Files.exists(directory.resolve("status.native")) || Files.exists(directory.resolve("status.json"))) {
                try {
                    record.put("before", status(launchFile));
                } catch (IOException | RuntimeException failure) {
                    failures.add("Cannot read original remote status: " + failure);
                    failure.printStackTrace(System.err);
                }
            }
            boolean requiredStop = !peerTreeCleaned(NxnPlan.object(record.get("before")));
            record.put("requiredStop", requiredStop);
            if (requiredStop) {
                failures.add("Remote peer outlived its owning JUnit JVM");
                for (boolean force : new boolean[] { false, true }) {
                    try {
                        Map<String, Object> control = stopPeer(container, launchFile, force, options, 35);
                        controls.add(control);
                        if (((Number) control.get("exitCode")).intValue() != 0) {
                            failures.add("Remote stop returned " + control.get("exitCode"));
                        }
                    } catch (IOException | InterruptedException | RuntimeException failure) {
                        Map<String, Object> control = new LinkedHashMap<>();
                        control.put("force", force);
                        control.put("failure", failure.toString());
                        controls.add(control);
                        failures.add("Remote stop failed: " + failure);
                        failure.printStackTrace(System.err);
                    }
                    try {
                        record.put("after", status(launchFile));
                    } catch (IOException | RuntimeException failure) {
                        record.put("after", null);
                        failure.printStackTrace(System.err);
                        if (force) {
                            failures.add("Cannot read final remote status: " + failure);
                        }
                    }
                    if (peerTreeCleaned(NxnPlan.object(record.get("after")))) {
                        break;
                    }
                }
            } else {
                record.put("after", record.get("before"));
            }
            Map<String, Object> finalStatus = NxnPlan.object(record.get("after"));
            if (!peerTreeCleaned(finalStatus)) {
                failures.add("Remote peer process cleanup was not confirmed");
            } else if (!same(finalStatus.get("processId"), processId) || !same(finalStatus.get("runId"), options.runId) || !same(finalStatus.get("groupId"), group.get("id"))) {
                failures.add("Remote final status belongs to another process/run/group");
            } else if (!same(finalStatus.get("state"), "exited") || truthy(finalStatus.get("error")) || truthy(finalStatus.get("cleanupFailure"))) {
                failures.add("Remote peer wrapper reported a lifecycle failure");
            }
        } catch (IOException | RuntimeException failure) {
            failures.add(failure.toString());
            failure.printStackTrace(System.err);
        }
        return record;
    }

    private static Map<String, Object> recoverMetadata(Path eventFile, String processId) throws IOException {
        if (!Files.exists(eventFile)) {
            return null;
        }
        List<String> lines = Files.readAllLines(eventFile, StandardCharsets.UTF_8);
        for (int index = lines.size() - 1; index >= 0; index--) {
            try {
                Map<String, Object> event = NxnPlan.JSON.readValue(lines.get(index), Map.class);
                if (event != null && same(event.get("processId"), processId)) {
                    return event;
                }
            } catch (IOException | RuntimeException ignored) {
                // A killed writer may leave an incomplete final event line.
            }
        }
        return null;
    }

    private static Map<String, Object> stopPeer(String container, Path launchFile, boolean force, NxnPlan.Options options, int timeoutSeconds) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(options.dockerExecutable);
        command.add("exec");
        command.add(container);
        List<String> invocation = new ArrayList<>(NxnProcess.stopCommand(launchFile, force));
        invocation.set(0, options.processWrapper);
        command.addAll(invocation);
        Map<String, Object> launch = NxnPlan.readObject(launchFile);
        String identity = launch.get("groupId") + "][" + launch.get("processId");
        Path controlLog = Files.createTempFile(launchFile.getParent(), "stop-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(controlLog.toFile());
        Process process = null;
        Map<String, Object> cursor = new LinkedHashMap<>();
        cursor.put("controlOffset", 0L);
        try {
            process = builder.start();
            process.getOutputStream().close();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                readOutput(identity, cursor, controlLog, "control", false);
                if (System.nanoTime() >= deadline) {
                    throw new IOException("Remote stop timed out after " + timeoutSeconds + " seconds");
                }
            }
            Map<String, Object> control = new LinkedHashMap<>();
            control.put("force", force);
            control.put("exitCode", process.exitValue());
            control.put("output", Files.readString(controlLog, StandardCharsets.UTF_8));
            return control;
        } finally {
            if (process != null && process.isAlive()) {
                closeTransport(process);
            }
            readOutput(identity, cursor, controlLog, "control", true);
            Files.deleteIfExists(controlLog);
        }
    }

    private static Map<String, Object> status(Path launchFile) throws IOException {
        return NxnPlan.JSON.convertValue(NxnProcess.readStatus(launchFile), Map.class);
    }

    private static boolean same(Object actual, Object expected) {
        boolean strings = actual instanceof String && expected instanceof String;
        return strings && StringUtils.equals((String) actual, (String) expected);
    }

    private static boolean truthy(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        if (value instanceof String) {
            return !((String) value).isEmpty();
        }
        return true;
    }

    private static double elapsed(long started) {
        return Math.round((System.nanoTime() - started) / 1_000_000.0) / 1000.0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> groups(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("groups");
    }
}
