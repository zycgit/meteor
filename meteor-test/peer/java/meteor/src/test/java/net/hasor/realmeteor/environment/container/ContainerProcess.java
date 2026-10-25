/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.container;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.function.EFunction;
import net.hasor.meteor.test.nxn.NxnProcess;

/** 借用常驻语言容器，只拥有当前场景的业务进程树和本地 exec 控制进程。 */
public final class ContainerProcess extends Process {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, JsonNode> SERVICES = new ConcurrentHashMap<>();
    private static final Object EVIDENCE_LOCK = new Object();

    private final Process attached;
    private final String service;
    private final String hostname;
    private final Path directory;
    private final Map<String, Object> evidence;
    private final EFunction<List<String>, String, IOException> control;
    private final AtomicBoolean cleaning = new AtomicBoolean();
    private final CompletableFuture<Void> cleaned = new CompletableFuture<>();
    private volatile Integer processExitCode;
    private volatile IOException cleanupFailure;

    ContainerProcess(Process attached, String service, String hostname, Path directory,
            Map<String, Object> evidence, EFunction<List<String>, String, IOException> control) {
        this.attached = attached;
        this.service = service;
        this.hostname = hostname;
        this.directory = directory;
        this.evidence = evidence;
        this.control = control;
        this.attached.onExit().thenRun(() -> this.finish(null, false));
    }

    public static ContainerProcess start(String runtime, ProcessBuilder command) throws IOException {
        return start(runtime, command, false);
    }

    /** 本机控制场景使用编排器指定的 LOCAL 服务，网络与 driver 保持约定关系。 */
    public static ContainerProcess startLocal(String runtime, ProcessBuilder command) throws IOException {
        return start(runtime, command, true);
    }

    private static ContainerProcess start(String runtime, ProcessBuilder command, boolean local) throws IOException {
        if (command.command().isEmpty()) {
            throw new IOException("Peer process command must not be empty");
        }
        String key = runtime.toUpperCase(Locale.ROOT).replace('-', '_');
        if (local) {
            key += "_LOCAL";
        }
        String service = requireEnvironment("METEOR_TEST_SERVICE_" + key);
        String hostname = requireEnvironment("METEOR_TEST_HOST_" + key);
        String runId = environment("METEOR_TEST_RUN_ID", "unassigned");
        String processId = "meteor-peer-" + UUID.randomUUID();
        String configured = environment("METEOR_TEST_EVIDENCE_DIR", "build/docker-evidence/" + runId);
        Path evidenceDirectory = Files.createDirectories(Path.of(configured).toAbsolutePath());
        Path directory = Files.createDirectory(evidenceDirectory.resolve(processId));
        Path workingDirectory = Path.of("").toAbsolutePath();
        if (command.directory() != null) {
            workingDirectory = command.directory().toPath().toAbsolutePath().normalize();
        }

        // Compose 拥有语言服务，每次只启动新的业务进程与独占临时目录。
        JsonNode inspection = serviceIdentity(service);
        Map<String, Object> launch = new LinkedHashMap<>();
        launch.put("processId", processId);
        launch.put("runId", runId);
        launch.put("groupId", environment("METEOR_TEST_GROUP_ID", ""));
        launch.put("command", command.command());
        launch.put("workingDirectory", workingDirectory.toString());
        launch.put("environment", environmentChanges(command.environment(), hostname, directory));
        List<String> removed = new ArrayList<>();
        for (String inherited : System.getenv().keySet()) {
            if (!command.environment().containsKey(inherited)) {
                removed.add(inherited);
            }
        }
        launch.put("unsetEnvironment", removed);
        launch.put("temporaryDirectory", directory.resolve("tmp").toString());
        Path launchFile = directory.resolve("launch.json");
        NxnProcess.prepareLaunch(launchFile, launch);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("processId", processId);
        evidence.put("runId", runId);
        evidence.put("groupId", launch.get("groupId"));
        evidence.put("runtime", runtime);
        evidence.put("executionMode", "exec");
        evidence.put("container", service);
        evidence.put("containerId", inspection.path("Id").asText());
        evidence.put("imageId", inspection.path("Image").asText());
        evidence.put("hostname", hostname);
        evidence.put("serviceInspection", inspection);
        evidence.put("dockerNetworkMode", inspection.path("HostConfig").path("NetworkMode").asText());
        evidence.put("networks", inspection.path("NetworkSettings").path("Networks"));
        evidence.put("networkMode", local ? "local-service" : "service");
        evidence.put("independentNetworkNamespace", false);
        evidence.put("independentProcess", true);
        evidence.put("command", redactCommand(command.command()));
        evidence.put("workingDirectory", workingDirectory.toString());
        evidence.put("artifactDirectory", directory.toString());
        evidence.put("createdAt", Instant.now().toString());
        evidence.put("startupStartedNanos", System.nanoTime());
        List<Map<String, Object>> timings = new ArrayList<>();
        evidence.put("dockerCommandTimings", timings);
        EFunction<List<String>, String, IOException> control = arguments -> timedDocker(arguments, timings);
        writeEvidence(directory, evidence);

        // ProcessBuilder 保留调用者的重定向，包装器同时持久化完整的 stdout/stderr。
        List<String> arguments = dockerCommand();
        arguments.addAll(List.of("exec", "--interactive", service));
        arguments.addAll(NxnProcess.runCommand(launchFile));
        ProcessBuilder exec = new ProcessBuilder(arguments);
        exec.redirectInput(command.redirectInput());
        exec.redirectOutput(command.redirectOutput());
        exec.redirectError(command.redirectError());
        exec.redirectErrorStream(command.redirectErrorStream());
        Process attached = exec.start();
        evidence.put("startupFinishedNanos", System.nanoTime());
        return new ContainerProcess(attached, service, hostname, directory, evidence, control);
    }

