package net.hasor.meteor.test.nxn;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;

/** 校时守卫的私有命令夹具，只读写当前 testcase 的临时文件。 */
public class FakeGuardMain {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] arguments) throws Exception {
        // 每个命令来自 testcase 创建的同名脚本，不调用系统服务或提权程序。
        String command = arguments[0];
        String[] values = Arrays.copyOfRange(arguments, 1, arguments.length);
        switch (command) {
            case "systemctl":
                systemctl(values);
                return;
            case "test-child":
                event("child");
                System.out.println("test output");
                System.exit(values.length == 0 ? 0 : Integer.parseInt(values[0]));
                return;
            case "getent":
                if (!Arrays.asList(values).equals(List.of("passwd", "developer"))) {
                    throw new IllegalArgumentException("Unexpected fake account lookup.");
                }
                System.out.println("developer:x:1234:5678:Example:/home/developer:/bin/bash");
                return;
            case "setpriv":
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("command", Arrays.asList(values));
                record.put("home", System.getenv("HOME"));
                record.put("user", System.getenv("USER"));
                record.put("logname", System.getenv("LOGNAME"));
                record.put("shell", System.getenv("SHELL"));
                event(record);
                return;
            case "sudo":
                event(Arrays.asList(values));
                return;
            case "chown":
                return;
            case "uname":
                boolean wsl = StringUtils.equals("true", System.getenv("GUARD_TEST_WSL"));
                System.out.println(wsl ? "6.6.87.2-microsoft-standard-WSL2" : "6.6.87-test-linux");
                return;
            case "wslpath":
                System.out.println(values[values.length - 1]);
                return;
            case "powershell.exe":
                windowsClockOwner(values);
                return;
            case "signal-child":
                signalChild(values);
                return;
            case "owner-exit-child":
                ownerExitChild();
                return;
            case "report-child":
                reportChild(values);
                return;
            case "argument-child":
                event(Map.of("arguments", Arrays.asList(values)));
                return;
            default:
                throw new IllegalArgumentException("Unexpected fake command: " + command);
        }
    }

    private static void systemctl(String[] values) throws Exception {
        // 状态和动作都记录在临时文件，失败注入与实际 systemctl 完全隔离。
        if (values.length != 2 || !StringUtils.equals("systemd-timesyncd.service", values[1])) {
            throw new IllegalArgumentException("Unexpected fake system service.");
        }
        Path statePath = Path.of(System.getenv("GUARD_TEST_STATE"));
        Map<String, Object> state = JSON.readValue(statePath.toFile(), new TypeReference<>() { });
        String action = values[0];
        event(action);
        if (StringUtils.equals("is-active", action)) {
            String active = state.get("active").toString();
            System.out.println(active);
            System.exit(StringUtils.equals("active", active) ? 0 : 3);
        }
        if (Boolean.TRUE.equals(state.get(action + "Fail"))) {
            System.exit(1);
        }
        if (Boolean.TRUE.equals(state.get(action + "NoChange"))) {
            return;
        }
        state.put("active", StringUtils.equals("start", action) ? "active" : "inactive");
        JSON.writeValue(statePath.toFile(), state);
    }

    private static void signalChild(String[] values) throws Exception {
        // JVM 收到信号后停在清理门闩，守卫只能在该门闩释放后恢复服务。
        Path release = Path.of(values[0]);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                System.out.println("CLEANUP");
                System.out.flush();
                try (var input = Files.newInputStream(release)) {
                    input.readAllBytes();
                }
                event("child-exited");
            } catch (Exception failure) {
                failure.printStackTrace(System.err);
            }
        }, "fake-guard-child-cleanup"));
        Path childPid = Path.of(System.getenv("GUARD_TEST_CHILD_PID"));
        Files.writeString(childPid, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
        System.out.println("READY");
        System.out.flush();
        new CountDownLatch(1).await();
    }

    private static void windowsClockOwner(String[] values) throws Exception {
        // Windows 目录直接来自当前仓库，不能重新查询外部临时目录。
        if (Arrays.asList(values).contains("-Command")) {
            throw new IllegalArgumentException("Windows temporary directory lookup is not allowed.");
        }
        int stateIndex = Arrays.asList(values).indexOf("-StateDirectory");
        if (stateIndex < 0 || stateIndex + 1 >= values.length) {
            throw new IllegalArgumentException("Windows clock owner must receive its state directory.");
        }
        Path directory = Path.of(values[stateIndex + 1]);
        Map<String, Object> state = JSON.readValue(Path.of(System.getenv("GUARD_TEST_STATE")).toFile(),
                new TypeReference<>() { });
        if (Boolean.TRUE.equals(state.get("ownerPathFail"))) {
            System.err.println("fake Windows state directory access failure");
            System.exit(7);
        }
        System.out.println("fake Windows clock owner started");
        if (Boolean.TRUE.equals(state.get("ownerStartFail"))) {
            clockLog(directory, "Windows elevation denied");
            writeMarker(directory, "unchanged", Map.of("modified", false));
            Files.writeString(directory.resolve("failed"), "Windows elevation denied", StandardCharsets.UTF_8);
            writeMarker(directory, "finished", Map.of("exitCode", 1));
            System.exit(1);
        }

        // 所有者持续运行到守卫释放，记录暂停与恢复顺序。
        boolean alreadyPaused = Boolean.TRUE.equals(state.get("ownerAlreadyPaused"));
        if (!alreadyPaused) {
            writeMarker(directory, "changed", Map.of("originalPid", 17, "originalStartTicks", "12345"));
        }
        event(alreadyPaused ? "chrony-already-paused" : "chrony-paused");
        clockLog(directory, alreadyPaused ? "chronyd was already paused" : "chronyd paused");
        if (Boolean.TRUE.equals(state.get("ownerReadyAndFailed"))) {
            clockLog(directory, "Windows worker failed after creating ready marker");
            Files.writeString(directory.resolve("failed"), "fake startup failure", StandardCharsets.UTF_8);
            writeMarker(directory, "ready", Map.of("originalPid", 17, "modified", true));
            writeMarker(directory, "finished", Map.of("exitCode", 1));
            System.exit(1);
        }
        writeMarker(directory, "ready", Map.of("originalPid", 17, "modified", !alreadyPaused));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(directory.resolve("release"))) {
            String childPid = System.getenv("GUARD_TEST_CHILD_PID");
            boolean exitAfterChild = Boolean.TRUE.equals(state.get("ownerExitAfterChildReady"));
            if (exitAfterChild && childPid != null && Files.exists(Path.of(childPid))) {
                event("chrony-owner-exited");
                clockLog(directory, "Windows clock owner exited unexpectedly");
                Files.writeString(directory.resolve("failed"), "fake owner exit", StandardCharsets.UTF_8);
                writeMarker(directory, "finished", Map.of("exitCode", 1));
                System.exit(1);
            }
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("Guard did not release its Windows clock owner.");
            }
            Thread.sleep(10);
        }
        if (Boolean.TRUE.equals(state.get("ownerRestoreFail"))) {
            event("chrony-restore-failed");
            clockLog(directory, "Failed to restore chronyd: fake Windows restore failure");
            String failure = "fake Windows restore failure\nRemote transcript: denied → original PID=17\n";
            Files.writeString(directory.resolve("failed"), failure, StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("failure-message.txt"), "fake Windows restore failure\n",
                    StandardCharsets.UTF_8);
            System.err.println("IllegalStateException: fake Windows restore failure");
            System.err.println("\tat fake.restore(ClockOwner.java:17)");
            writeMarker(directory, "finished", Map.of("exitCode", 1));
            System.exit(1);
        }
        event(alreadyPaused ? "chrony-left-paused" : "chrony-restored");
        clockLog(directory, alreadyPaused ? "chronyd remains paused" : "chronyd restored");
        if (!Boolean.TRUE.equals(state.get("ownerOmitRestored"))) {
            String originalState = alreadyPaused ? "T" : "S";
            writeMarker(directory, "restored", Map.of("originalPid", 17, "originalState", originalState));
        }
        if (!Boolean.TRUE.equals(state.get("ownerOmitFinished"))) {
            Object workerExitCode = state.getOrDefault("ownerFinishedExitCode", 0);
            writeMarker(directory, "finished", Map.of("exitCode", workerExitCode));
        }
        int processExitCode = ((Number) state.getOrDefault("ownerExitCode", 0)).intValue();
        System.exit(processExitCode);
    }

    private static void writeMarker(Path directory, String name, Object value) throws Exception {
        Files.writeString(directory.resolve(name), JSON.writeValueAsString(value) + "\n", StandardCharsets.UTF_8);
    }

    private static void clockLog(Path directory, String text) throws Exception {
        Files.writeString(directory.resolve("chrony.log"), text + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void ownerExitChild() throws Exception {
        // 所有者退出后业务命令仍能继续完成，避免用固定休眠制造时序。
        event("child");
        Path childPid = Path.of(System.getenv("GUARD_TEST_CHILD_PID"));
        Files.writeString(childPid, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
        Path events = Path.of(System.getenv("GUARD_TEST_EVENTS"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!Files.readString(events).contains("chrony-owner-exited")) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Windows owner did not exit.");
            }
            Thread.sleep(10);
        }
        event("child-after-owner-exit");
        System.out.println("child completed after owner exit");
    }

    private static void reportChild(String[] values) throws Exception {
        // 模拟 Gradle 已写好的 RPC 报告和归档指针，不访问真实仓库报告。
        Path state = Path.of(System.getenv("METEOR_TEST_SCRIPT_STATE_DIR"));
        Path artifacts = Path.of(System.getenv("GUARD_TEST_ARTIFACTS"));
        Files.writeString(state.resolve("artifacts"), artifacts + "\n", StandardCharsets.UTF_8);
        Files.writeString(artifacts.resolve("report.md"), "结果：通过\n\nRPC test results.\n", StandardCharsets.UTF_8);
        event("child");
        System.exit(values.length == 0 ? 0 : Integer.parseInt(values[0]));
    }

    private static void event(Object value) throws Exception {
        Path events = Path.of(System.getenv("GUARD_TEST_EVENTS"));
        Files.writeString(events, JSON.writeValueAsString(value) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
