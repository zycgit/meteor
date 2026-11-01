/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */
package net.hasor.meteor.test.nxn;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Timeout;

/** 使用真实 Linux 进程验证执行器边界，不启动 Docker 或 RPC 服务。 */
public class NxnProcessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private Path directory;
    private final List<Map.Entry<Process, Path>> wrappers = new ArrayList<>();

    @BeforeEach
    public void prepare() throws IOException {
        this.directory = Files.createTempDirectory("meteor-native-process-");
        assertTrue(Files.isExecutable(executable()), "Native executor must be built in the test container");
    }

    @AfterEach
    public void release() throws Exception {
        // 测试失败时仍回收本方法创建的真实执行器和子进程。
        for (Map.Entry<Process, Path> wrapper : this.wrappers) {
            Process process = wrapper.getKey();
            if (process.isAlive()) {
                control(wrapper.getValue(), true);
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Owned native wrapper did not exit");
            }
            process.getOutputStream().close();
            process.getInputStream().close();
            process.getErrorStream().close();
        }
        if (this.directory != null) {
            try (var files = Files.walk(this.directory)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void stdinEofPreservesBinaryStdoutAndStderr() throws Exception {
        // 二进制内容通过标准输入进入两个独立的输出日志。
        Path launch = launch("binary", "tee /dev/fd/2");
        Process process = start(launch);
        byte[] payload = new byte[256 * 1024];
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) index;
        }
        CompletableFuture<byte[]> output = readAll(process.getInputStream());
        CompletableFuture<byte[]> error = readAll(process.getErrorStream());
        process.getOutputStream().write(payload);
        process.getOutputStream().close();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 两份日志完整保存后才能发布终态。
        assertArrayEquals(payload, output.get(1, TimeUnit.SECONDS));
        assertArrayEquals(payload, error.get(1, TimeUnit.SECONDS));
        assertArrayEquals(payload, Files.readAllBytes(launch.resolveSibling("stdout.log")));
        assertArrayEquals(payload, Files.readAllBytes(launch.resolveSibling("stderr.log")));
        assertClean(launch, status(launch));
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nonzeroExitSurvivesIdempotentStop() throws Exception {
        // 真实 SDK 失败保留业务退出码，并完成所属资源清理。
        Path launch = launch("nonzero", "printf 'failed\\n'; exit 19");
        Process process = start(launch);
        assertEquals("failed\n", new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 重复停止只确认清理，不覆盖原业务退出码。
        assertEquals(19, process.exitValue());
        assertEquals(0, control(launch, false).exitValue());
        JsonNode result = status(launch);
        assertEquals(19, result.path("exitCode").asInt());
        assertEquals(19, result.path("rawExitCode").asInt());
        assertClean(launch, result);
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void gracefulCancelReapsDetachedDescendant() throws Exception {
        // 子进程离开原会话并忽略 TERM。
        Path launch = launch("graceful", detached(false, false));
        Process process = start(launch);
        long descendant = Long.parseLong(line(process.getInputStream()));
        assertEquals(0, control(launch, false).exitValue());
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 升级终止仅针对所属子孙，主进程仍保留 TERM 退出码。
        JsonNode result = status(launch);
        assertEquals(143, result.path("exitCode").asInt());
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertClean(launch, result);
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void forcedCancelSkipsTermGrace() throws Exception {
        // 主进程与脱离会话的子孙均忽略普通 TERM。
        Path launch = launch("forced", detached(true, false));
        Process process = start(launch);
        long descendant = Long.parseLong(line(process.getInputStream()));
        long started = System.nanoTime();
        assertEquals(0, control(launch, true).exitValue());
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 强制取消直接按进程身份发送 KILL，并回收全部所属进程。
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 3000);
        JsonNode result = status(launch);
        assertEquals(137, result.path("exitCode").asInt());
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertClean(launch, result);
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void concurrentNativeControlsCannotDowngradeForce() throws Exception {
        // 两个独立控制进程同时停止同一个忽略 TERM 的业务进程树。
        Path launch = launch("concurrent-controls", detached(true, false));
        Process process = start(launch);
        line(process.getInputStream());
        String nativeExecutable = executable().toString();
        Process force = new ProcessBuilder(nativeExecutable, "--stop", launch.toString(), "--force").start();
        Process graceful = new ProcessBuilder(nativeExecutable, "--stop", launch.toString()).start();
        assertTrue(force.waitFor(10, TimeUnit.SECONDS));
        assertTrue(graceful.waitFor(10, TimeUnit.SECONDS));
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // flock 下的合并保留强制请求，后来的普通停止不能降级它。
        assertEquals(0, force.exitValue());
        assertEquals(0, graceful.exitValue());
        String[] control = Files.readString(launch.resolveSibling("cancel.control")).split("\0", -1);
        assertEquals("1", control[2]);
        JsonNode result = status(launch);
        assertEquals(137, result.path("exitCode").asInt());
        assertClean(launch, result);
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void successfulMainExitStillReapsDetachedDescendant() throws Exception {
        // 主命令成功退出，但独立会话中的工作进程仍然存活。
        Path launch = launch("normal-orphan", detached(false, true));
        Process process = start(launch);
        long descendant = Long.parseLong(line(process.getInputStream()));
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 业务成功也不能在常驻语言容器中遗留工作进程。
        JsonNode result = status(launch);
        assertEquals(0, result.path("exitCode").asInt());
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertClean(launch, result);
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellationLeavesAnotherInvocationRunning() throws Exception {
        // 两个执行器共享 Linux 命名空间，各自拥有输入和进程树。
        Path first = launch("first", "printf 'first\\n'; sleep 60");
        Path second = launch("second", "printf 'second\\n'; IFS= read -r value; printf '%s\\n' \"$value\"");
        Process firstProcess = start(first);
        Process secondProcess = start(second);
        assertEquals("first", line(firstProcess.getInputStream()));
        assertEquals("second", line(secondProcess.getInputStream()));
        assertEquals(0, control(first, true).exitValue());

        // 无关调用仍可完成输入输出交换并正常退出。
        assertTrue(secondProcess.isAlive());
        secondProcess.getOutputStream().write("unaffected\n".getBytes(StandardCharsets.UTF_8));
        secondProcess.getOutputStream().close();
        assertEquals("unaffected", line(secondProcess.getInputStream()));
        assertTrue(firstProcess.waitFor(10, TimeUnit.SECONDS));
        assertTrue(secondProcess.waitFor(10, TimeUnit.SECONDS));
        assertClean(first, status(first));
        assertClean(second, status(second));
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellationBeforeStartupPreventsBusinessLaunch() throws Exception {
        // 取消请求先于执行器启动，不能因启动时序而丢失。
        Path launch = launch("before-start", "touch escaped");
        Process controller = controlBuilder(launch, false).start();
        awaitFile(launch.resolveSibling("cancel.control"));
        Process process = start(launch);
        assertTrue(controller.waitFor(15, TimeUnit.SECONDS));
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 启动前取消不产生业务进程或应用副作用。
        JsonNode result = status(launch);
        assertEquals(0, controller.exitValue());
        assertEquals(143, result.path("exitCode").asInt());
        assertTrue(result.path("pid").isNull());
        assertFalse(Files.exists(launch.resolveSibling("escaped")));
        assertClean(launch, result);
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void stalePidStartTickIsNeverSignalled() throws Exception {
        // 旧状态指向本测试 JVM，但使用不同的内核启动时刻。
        Path launch = launch("reused-pid", "true");
        long pid = ProcessHandle.current().pid();
        String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
        long startTicks = Long.parseLong(stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19]);
        writeState(launch, Map.of("processId", "reused-pid", "state", "running",
                "wrapperPid", Long.toString(pid), "wrapperStartTicks", Long.toString(startTicks + 1)));
        Process controller = control(launch, false);

        // 身份冲突必须报告失败，不能向本测试 JVM 发送信号。
        assertEquals(1, controller.exitValue());
        String error = new String(controller.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(error.contains("identity no longer matches"));
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cleanTerminalPublishedDuringIdentityLookupAcknowledgesStop() throws Exception {
        // 控制器读取 running 后，监督器恰好发布完整终态并退出。
        Path launch = launch("terminal-during-identity", "true");
        Process controller = identityRace(launch, "clean");

        // 同一任务已完成清理，应确认停止成功并保留原始退出码。
        String error = new String(controller.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, controller.exitValue(), error);
        JsonNode result = NxnProcess.readStatus(launch);
        assertEquals(143, result.path("exitCode").asInt());
        assertClean(launch, result);
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void failedTerminalPublishedDuringIdentityLookupStillFailsStop() throws Exception {
        // 身份查询时发布失败终态，即使资源已经释放也不能改记为成功。
        Path launch = launch("failed-during-identity", "true");
        Process controller = identityRace(launch, "failed");

        // 保留监督器报告的失败及其清理证据。
        assertEquals(1, controller.exitValue());
        JsonNode result = NxnProcess.readStatus(launch);
        assertEquals("failed", result.path("state").asText());
        assertEquals("Fixture cleanup failure", result.path("error").asText());
        assertReleased(result);
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void differentPeerTerminalDuringIdentityLookupStillFailsStop() throws Exception {
        // 身份查询时出现另一个任务的终态，不能接纳它的成功结果。
        Path launch = launch("wrong-peer-during-identity", "true");
        Process controller = identityRace(launch, "wrong-id");

        // 原任务仍报告控制失败，不向其它进程发送信号。
        assertEquals(1, controller.exitValue());
        JsonNode result = NxnProcess.readStatus(launch);
        assertEquals("another-peer", result.path("processId").asText());
        assertEquals("exited", result.path("state").asText());
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingWrapperWithRunningStateStillFailsStop() throws Exception {
        // 监督器身份已经消失，但新快照仍未确认业务退出。
        Path launch = launch("running-without-wrapper", "true");
        Process controller = identityRace(launch, "still-running");

        // 未得到终态不能将取消当作已完成。
        assertEquals(1, controller.exitValue());
        JsonNode result = NxnProcess.readStatus(launch);
        assertEquals("running", result.path("state").asText());
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void uncleanTerminalDuringIdentityLookupStillFailsStop() throws Exception {
        // 身份查询时发布退出状态，但仍有业务进程未清理。
        Path launch = launch("unclean-during-identity", "true");
        Process controller = identityRace(launch, "unclean");

        // 不完整的收尾证据必须继续使本次停止失败。
        assertEquals(1, controller.exitValue());
        JsonNode result = NxnProcess.readStatus(launch);
        assertEquals("exited", result.path("state").asText());
        assertFalse(result.path("processGroupEmpty").asBoolean());
        assertEquals(123, result.path("remainingPids").get(0).asInt());
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void missingCancellationIdCleansOnlyTheOwnedTree() throws Exception {
        // 一个执行器收到损坏的控制文件，另一个独立执行器保持运行。
        Path launch = launch("missing-id", detached(false, false));
        Path other = launch("other-missing", "printf 'ready\\n'; read value; echo \"$value\"");
        Process process = start(launch);
        Process otherProcess = start(other);
        long descendant = Long.parseLong(line(process.getInputStream()));
        assertEquals("ready", line(otherProcess.getInputStream()));
        Files.write(launch.resolveSibling("cancel.control"), "meteor-cancel-v1\0".getBytes(StandardCharsets.UTF_8));
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 控制数据损坏仍强制清理所属资源，不影响另一棵进程树。
        JsonNode result = status(launch);
        assertEquals("failed", result.path("state").asText());
        assertTrue(result.path("error").asText().contains("processId"));
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertReleased(result);
        assertTrue(otherProcess.isAlive());
        otherProcess.getOutputStream().write("unaffected\n".getBytes(StandardCharsets.UTF_8));
        otherProcess.getOutputStream().close();
        assertEquals("unaffected", line(otherProcess.getInputStream()));
        assertTrue(otherProcess.waitFor(10, TimeUnit.SECONDS));
        assertClean(other, status(other));
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void mismatchedCancellationIdCleansOnlyTheOwnedTree() throws Exception {
        // 请求误指向另一个任务，但执行器仍须回收自己持有的独立会话子孙。
        Path launch = launch("mismatched-id", detached(false, false));
        Path other = launch("other-mismatch", "printf 'ready\\n'; read value; echo \"$value\"");
        Process process = start(launch);
        Process otherProcess = start(other);
        long descendant = Long.parseLong(line(process.getInputStream()));
        assertEquals("ready", line(otherProcess.getInputStream()));
        String request = "meteor-cancel-v1\0another-job\0" + "1\0\0";
        Files.write(launch.resolveSibling("cancel.control"), request.getBytes(StandardCharsets.UTF_8));
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 失败请求只释放当前执行器的子孙和私有目录。
        JsonNode result = status(launch);
        assertEquals("failed", result.path("state").asText());
        assertTrue(result.path("error").asText().contains("does not match launch"));
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertReleased(result);
        assertTrue(otherProcess.isAlive());
        otherProcess.getOutputStream().write("unaffected\n".getBytes(StandardCharsets.UTF_8));
        otherProcess.getOutputStream().close();
        assertEquals("unaffected", line(otherProcess.getInputStream()));
        assertTrue(otherProcess.waitFor(10, TimeUnit.SECONDS));
        assertClean(other, status(other));
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void invalidCancellationJsonIsRepairedWithRetainedError() throws Exception {
        // Java 修复损坏的 JSON，并请求原生执行器释放所属进程树。
        Path launch = launch("invalid-json", detached(false, false));
        Process process = start(launch);
        long descendant = Long.parseLong(line(process.getInputStream()));
        Files.writeString(launch.resolveSibling("cancel.json"), "{invalid");
        Process controller = control(launch, true);
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 修复后的控制请求仍保留原始失败，并完整释放资源。
        JsonNode request = JSON.readTree(Files.readAllBytes(launch.resolveSibling("cancel.json")));
        JsonNode result = status(launch);
        assertEquals(1, controller.exitValue());
        assertTrue(request.path("previousError").asText().contains("Invalid cancellation file"));
        assertTrue(result.path("error").asText().contains("Invalid cancellation file"));
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertReleased(result);
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void deeplyNestedCancellationJsonCannotInterruptCleanup() throws Exception {
        // JSON 深度越界属于控制错误，不能使运行中的 SDK 泄漏。
        Path launch = launch("nested-json", detached(false, false));
        Process process = start(launch);
        long descendant = Long.parseLong(line(process.getInputStream()));
        String nested = "[".repeat(10000) + "0" + "]".repeat(10000);
        Files.writeString(launch.resolveSibling("cancel.json"), nested);
        assertEquals(1, control(launch, true).exitValue());
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 所属进程与临时数据清理完毕后，解析失败仍可查询。
        JsonNode result = status(launch);
        assertTrue(result.path("error").asText().contains("Invalid cancellation file"));
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertReleased(result);
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void cancellationReadFailureForcesCleanup() throws Exception {
        // 使用目录模拟读取合法控制内容之前的系统调用失败。
        Path launch = launch("control-read-error", detached(false, false));
        Process process = start(launch);
        long descendant = Long.parseLong(line(process.getInputStream()));
        Files.createDirectory(launch.resolveSibling("cancel.control"));
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 系统调用失败不逃离取消边界，全部所属子孙仍会回收。
        JsonNode result = status(launch);
        assertEquals("failed", result.path("state").asText());
        assertTrue(result.path("error").asText().contains("Invalid cancellation file"));
        assertFalse(Files.exists(Path.of("/proc", Long.toString(descendant))));
        assertReleased(result);
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void redirectedOutputEvidenceRemainsComplete() throws Exception {
        // 文件重定向同时保留外部输出和执行器独立日志。
        Path launch = launch("redirected", "head -c 1048576 /dev/zero; head -c 1048576 /dev/zero >&2");
        ProcessBuilder builder = runBuilder(launch);
        Path stdout = this.directory.resolve("external.out");
        Path stderr = this.directory.resolve("external.err");
        builder.redirectOutput(stdout.toFile());
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        this.wrappers.add(Map.entry(process, launch));
        process.getOutputStream().close();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));

        // 全部重定向字节持久化后才发布终态。
        assertEquals(1048576, Files.size(stdout));
        assertEquals(1048576, Files.size(stderr));
        assertArrayEquals(Files.readAllBytes(stdout), Files.readAllBytes(launch.resolveSibling("stdout.log")));
        assertArrayEquals(Files.readAllBytes(stderr), Files.readAllBytes(launch.resolveSibling("stderr.log")));
        assertClean(launch, status(launch));
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void invocationReceivesPrivateTmpAndJavaHomeOptions() throws Exception {
        // 目录名中的空格经过原生参数和 JVM 选项引用后保持完整。
        Path launch = launch("temporary with spaces",
                "printf '%s\\000' \"$TMPDIR\" \"$TMP\" \"$TEMP\" \"$METEOR_TEST_JOB_DIRECTORY\""
                        + " \"$JDK_JAVA_OPTIONS\"; touch \"$TMPDIR/owned\"");
        Map<String, Object> definition = JSON.readValue(Files.readAllBytes(launch), new TypeReference<>() { });
        definition.put("environment", Map.of("JDK_JAVA_OPTIONS", "-Dexisting.option=kept"));
        NxnProcess.prepareLaunch(launch, definition);
        Process process = start(launch);
        String[] values = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).split("\0");
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 所有临时目录别名和 Java 默认目录都属于本次调用。
        String temporary = launch.resolveSibling("temporary").toString();
        for (int index = 0; index < 4; index++) {
            assertEquals(temporary, values[index]);
        }
        String expected = "-Dexisting.option=kept \"-Djava.io.tmpdir=" + temporary
                + "\" \"-Duser.home=" + temporary + "/home\"";
        assertEquals(expected, values[4]);
        assertClean(launch, status(launch));
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void concurrentHomesKeepSdkMetadataSeparate() throws Exception {
        // 两个对端使用相同的元数据文件名，并分别等待输入放行。
        String script = "mkdir -p \"$HOME/.dubbo\"; printf '%s' \"$JOB_MARKER\" > \"$HOME/.dubbo/metadata\"; "
                + "printf '%s' \"$JOB_MARKER\" > \"$XDG_CACHE_HOME/metadata\"; "
                + "printf '%s' \"$JOB_MARKER\" > \"$XDG_CONFIG_HOME/metadata\"; "
                + "printf '%s' \"$JOB_MARKER\" > \"$XDG_DATA_HOME/metadata\"; "
                + "printf '%s\\n' \"$HOME\"; read value; cat \"$HOME/.dubbo/metadata\"; "
                + "test \"$(cat \"$XDG_CACHE_HOME/metadata\")\" = \"$JOB_MARKER\"; "
                + "test \"$(cat \"$XDG_CONFIG_HOME/metadata\")\" = \"$JOB_MARKER\"; "
                + "test \"$(cat \"$XDG_DATA_HOME/metadata\")\" = \"$JOB_MARKER\"";
        Path first = launch("home-first", script);
        Path second = launch("home-second", script);
        setEnvironment(first, Map.of("JOB_MARKER", "first"));
        setEnvironment(second, Map.of("JOB_MARKER", "second"));
        Process firstProcess = start(first);
        Process secondProcess = start(second);
        String firstHome = line(firstProcess.getInputStream());
        String secondHome = line(secondProcess.getInputStream());

        // 独立 HOME 防止 SDK 缓存在并发调用之间串用。
        assertNotEquals(firstHome, secondHome);
        assertTrue(firstHome.startsWith(first.getParent().toString()));
        assertTrue(secondHome.startsWith(second.getParent().toString()));
        firstProcess.getOutputStream().write("release\n".getBytes(StandardCharsets.UTF_8));
        secondProcess.getOutputStream().write("release\n".getBytes(StandardCharsets.UTF_8));
        firstProcess.getOutputStream().close();
        secondProcess.getOutputStream().close();
        assertEquals("first", new String(firstProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("second", new String(secondProcess.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertTrue(firstProcess.waitFor(10, TimeUnit.SECONDS));
        assertTrue(secondProcess.waitFor(10, TimeUnit.SECONDS));
        assertClean(first, status(first));
        assertClean(second, status(second));
        assertFalse(Files.exists(Path.of(firstHome)));
        assertFalse(Files.exists(Path.of(secondHome)));
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void blockedInheritedOutputKeepsFullEvidenceAndFails() throws Exception {
        // 不读取 exec 输出，使业务输出超过管道容量。
        Path launch = launch("blocked", "head -c 1048576 /dev/zero; head -c 1048576 /dev/zero >&2");
        Process process = start(launch);
        JsonNode result = status(launch);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 输出交付超时有界失败，保留全部日志，不能报告完整成功。
        assertEquals("failed", result.path("state").asText());
        assertEquals(0, result.path("exitCode").asInt());
        assertEquals(1, process.exitValue());
        assertTrue(result.path("cleanupFailure").asText().contains("inherited output did not drain"));
        assertEquals(1048576, Files.size(launch.resolveSibling("stdout.log")));
        assertEquals(1048576, Files.size(launch.resolveSibling("stderr.log")));
        assertReleased(result);
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void brokenInheritedPipeDoesNotBlockEvidenceDrain() throws Exception {
        // exec 传输提前断开，业务仍继续生成输出。
        Path launch = launch("broken-pipe", "head -c 1048576 /dev/zero; head -c 1048576 /dev/zero >&2");
        Process process = start(launch);
        process.getInputStream().close();
        process.getErrorStream().close();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 传输断开后仍保存完整日志，不遗留所属进程树。
        assertEquals(1048576, Files.size(launch.resolveSibling("stdout.log")));
        assertEquals(1048576, Files.size(launch.resolveSibling("stderr.log")));
        assertClean(launch, status(launch));
    }

    @Test
    @Timeout(value = 20000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void failedOutputSpoolReapsItsWriterWithoutAffectingAnotherInvocation() throws Exception {
        // 真实故障设备拒绝日志写入，业务持续生成输出。
        Path launch = launch("failed-spool", "while :; do printf 'continuous-output'; done");
        Files.createSymbolicLink(launch.resolveSibling("stdout.log"), Path.of("/dev/full"));
        Path other = launch("other-spool", "printf 'ready\\n'; read value; echo \"$value\"");
        Process otherProcess = start(other);
        assertEquals("ready", line(otherProcess.getInputStream()));
        Process process = start(launch);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "A failed spool must not leave a blocked writer");

        // 输出失败有界收尾并保留诊断，无关执行器仍可正常使用。
        JsonNode result = status(launch);
        assertEquals("failed", result.path("state").asText());
        assertTrue(result.path("cleanupFailure").asText().contains("Cannot persist peer output"));
        assertReleased(result);
        assertTrue(otherProcess.isAlive());
        otherProcess.getOutputStream().write("unaffected\n".getBytes(StandardCharsets.UTF_8));
        otherProcess.getOutputStream().close();
        assertEquals("unaffected", line(otherProcess.getInputStream()));
        assertTrue(otherProcess.waitFor(10, TimeUnit.SECONDS));
        assertClean(other, status(other));
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void execFailureRetainsItsRealExit() throws Exception {
        // 缺少可执行文件明确报告命令错误。
        Path launch = launch("missing-command", "true");
        Map<String, Object> definition = JSON.readValue(Files.readAllBytes(launch), new TypeReference<>() { });
        definition.put("command", List.of("/definitely-missing-meteor-command"));
        NxnProcess.prepareLaunch(launch, definition);
        Process process = start(launch);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 原生清理后仍保留真实 exec 退出码与错误日志。
        assertEquals(127, process.exitValue());
        assertEquals(127, status(launch).path("exitCode").asInt());
        assertTrue(Files.readString(launch.resolveSibling("stderr.log")).contains("Cannot execute peer command"));
        assertClean(launch, status(launch));
    }

    @Test
    @Timeout(value = 15000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void existingTemporaryDirectoryIsNeverDeleted() throws Exception {
        // 执行器启动前，指定临时目录已被其他对象持有。
        Path launch = launch("existing-temporary", "touch escaped");
        Path temporary = Files.createDirectory(launch.resolveSibling("temporary"));
        Files.writeString(temporary.resolve("not-owned"), "preserved");
        Process process = start(launch);
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));

        // 启动失败不能接管或删除已有目录。
        JsonNode result = status(launch);
        assertEquals("failed", result.path("state").asText());
        assertFalse(result.path("temporaryDirectoryRemoved").asBoolean());
        assertEquals("preserved", Files.readString(temporary.resolve("not-owned")));
        assertFalse(Files.exists(launch.resolveSibling("escaped")));
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void nativeStateIsRequiredInsteadOfStaleJson() throws Exception {
        // 旧报告快照不能成为当前进程状态的依据。
        Path launch = launch("no-native-state", "true");
        Files.writeString(launch.resolveSibling("status.json"), "{\"state\":\"exited\",\"exitCode\":0}");

        // 调用方仍能区分原生状态尚未产生的情况。
        assertNull(NxnProcess.readStatus(launch));
    }

    @Test
    @Timeout(value = 10000, unit = TimeUnit.MILLISECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void truncatedNativeStateIsRejected() throws Exception {
        // 原生状态必须是完整的 NUL 分隔记录，由所有者原子替换。
        Path launch = launch("truncated", "true");
        Files.writeString(launch.resolveSibling("status.native"), "meteor-status-v1\0state\0exited");

        // 截断记录不能被解释为成功终态。
        assertThrows(IOException.class, () -> NxnProcess.readStatus(launch));
    }

    private Path launch(String id, String script) throws IOException {
        Path target = Files.createDirectory(this.directory.resolve(id));
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("processId", id);
        definition.put("runId", "native-unit");
        definition.put("groupId", "group");
        definition.put("command", List.of("/bin/sh", "-c", script));
        definition.put("workingDirectory", target.toString());
        definition.put("environment", Map.of());
        definition.put("unsetEnvironment", List.of());
        definition.put("temporaryDirectory", target.resolve("temporary").toString());
        Path launchFile = target.resolve("launch.json");
        NxnProcess.prepareLaunch(launchFile, definition);
        return launchFile;
    }

    private static void setEnvironment(Path launch, Map<String, String> environment) throws IOException {
        Map<String, Object> definition = JSON.readValue(Files.readAllBytes(launch), new TypeReference<>() { });
        definition.put("environment", environment);
        NxnProcess.prepareLaunch(launch, definition);
    }

    private Process start(Path launch) throws IOException {
        Process process = runBuilder(launch).start();
        this.wrappers.add(Map.entry(process, launch));
        return process;
    }

    private static ProcessBuilder runBuilder(Path launch) throws IOException {
        List<String> command = new ArrayList<>(NxnProcess.runCommand(launch));
        command.set(0, executable().toString());
        return new ProcessBuilder(command);
    }

    private static ProcessBuilder controlBuilder(Path launch, boolean force) throws IOException {
        List<String> command = new ArrayList<>(NxnProcess.stopCommand(launch, force));
        command.set(0, executable().toString());
        return new ProcessBuilder(command);
    }

    private static Process control(Path launch, boolean force) throws Exception {
        Process controller = controlBuilder(launch, force).start();
        assertTrue(controller.waitFor(35, TimeUnit.SECONDS), "Native cancellation exceeded its deadline");
        return controller;
    }

    private static Path executable() {
        return Path.of(System.getProperty("meteor.test.native.executable", NxnProcess.EXECUTABLE));
    }

    private static Process identityRace(Path launch, String scenario) throws Exception {
        Path fixture = executable().resolveSibling("peer-process-race");
        assertTrue(Files.isExecutable(fixture), "Native race fixture must be built in the test container");
        Process controller = new ProcessBuilder(fixture.toString(), launch.toString(), scenario).start();
        try {
            assertTrue(controller.waitFor(5, TimeUnit.SECONDS), "Native identity race exceeded its deadline");
            return controller;
        } finally {
            if (controller.isAlive()) {
                controller.destroyForcibly();
                assertTrue(controller.waitFor(5, TimeUnit.SECONDS), "Native race fixture did not exit");
            }
        }
    }

    private static JsonNode status(Path launch) throws Exception {
        return status(launch, value -> StringUtils.equals("exited", value.path("state").asText())
                || StringUtils.equals("failed", value.path("state").asText()));
    }

    private static JsonNode status(Path launch, Predicate<JsonNode> predicate) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
        while (System.nanoTime() < deadline) {
            JsonNode value = NxnProcess.readStatus(launch);
            if (value != null && predicate.test(value)) {
                return value;
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("Native peer status did not reach expected state: " + launch);
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path) && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertTrue(Files.exists(path), "Expected native control file");
    }

    private static void assertClean(Path launch, JsonNode status) {
        assertEquals("exited", status.path("state").asText());
        assertReleased(status);
        assertFalse(Files.exists(launch.resolveSibling("temporary")));
    }

    private static void assertReleased(JsonNode status) {
        assertTrue(status.path("processGroupEmpty").asBoolean());
        assertTrue(status.path("remainingPids").isArray());
        assertTrue(status.path("remainingPids").isEmpty());
        assertTrue(status.path("temporaryDirectoryRemoved").asBoolean());
    }

    private static String detached(boolean resistantRoot, boolean successfulRoot) {
        String prefix = resistantRoot ? "trap '' TERM; " : "";
        String ending = successfulRoot ? "exit 0" : "sleep 60";
        return prefix + "setsid /bin/sh -c 'trap \"\" TERM; echo ready; sleep 60' > child-ready & child=$!; "
                + "while [ ! -s child-ready ]; do sleep 0.01; done; printf '%s\\n' \"$child\"; " + ending;
    }

    private static String line(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int value; (value = input.read()) >= 0 && value != '\n';) {
            line.write(value);
        }
        return line.toString(StandardCharsets.UTF_8);
    }

    private static CompletableFuture<byte[]> readAll(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return input.readAllBytes();
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private static void writeState(Path launch, Map<String, String> state) throws IOException {
        ByteArrayOutputStream nativeState = new ByteArrayOutputStream();
        nativeState.write("meteor-status-v1\0".getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, String> entry : state.entrySet()) {
            nativeState.write((entry.getKey() + "\0" + entry.getValue() + "\0").getBytes(StandardCharsets.UTF_8));
        }
        Files.write(launch.resolveSibling("status.native"), nativeState.toByteArray());
    }
}