    public static String host(Process process) {
        if (!(process instanceof ContainerProcess)) {
            throw new IllegalArgumentException("Expected a container peer process");
        }
        return ((ContainerProcess) process).hostname;
    }

    public static String runnerHost() {
        return environment("METEOR_TEST_RUNNER_HOST", "java-meteor.meteor.test");
    }

    @Override
    public OutputStream getOutputStream() {
        return this.attached.getOutputStream();
    }

    @Override
    public InputStream getInputStream() {
        return this.attached.getInputStream();
    }

    @Override
    public InputStream getErrorStream() {
        return this.attached.getErrorStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
        while (!this.waitFor(1, TimeUnit.SECONDS)) {
            // 无期限等待仍核对真实子进程，不永久依赖 exec CLI 的退出通知。
        }
        return this.exitValue();
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        if (this.processExitCode == null && !this.cleaned.isDone()
                && !this.attached.waitFor(timeout, unit) && !this.finishIfExited()) {
            return false;
        }
        this.finish(null, false);
        this.awaitCleanup();
        return true;
    }

    @Override
    public int exitValue() {
        // 退出码只是本轮收尾的候选结果；所有终态查询等待同一份日志与资源清理结果。
        if (this.cleaning.get() || this.processExitCode != null || this.cleanupFailure != null) {
            this.cleaned.join();
            this.checkCleanup();
            return this.processExitCode;
        }
        if (this.isAlive()) {
            throw new IllegalThreadStateException("Peer process has not exited: " + this.evidence.get("processId"));
        }
        this.cleaned.join();
        this.checkCleanup();
        return this.processExitCode;
    }

    @Override
    public boolean isAlive() {
        if (this.cleaning.get() || this.processExitCode != null || this.cleanupFailure != null) {
            this.cleaned.join();
            this.checkCleanup();
            return false;
        }
        if (this.attached.isAlive()) {
            // 保留正常输出排空路径；只有 waitFor 的等待预算结束后才恢复滞留的 exec CLI。
            return true;
        }
        this.finish(null, false);
        this.cleaned.join();
        this.checkCleanup();
        return false;
    }

    /** 本地 pid 标识本对象拥有的 exec CLI；远端业务 PID 记录在 status 证据中。 */
    @Override
    public long pid() {
        return this.attached.pid();
    }

    @Override
    public ProcessHandle toHandle() {
        return this.attached.toHandle();
    }

