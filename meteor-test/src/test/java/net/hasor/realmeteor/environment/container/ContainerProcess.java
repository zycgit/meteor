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
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;

/** 拥有一个对端容器，默认使用独立网络命名空间，并保留 Process 的标准输入输出与退出语义。 */
public final class ContainerProcess extends Process {
    private static final ObjectMapper          JSON          = new ObjectMapper();
    private static final Map<String, JsonNode> IMAGES        = new ConcurrentHashMap<>();
    private static final Object                EVIDENCE_LOCK = new Object();

    private final    Process                 attached;
    private final    String                  container;
    private final    String                  hostname;
    private final    Path                    evidenceDirectory;
    private final    Map<String, Object>     evidence;
    private final    AtomicBoolean           cleaning = new AtomicBoolean();
    private final    CompletableFuture<Void> cleaned  = new CompletableFuture<>();
    private volatile IOException             cleanupFailure;
    private volatile String                  stopFailure;

    private ContainerProcess(Process attached, String container, String hostname, Path evidenceDirectory, Map<String, Object> evidence) {
        this.attached = attached;
        this.container = container;
        this.hostname = hostname;
        this.evidenceDirectory = evidenceDirectory;
        this.evidence = evidence;
        this.attached.onExit().thenRun(this::finish);
    }

    public static ContainerProcess start(String runtime, ProcessBuilder command) throws IOException {
        return start(runtime, command, false);
    }

    /** 仅用于协议要求本机控制目标的场景；独立对端进程共享 runner 网络，不计为独立网络互通。 */
    public static ContainerProcess startLocal(String runtime, ProcessBuilder command) throws IOException {
        return start(runtime, command, true);
    }