    @Override
    public CompletableFuture<Process> onExit() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                this.waitFor();
                return this;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CompletionException(interrupted);
            }
        });
    }

    @Override
    public boolean supportsNormalTermination() {
        return true;
    }

    @Override
    public void destroy() {
        this.stop(false);
    }

    @Override
    public Process destroyForcibly() {
        this.stop(true);
        return this;
    }

    private void stop(boolean force) {
        if (this.cleaning.compareAndSet(false, true)) {
            JsonNode status = null;
            boolean stopRequested = false;
            try {
                status = this.readStatus();
                if (!terminal(status)) {
                    stopRequested = true;
                    this.requestStop(force);
                    status = this.readStatus();
                }
            } catch (IOException failure) {
                this.cleanupFailure = failure;
            }
            this.finishClaimed(status, false, stopRequested);
        }
        this.cleaned.join();
        this.checkCleanup();
    }

    private boolean finishIfExited() {
        if (this.cleaning.get()) {
            return true;
        }
        try {
            JsonNode status = this.readStatus();
            if (!terminal(status)) {
                return false;
            }
            this.finish(status, this.attached.isAlive());
        } catch (IOException failure) {
            this.cleanupFailure = failure;
            this.finish(null, false);
        }
        return true;
    }

    private JsonNode readStatus() throws IOException {
        JsonNode status = NxnProcess.readStatus(this.directory.resolve("launch.json"));
        if (status == null) {
            return null;
        }
        String processId = (String) this.evidence.get("processId");
        if (status == null || !StringUtils.equals(processId, status.path("processId").asText())) {
            throw new IOException("Peer status does not identify the owned process: " + processId);
        }
        return status;
    }

    private static boolean terminal(JsonNode status) {
        if (status == null) {
            return false;
        }
        String state = status.path("state").asText();
        return StringUtils.equals("exited", state) || StringUtils.equals("failed", state);
    }

    private void requestStop(boolean force) throws IOException {
        // 只停止当前 launch 创建的进程树，不能关闭借入的语言服务容器。
        List<String> arguments = new ArrayList<>();
        arguments.addAll(List.of("exec", this.service));
        arguments.addAll(NxnProcess.stopCommand(this.directory.resolve("launch.json"), force));
        this.control.eApply(arguments);
    }

    private void finish(JsonNode status, boolean attachDelayed) {
        if (this.cleaning.compareAndSet(false, true)) {
            this.finishClaimed(status, attachDelayed, false);
        }
    }

    private void finishClaimed(JsonNode status, boolean attachDelayed, boolean stopRequested) {
        try {
            this.evidence.put("cleanupStartedNanos", System.nanoTime());
            if (status == null) {
                status = this.readStatus();
            }
            if (!terminal(status)) {
                // CLI 提前结束不能表示业务完成；回收所属进程树后仍报告原控制失败。
                if (this.cleanupFailure == null) {
                    this.cleanupFailure = new IOException("Docker exec ended before the peer process exited");
                }
                if (!stopRequested) {
                    stopRequested = true;
                    this.requestStop(true);
                }
                status = this.readStatus();
            }
            if (!terminal(status) || !status.path("exitCode").canConvertToInt()) {
                throw new IOException("Peer process has no final exit status");
            }
            this.evidence.put("state", status);
            this.processExitCode = status.path("exitCode").intValue();
            this.evidence.put("processExitCode", this.processExitCode);
            boolean treeCleaned = status.path("processGroupEmpty").asBoolean();
            boolean temporaryRemoved = status.path("temporaryDirectoryRemoved").asBoolean();
            this.evidence.put("processTreeCleaned", treeCleaned);
            this.evidence.put("temporaryDirectoryRemoved", temporaryRemoved);
            boolean failed = StringUtils.equals("failed", status.path("state").asText());
            boolean noRemainingPids = status.path("remainingPids").isArray() && status.path("remainingPids").isEmpty();
            boolean hasFailure = status.hasNonNull("cleanupFailure") || status.hasNonNull("error");
            if (failed || !treeCleaned || !noRemainingPids || !temporaryRemoved || hasFailure) {
                throw new IOException("Peer process cleanup failed: " + status);
            }
            Path stdout = this.directory.resolve("stdout.log");
            Path stderr = this.directory.resolve("stderr.log");
            if (!Files.isRegularFile(stdout) || !Files.isRegularFile(stderr)) {
                throw new IOException("Peer process output logs are missing");
            }
            this.evidence.put("stdoutLog", stdout.toString());
            this.evidence.put("stderrLog", stderr.toString());
            this.evidence.put("attachDelayed", attachDelayed);
            if (!this.attached.isAlive()) {
                int cliExitCode = this.attached.exitValue();
                if (cliExitCode != 0 && cliExitCode != this.processExitCode) {
                    throw new IOException("Docker exec failed with exit " + cliExitCode);
                }
            }
        } catch (IOException failure) {
            if (this.cleanupFailure == null) {
                this.cleanupFailure = failure;
            } else if (this.cleanupFailure != failure) {
                this.cleanupFailure.addSuppressed(failure);
            }
            if (!terminal(status) && !stopRequested) {
                // 状态缺失或损坏也要通过这份 launch 请求取消，不能只结束本地 CLI 留下远端业务进程。
                try {
                    this.requestStop(true);
                    JsonNode stopped = this.readStatus();
                    this.evidence.put("state", stopped);
                } catch (IOException cleanup) {
                    this.cleanupFailure.addSuppressed(cleanup);
                }
            }
        } finally {
            // 真实状态与完整日志先持久化，最后释放本对象拥有的 exec CLI。
            if (this.attached.isAlive()) {
                this.attached.destroyForcibly();
            }
            try {
                if (!this.attached.waitFor(30, TimeUnit.SECONDS)) {
                    throw new IOException("Docker exec CLI did not stop");
                }
                this.evidence.put("execReaped", true);
                this.evidence.put("execExitCode", this.attached.exitValue());
            } catch (IOException | InterruptedException failure) {
                if (failure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                if (this.cleanupFailure == null) {
                    this.cleanupFailure = new IOException("Cannot reap Docker exec CLI", failure);
                }
            }
            this.evidence.put("finishedAt", Instant.now().toString());
            this.evidence.put("cleanupFinishedNanos", System.nanoTime());
            this.evidence.put("cleaned", this.cleanupFailure == null && this.processExitCode != null);
            try {
                if (this.cleanupFailure != null) {
                    this.evidence.put("cleanupFailure", this.cleanupFailure.getMessage());
                }
                writeEvidence(this.directory, this.evidence);
            } catch (IOException failure) {
                this.cleanupFailure = failure;
            }
            this.cleaned.complete(null);
        }
    }

    private void awaitCleanup() throws InterruptedException {
        try {
            this.cleaned.get();
        } catch (ExecutionException impossible) {
            throw new IllegalStateException(impossible.getCause());
        }
        this.checkCleanup();
    }

    private void checkCleanup() {
        if (this.cleanupFailure != null) {
            throw new IllegalStateException("Cannot finish peer process " + this.evidence.get("processId"),
                    this.cleanupFailure);
        }
    }

    private static JsonNode serviceIdentity(String service) throws IOException {
        JsonNode cached = SERVICES.get(service);
        if (cached != null) {
            return cached;
        }
        JsonNode result = JSON.readTree(docker(List.of("inspect", service)));
        JsonNode inspection = result == null ? null : result.path(0);
        if (inspection == null || !inspection.path("State").path("Running").asBoolean()) {
            throw new IOException("Peer service is not running: " + service);
        }
        SERVICES.put(service, inspection);
        return inspection;
    }

    private static Map<String, String> environmentChanges(Map<String, String> configured, String hostname,
            Path directory) {
        Map<String, String> changes = new LinkedHashMap<>();
        Map<String, String> inherited = System.getenv();
        for (Map.Entry<String, String> entry : configured.entrySet()) {
            if (!StringUtils.equals(inherited.get(entry.getKey()), entry.getValue())) {
                changes.put(entry.getKey(), entry.getValue());
            }
        }
        changes.put("METEOR_TEST_PEER_HOST", hostname);
        changes.put("METEOR_TEST_RUNNER_HOST", runnerHost());
        changes.put("METEOR_TEST_PEER_EVIDENCE_DIR", directory.toString());
        String privateHosts = ".meteor.test,localhost,127.0.0.1,::1";
        String exclusions = configured.getOrDefault("NO_PROXY", "");
        if (!exclusions.isBlank()) {
            privateHosts = exclusions + "," + privateHosts;
        }
        changes.put("NO_PROXY", privateHosts);
        changes.put("no_proxy", privateHosts);
        if (configured.containsKey("JAVA_TOOL_OPTIONS")) {
            changes.put("JAVA_TOOL_OPTIONS", configured.get("JAVA_TOOL_OPTIONS"));
        }
        return changes;
    }

    private static void writeEvidence(Path directory, Map<String, Object> evidence) throws IOException {
        synchronized (EVIDENCE_LOCK) {
            String processId = (String) evidence.get("processId");
            byte[] snapshot = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence);
            Files.write(directory.getParent().resolve(processId + ".json"), snapshot);
            byte[] event = (JSON.writeValueAsString(evidence) + "\n").getBytes(StandardCharsets.UTF_8);
            Path events = directory.getParent().resolve("peers.jsonl");
            Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, //
                    StandardOpenOption.APPEND);
            try (FileChannel channel = FileChannel.open(events, options); FileLock ignored = channel.lock()) {
                ByteBuffer bytes = ByteBuffer.wrap(event);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
            }
        }
    }

    private static List<String> redactCommand(List<String> command) {
        List<String> redacted = new ArrayList<>();
        boolean hideNext = false;
        for (String argument : command) {
            if (hideNext) {
                redacted.add("<redacted>");
                hideNext = false;
                continue;
            }
            String normalized = argument.toLowerCase(Locale.ROOT);
            boolean password = normalized.contains("password");
            boolean token = normalized.contains("token");
            boolean secret = password || token || normalized.contains("secret");
            int equals = argument.indexOf('=');
            if (secret && equals > 0) {
                redacted.add(argument.substring(0, equals + 1) + "<redacted>");
            } else {
                redacted.add(argument);
                hideNext = secret && argument.startsWith("-");
            }
        }
        return redacted;
    }

    private static List<String> dockerCommand() {
        return new ArrayList<>(List.of(environment("METEOR_TEST_DOCKER_COMMAND", "docker")));
    }

    private static String docker(List<String> arguments) throws IOException {
        List<String> command = dockerCommand();
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        return commandResult(process, arguments, 30, TimeUnit.SECONDS);
    }

    static String commandResult(Process process, List<String> command, long timeout, TimeUnit unit) throws IOException {
        // CLI 退出与输出 EOF 共用同一截止时间，不能在 waitFor 之后无限等待继承管道。
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try (InputStream input = process.getInputStream()) {
                return input.readAllBytes();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        IOException commandFailure = null;
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) {
                throw new IOException("Docker command timed out: " + String.join(" ", command));
            }
            remaining = Math.max(0, deadline - System.nanoTime());
            String text = new String(output.get(remaining, TimeUnit.NANOSECONDS), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("Docker command failed (" + process.exitValue() + "): " + text.trim());
            }
            return text;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            commandFailure = new IOException("Interrupted while controlling peer process", interrupted);
            throw commandFailure;
        } catch (ExecutionException failure) {
            commandFailure = new IOException("Cannot read Docker command output", failure.getCause());
            throw commandFailure;
        } catch (TimeoutException failure) {
            commandFailure = new IOException("Docker command output did not reach EOF within its deadline", failure);
            throw commandFailure;
        } catch (IOException failure) {
            commandFailure = failure;
            throw failure;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            if (!output.isDone()) {
                output.cancel(true);
                CompletableFuture<Void> closed = new CompletableFuture<>();
                Thread closer = new Thread(() -> {
                    try {
                        process.getInputStream().close();
                        closed.complete(null);
                    } catch (IOException failure) {
                        closed.completeExceptionally(failure);
                    }
                }, "meteor-docker-output-close");
                closer.setDaemon(true);
                closer.start();
                try {
                    closed.get(1, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    IOException cleanup = new IOException("Docker command output cleanup did not finish", failure);
                    if (commandFailure != null) {
                        commandFailure.addSuppressed(cleanup);
                    } else {
                        throw cleanup;
                    }
                }
            }
        }
    }

    private static String timedDocker(List<String> arguments, List<Map<String, Object>> timings) throws IOException {
        long started = System.nanoTime();
        boolean succeeded = false;
        try {
            String result = docker(arguments);
            succeeded = true;
            return result;
        } finally {
            Map<String, Object> timing = new LinkedHashMap<>();
            timing.put("operation", arguments.get(0));
            timing.put("startedNanos", started);
            timing.put("finishedNanos", System.nanoTime());
            timing.put("succeeded", succeeded);
            synchronized (EVIDENCE_LOCK) {
                timings.add(timing);
            }
        }
    }

    private static String requireEnvironment(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing peer service environment: " + name);
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