    private static ContainerProcess start(String runtime, ProcessBuilder command, boolean localControl) throws IOException {
        // 镜像、网络和共享卷由外层启动器拥有；对端只拥有本次创建的容器，不接触 Docker socket。
        String normalizedRuntime = runtime.toUpperCase(Locale.ROOT).replace('-', '_');
        String image = requireEnvironment("METEOR_TEST_IMAGE_" + normalizedRuntime);
        String network = requireEnvironment("METEOR_TEST_NETWORK");
        String workspace = requireEnvironment("METEOR_TEST_WORKSPACE_VOLUME");
        String gradle = requireEnvironment("METEOR_TEST_GRADLE_VOLUME");
        String maven = requireEnvironment("METEOR_TEST_MAVEN_VOLUME");
        if (command.command().isEmpty()) {
            throw new IOException("Container peer command must not be empty");
        }
        JsonNode identity = imageIdentity(image);
        String container = "meteor-peer-" + UUID.randomUUID();
        String hostname = container + ".meteor.test";
        String runId = environment("METEOR_TEST_RUN_ID", "unassigned");
        String sharedNetworkContainer = null;
        if (localControl) {
            // rpcbind SET/UNSET 的公开 API 限定本机目标，不能为测试放宽生产约束。
            sharedNetworkContainer = "meteor-test-" + requireEnvironment("METEOR_TEST_RUN_ID") + "-runner";
            network = "container:" + sharedNetworkContainer;
            hostname = runnerHost();
        }
        Path evidenceDirectory = evidenceDirectory(runId);

        // 参数顺序固定为所有权、网络、共享目录、工作目录、环境和程序；不经过 shell 拼接。
        List<String> create = new ArrayList<>();
        create.add("create");
        create.add("--interactive");
        create.add("--init");
        create.add("--name");
        create.add(container);
        addOption(create, "--label", "net.hasor.meteor.test.run=" + runId);
        addOption(create, "--label", "net.hasor.meteor.test.runtime=" + runtime);
        addOption(create, "--network", network);
        if (!localControl) {
            addOption(create, "--network-alias", hostname);
        }
        if (StringUtils.equals("BROWSER", normalizedRuntime)) {
            addOption(create, "--shm-size", "1g");
        }
        addOption(create, "--mount", "type=volume,source=" + workspace + ",target=/workspace");
        addOption(create, "--mount", "type=volume,source=" + gradle + ",target=/root/.gradle");
        addOption(create, "--mount", "type=volume,source=" + maven + ",target=/root/.m2");
        Path directory = command.directory() == null ? Path.of("").toAbsolutePath() : command.directory().toPath();
        addOption(create, "--workdir", directory.toAbsolutePath().normalize().toString());
        addEnvironment(create, command.environment(), hostname);
        addOption(create, "--entrypoint", "/usr/bin/env");
        create.add(identity.path("Id").asText());
        create.addAll(command.command());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("runId", runId);
        evidence.put("runtime", runtime);
        evidence.put("container", container);
        evidence.put("hostname", hostname);
        evidence.put("network", network);
        evidence.put("networkMode", localControl ? "runner" : "bridge");
        evidence.put("independentNetworkNamespace", !localControl);
        if (localControl) {
            evidence.put("sharedNetworkContainer", sharedNetworkContainer);
        }
        evidence.put("image", image);
        evidence.put("imageId", identity.path("Id").asText());
        evidence.put("imageDigests", identity.path("RepoDigests"));
        evidence.put("os", identity.path("Os").asText());
        evidence.put("architecture", identity.path("Architecture").asText());
        evidence.put("command", redactCommand(command.command()));
        evidence.put("workingDirectory", directory.toAbsolutePath().normalize().toString());
        evidence.put("createdAt", Instant.now().toString());

        // attach 使用调用者的重定向，因此现有端口通告、标准输入控制和日志读取继续生效。
        boolean created = false;
        Process attached = null;
        try {
            String containerId = docker(create);
            created = true;
            evidence.put("containerId", containerId.trim());
            writeEvidence(evidenceDirectory, container, evidence);
            List<String> attachCommand = dockerCommand();
            attachCommand.add("start");
            attachCommand.add("--attach");
            attachCommand.add("--interactive");
            attachCommand.add(container);
            ProcessBuilder attach = new ProcessBuilder(attachCommand);
            attach.environment().clear();
            attach.environment().putAll(command.environment());
            attach.redirectInput(command.redirectInput());
            attach.redirectOutput(command.redirectOutput());
            attach.redirectError(command.redirectError());
            attach.redirectErrorStream(command.redirectErrorStream());
            attached = attach.start();
            captureStartedNetwork(container, attached, evidence);
            writeEvidence(evidenceDirectory, container, evidence);
            return new ContainerProcess(attached, container, hostname, evidenceDirectory, evidence);
        } catch (IOException failure) {
            evidence.put("startupFailure", failure.getMessage());
            if (created) {
                try {
                    docker(List.of("rm", "--force", container));
                    evidence.put("removed", true);
                } catch (IOException cleanup) {
                    evidence.put("removed", false);
                    evidence.put("cleanupFailure", cleanup.getMessage());
                    failure.addSuppressed(cleanup);
                }
            }
            if (attached != null) {
                attached.destroyForcibly();
            }
            try {
                writeEvidence(evidenceDirectory, container, evidence);
            } catch (IOException evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            throw failure;
        }
    }

    public static String host(Process process) {
        if (!(process instanceof ContainerProcess)) {
            throw new IllegalArgumentException("Expected a container peer process");
        }
        return ((ContainerProcess) process).hostname;
    }

    public static String runnerHost() {
        return environment("METEOR_TEST_RUNNER_HOST", "runner.meteor.test");
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
        int exit = this.attached.waitFor();
        this.finish();
        this.awaitCleanup();
        return exit;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
        if (!this.attached.waitFor(timeout, unit)) {
            return false;
        }
        this.finish();
        this.awaitCleanup();
        return true;
    }

    @Override
    public int exitValue() {
        return this.attached.exitValue();
    }

    @Override
    public boolean isAlive() {
        return this.attached.isAlive();
    }

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
        return this.attached.onExit().thenApply(process -> {
            this.finish();
            this.cleaned.join();
            this.checkCleanup();
            return this;
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
        // 终止真实容器而非仅终止 docker attach；同一对象只在 finish 中释放容器。
        if (this.attached.isAlive() && !this.cleaning.get()) {
            try {
                if (force) {
                    docker(List.of("kill", this.container));
                } else {
                    docker(List.of("stop", "--time", "3", this.container));
                }
            } catch (IOException failure) {
                // 退出与停止可以同时到达；最终以容器是否成功删除判断资源释放结果。
                this.stopFailure = failure.getMessage();
            }
        }
        this.finish();
        if (force) {
            this.attached.destroyForcibly();
        } else {
            this.attached.destroy();
        }
        this.checkCleanup();
    }

    private void finish() {
        if (!this.cleaning.compareAndSet(false, true)) {
            return;
        }
        try {
            // 退出状态与失败日志在删除前采集，报告包含实际运行的镜像与容器网络信息。
            this.evidence.put("finishedAt", Instant.now().toString());
            try {
                JsonNode inspection = JSON.readTree(docker(List.of("inspect", this.container)));
                JsonNode containerData = inspection.path(0);
                this.evidence.put("state", containerData.path("State"));
                this.evidence.put("finishedNetworks", containerData.path("NetworkSettings").path("Networks"));
                int exitCode = containerData.path("State").path("ExitCode").asInt();
                if (exitCode != 0) {
                    String logs = docker(List.of("logs", "--tail", "200", this.container));
                    Path logFile = this.evidenceDirectory.resolve(this.container + ".log");
                    Files.writeString(logFile, logs);
                    this.evidence.put("failureLog", logFile.toString());
                }
            } catch (IOException inspectionFailure) {
                this.evidence.put("inspectionFailure", inspectionFailure.getMessage());
            }
            try {
                docker(List.of("rm", "--force", this.container));
                this.evidence.put("removed", true);
            } catch (IOException removalFailure) {
                this.cleanupFailure = removalFailure;
                this.evidence.put("removed", false);
                this.evidence.put("cleanupFailure", removalFailure.getMessage());
            }
            if (this.stopFailure != null) {
                this.evidence.put("stopFailure", this.stopFailure);
            }
            writeEvidence(this.evidenceDirectory, this.container, this.evidence);
        } catch (IOException evidenceFailure) {
            this.cleanupFailure = evidenceFailure;
        } finally {
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
            throw new IllegalStateException("Cannot clean up container peer " + this.container, this.cleanupFailure);
        }
    }

    private static void captureStartedNetwork(String container, Process attached, Map<String, Object> evidence) throws IOException {
        // 只等待 Docker 从 created 进入运行或退出状态；应用端口和业务就绪仍由各场景自行观察。
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            JsonNode inspection = JSON.readTree(docker(List.of("inspect", container)));
            JsonNode containerData = inspection.path(0);
            JsonNode state = containerData.path("State");
            String status = state.path("Status").asText();
            evidence.put("startedAt", state.path("StartedAt").asText());
            evidence.put("networkCaptureState", status);
            evidence.put("dockerNetworkMode", containerData.path("HostConfig").path("NetworkMode").asText());
            evidence.put("networks", containerData.path("NetworkSettings").path("Networks"));
            boolean created = StringUtils.equals("created", status);
            boolean starting = created && attached.isAlive();
            if (!starting || System.nanoTime() >= deadline) {
                evidence.put("networkCapturedWhileRunning", state.path("Running").asBoolean());
                return;
            }
        }
    }

    private static void addEnvironment(List<String> create, Map<String, String> environment, String hostname) {
        // 只传递调用者显式改动的变量，避免把 runner 的凭据和 Docker 控制参数复制到对端。
        Map<String, String> inherited = System.getenv();
        Map<String, String> changes = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (!StringUtils.equals(inherited.get(entry.getKey()), entry.getValue())) {
                changes.put(entry.getKey(), entry.getValue());
            }
        }
        for (String removed : inherited.keySet()) {
            if (!environment.containsKey(removed)) {
                changes.put(removed, "");
            }
        }
        changes.put("METEOR_TEST_PEER_HOST", hostname);
        // 对端需要 runner 的公开网络身份，即便调用者设置的值与继承环境完全相同。
        changes.put("METEOR_TEST_RUNNER_HOST", runnerHost());
        String exclusions = environment.getOrDefault("NO_PROXY", "");
        String privateHosts = ".meteor.test,runner.meteor.test,localhost,127.0.0.1,::1";
        if (!exclusions.isBlank()) {
            privateHosts = exclusions + "," + privateHosts;
        }
        changes.put("NO_PROXY", privateHosts);
        changes.put("no_proxy", privateHosts);
        String javaOptions = environment.get("JAVA_TOOL_OPTIONS");
        if (javaOptions != null && !javaOptions.isBlank()) {
            changes.put("JAVA_TOOL_OPTIONS", javaOptions);
        }
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            addOption(create, "--env", entry.getKey() + "=" + entry.getValue());
        }
    }

    private static JsonNode imageIdentity(String image) throws IOException {
        JsonNode cached = IMAGES.get(image);
        if (cached != null) {
            return cached;
        }
        JsonNode inspection = JSON.readTree(docker(List.of("image", "inspect", image)));
        JsonNode identity = inspection.path(0);
        if (identity.path("Id").asText().isBlank()) {
            throw new IOException("Docker image has no identity: " + image);
        }
        IMAGES.put(image, identity);
        return identity;
    }

    private static Path evidenceDirectory(String runId) throws IOException {
        String configured = environment("METEOR_TEST_EVIDENCE_DIR", "build/docker-evidence/" + runId);
        return Files.createDirectories(Path.of(configured).toAbsolutePath());
    }

    private static void writeEvidence(Path directory, String container, Map<String, Object> evidence) throws IOException {
        synchronized (EVIDENCE_LOCK) {
            byte[] snapshot = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(evidence);
            Files.write(directory.resolve(container + ".json"), snapshot);
            byte[] event = (JSON.writeValueAsString(evidence) + "\n").getBytes(StandardCharsets.UTF_8);
            Path events = directory.resolve("peers.jsonl");
            Set<OpenOption> options = Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
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

    private static void addOption(List<String> command, String option, String value) {
        command.add(option);
        command.add(value);
    }

    private static List<String> dockerCommand() {
        List<String> command = new ArrayList<>();
        command.add(environment("METEOR_TEST_DOCKER_COMMAND", "docker"));
        return command;
    }

    private static String docker(List<String> arguments) throws IOException {
        List<String> command = dockerCommand();
        command.addAll(arguments);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        });
        try {
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Docker command timed out: " + String.join(" ", arguments));
            }
            String text = new String(output.join(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IOException("Docker command failed (" + process.exitValue() + "): " + text.trim());
            }
            return text;
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while controlling Docker peer", interrupted);
        } catch (CompletionException failure) {
            throw new IOException("Cannot read Docker command output", failure.getCause());
        }
    }

    private static String requireEnvironment(String name) throws IOException {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing container test environment: " + name);
        }
        return value;
    }

    private static String environment(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }
}
