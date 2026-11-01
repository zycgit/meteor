package net.hasor.meteor.test.nxn;
import java.io.BufferedReader;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 只在 Linux 执行容器中验证 Shell 守卫；PATH 仅开放私有夹具命令。 */
public class NxnClockGuardTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    @TempDir
    public Path temporary;
    private Path directory;
    private Path state;
    private Path events;
    private Path child;
    private Path privateGuard;
    private Path privateBuild;
    private Map<String, String> environment;

    @BeforeEach
    public void prepareCommands() throws Exception {
        // 采用封闭 PATH，任何漏建的 fake 命令都会失败，不能访问宿主 systemctl 或 sudo。
        this.directory = this.temporary;
        Path bin = Files.createDirectory(this.directory.resolve("bin"));
        Path clockTools = Files.createDirectories(this.directory.resolve("private/repo/meteor-test/tools/clock"));
        this.privateBuild = Files.createDirectories(this.directory.resolve("private/repo/meteor-test/build"));
        this.privateGuard = Files.copy(guard(), clockTools.resolve("with-clock-paused.sh"));
        Files.copy(guard().getParent().resolve("wsl-clock-owner.ps1"), clockTools.resolve("wsl-clock-owner.ps1"));
        this.state = this.directory.resolve("state.json");
        this.events = this.directory.resolve("events.jsonl");
        this.environment = new LinkedHashMap<>(System.getenv());
        this.environment.put("PATH", bin.toString());
        this.environment.remove("SUDO_USER");
        this.environment.put("TMPDIR", this.directory.toString());
        this.environment.put("METEOR_TEST_SKIP_ERROR", "false");
        this.environment.put("GUARD_TEST_STATE", this.state.toString());
        this.environment.put("GUARD_TEST_EVENTS", this.events.toString());
        configure("active", Map.of());
        command("systemctl");
        command("uname");
        command("powershell.exe");
        command("wslpath");
        // 真实工具只处理当前夹具目录；系统服务与 Windows 命令始终使用上面的 fake。
        List<String> fileCommands = List.of("mktemp", "cp", "touch", "sleep", "tail", "rm", "dirname", "cat",
                "bash", "sed", "mkdir");
        for (String name : fileCommands) {
            Files.createSymbolicLink(bin.resolve(name), Path.of("/usr/bin").resolve(name));
        }
        this.child = command("test-child");
    }

    @Test
    public void activeServiceIsRestoredAfterSuccess() throws Exception {
        // 标准输出归测试命令所有，守卫的服务动作另行记录。
        Map<String, Object> result = runGuard(this.child.toString());

        // 成功后恢复启动前的 active 状态。
        assertEquals(0, result.get("exitCode"));
        assertEquals("test output\n", result.get("stdout"));
        assertEquals(serviceLifecycle("child"), recorded());
        assertEquals("active", currentState());
    }

    @Test
    public void failedCommandKeepsItsExitCodeAfterRestoration() throws Exception {
        // 测试命令失败也必须完整执行服务恢复。
        Map<String, Object> result = runGuard(this.child.toString(), "42");

        // 原始退出码与服务状态同时保留。
        assertEquals(42, result.get("exitCode"));
        assertEquals(serviceLifecycle("child"), recorded());
        assertEquals("active", currentState());
    }

    @Test
    public void inactiveServiceIsLeftInactive() throws Exception {
        // 启动前已经停止的服务不归守卫重新启动。
        configure("inactive", Map.of());
        Map<String, Object> result = runGuard(this.child.toString());

        // 守卫只查询状态并运行命令。
        assertEquals(0, result.get("exitCode"));
        assertEquals(List.of("is-active", "child", "is-active"), recorded());
        assertEquals("inactive", currentState());
    }

    @Test
    public void stopFailurePreventsCommandStartup() throws Exception {
        // 停止失败仍尝试恢复，避免部分状态改变被忽略。
        configure("active", Map.of("stopFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 未启动任何测试命令。
        assertNotEquals(0, result.get("exitCode"));
        assertEquals(List.of("is-active", "stop", "start", "is-active"), recorded());
        assertEquals("", result.get("stdout"));
    }

    @Test
    public void restoreFailureFailsSuccessfulCommand() throws Exception {
        // 子命令成功不能覆盖服务恢复失败。
        configure("active", Map.of("startFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 保留可诊断的恢复错误与实际停止状态。
        assertNotEquals(0, result.get("exitCode"));
        assertTrue(result.get("stderr").toString().contains("Failed to restore"));
        assertEquals("inactive", currentState());
    }

    @Test
    public void successfulStopMustProduceInactiveState() throws Exception {
        // systemctl 返回零仍需检查最终状态。
        configure("active", Map.of("stopNoChange", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 服务未停止时不运行测试命令。
        assertNotEquals(0, result.get("exitCode"));
        assertFalse(recorded().contains("child"));
        assertEquals("active", currentState());
    }

    @Test
    public void successfulRestoreMustProduceActiveState() throws Exception {
        // start 的退出码不代替恢复后的状态检查。
        configure("active", Map.of("startNoChange", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 未恢复 active 时整轮守卫失败。
        assertNotEquals(0, result.get("exitCode"));
        assertTrue(result.get("stderr").toString().contains("Failed to restore"));
        assertEquals("inactive", currentState());
    }

    @Test
    public void unknownServiceStatePreventsCommandStartup() throws Exception {
        // failed 或缺失服务不能被当成原先 inactive。
        configure("failed", Map.of());
        Map<String, Object> result = runGuard(this.child.toString());

        // 状态不明确时在任何变更前退出。
        assertNotEquals(0, result.get("exitCode"));
        assertEquals(List.of("is-active"), recorded());
    }

    @Test
    public void sudoCallerUsesNativeHomeAndGroups() throws Exception {
        // fake 身份查询与 setpriv 只记录参数，不改变测试用户身份。
        this.environment.put("SUDO_USER", "developer");
        this.environment.put("SUDO_UID", "1234");
        this.environment.put("SUDO_GID", "5678");
        this.environment.put("HOME", "/root");
        command("getent");
        command("setpriv");
        command("chown");
        Files.createSymbolicLink(this.directory.resolve("bin/env"), Path.of("/usr/bin/env"));
        Map<String, Object> result = runGuard("run_as_caller", this.child.toString());

        // 服务所有者保持不变，子命令使用原调用者的 HOME 和附加组。
        assertEquals(0, result.get("exitCode"));
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("command", List.of("--reuid=1234", "--regid=5678", "--init-groups", "--",
                this.child.toString()));
        expected.put("home", "/home/developer");
        expected.put("user", "developer");
        expected.put("logname", "developer");
        expected.put("shell", "/bin/bash");
        assertEquals(expected, recorded().get(3));
    }

    @Test
    public void rootCallerNeedsNoAdditionalPrivilegeWrapper() throws Exception {
        // 原调用者已是 root 时直接运行命令。
        this.environment.put("SUDO_USER", "root");
        Map<String, Object> result = runGuard("run_as_caller", this.child.toString());

        // 封闭 PATH 中没有 setpriv，仍能完成完整生命周期。
        assertEquals(0, result.get("exitCode"));
        assertEquals(serviceLifecycle("child"), recorded());
    }

    @Test
    public void termWaitsForChildCleanupBeforeRestoration() throws Exception {
        // TERM 清理完成前不能恢复时钟服务。
        assertSignalCleanup("TERM", 143);
    }

    @Test
    public void intWaitsForChildCleanupBeforeRestoration() throws Exception {
        // INT 清理完成前不能恢复时钟服务。
        assertSignalCleanup("INT", 130);
    }

    @Test
    public void normalLinuxDoesNotStartWindowsClockOwner() throws Exception {
        // Linux 主机只管理自己的 timesyncd，不启动 Windows 管理进程。
        Map<String, Object> result = runGuard(this.child.toString());

        // 路径中存在 PowerShell 夹具也不会触发它。
        assertEquals(0, result.get("exitCode"));
        assertFalse(recorded().contains("chrony-paused"));
        assertFalse(recorded().contains("windows-temp"));
    }

    @Test
    public void wslClockIsRestoredAfterSuccessfulCommand() throws Exception {
        // 通过私有 Windows 所有者暂停 WSL 校时，再运行测试命令。
        enableWsl(Map.of());
        Map<String, Object> result = runGuard(this.child.toString());

        // 恢复发生在子命令完成之后，暂停和恢复日志都到达控制台。
        assertEquals(0, result.get("exitCode"));
        assertChronyLifecycle("chrony-paused", "child", "chrony-restored");
        String console = result.get("stdout").toString() + result.get("stderr");
        assertTrue(console.contains("chronyd paused"));
        assertTrue(console.contains("chronyd restored"));
        assertEquals("active", currentState());
    }

    @Test
    public void previouslyPausedWslClockRemainsPaused() throws Exception {
        // 初始已经暂停的 chronyd 不属于本轮需要恢复运行的进程。
        enableWsl(Map.of("ownerAlreadyPaused", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 所有者只恢复原状态，不发起额外继续操作。
        assertEquals(0, result.get("exitCode"));
        assertChronyLifecycle("chrony-already-paused", "child", "chrony-left-paused");
        assertFalse(recorded().contains("chrony-restored"));
    }

    @Test
    public void windowsOwnerFailurePreventsCommandStartup() throws Exception {
        // Windows 权限拒绝必须在业务命令和系统服务变更前暴露。
        enableWsl(Map.of("ownerStartFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 错误完整输出，既不启动测试，也不改变 timesyncd 初态。
        assertNotEquals(0, result.get("exitCode"));
        String console = result.get("stdout").toString() + result.get("stderr");
        assertTrue(console.contains("Windows elevation denied"));
        assertFalse(recorded().contains("child"));
        assertFalse(recorded().contains("stop"));
        assertEquals("active", currentState());
    }

    @Test
    public void failedWindowsOwnerCannotAuthorizeStartupWithReadyMarker() throws Exception {
        // 保留 ready 的失败所有者不能被当作仍持有校时暂停能力。
        enableWsl(Map.of("ownerReadyAndFailed", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 失败状态优先于 ready，业务命令与 timesyncd 停止动作都不执行。
        assertNotEquals(0, result.get("exitCode"));
        assertFalse(recorded().contains("child"));
        assertFalse(recorded().contains("stop"));
        assertEquals("active", currentState());
    }

    @Test
    public void failedLinuxPauseReleasesWindowsOwner() throws Exception {
        // 已暂停 WSL 校时后，timesyncd 停止失败同样进入恢复链路。
        enableWsl(Map.of("stopFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 测试未启动，两端所有者都恢复其负责的初态。
        assertNotEquals(0, result.get("exitCode"));
        assertChronyLifecycle("chrony-paused", "stop", "chrony-restored");
        assertFalse(recorded().contains("child"));
        assertEquals("active", currentState());
    }

    @Test
    public void failedWslCommandKeepsItsExitCodeAfterRestoration() throws Exception {
        // 两条校时链路都恢复后仍返回测试命令自身的失败码。
        enableWsl(Map.of());
        Map<String, Object> result = runGuard(this.child.toString(), "42");

        // 守卫不能用恢复成功覆盖业务失败。
        assertEquals(42, result.get("exitCode"));
        assertChronyLifecycle("chrony-paused", "child", "chrony-restored");
        assertEquals("active", currentState());
    }

    @Test
    public void failedWindowsRestorationFailsSuccessfulCommand() throws Exception {
        // 测试通过而 chronyd 恢复失败时，整轮仍需返回失败。
        enableWsl(Map.of("ownerRestoreFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 原始 Windows 诊断必须到达控制台。
        assertNotEquals(0, result.get("exitCode"));
        String console = result.get("stdout").toString() + result.get("stderr");
        assertTrue(console.contains("fake Windows restore failure"));
        assertChronyLifecycle("chrony-paused", "child", "chrony-restore-failed");
        assertEquals("active", currentState());

        // 未产生 RPC 归档时，原始失败和 Windows 进程退出码仍保存在私有模块 build 下。
        List<Path> diagnostics = diagnostics();
        assertEquals(1, diagnostics.size());
        Path clockState = diagnostics.get(0).resolve("clock-state");
        String failure = Files.readString(clockState.resolve("failed"));
        assertTrue(failure.contains("Remote transcript: denied → original PID=17"));
        assertEquals("1\n", Files.readString(clockState.resolve("owner-exit-code.txt")));
        assertTrue(Files.readString(clockState.resolve("owner.stderr.log")).contains("at fake.restore"));
    }

    @Test
    public void successfulWslRestorationArchivesItsCompleteClockState() throws Exception {
        // RPC 命令生成归档后，Windows 所有者成功恢复原进程状态。
        enableWsl(Map.of());
        Path artifacts = prepareReport();
        Map<String, Object> result = runGuard(command("report-child").toString());

        // 成功证据和原始输出一并进入本轮归档，不只保留通过摘要。
        assertEquals(0, result.get("exitCode"));
        Path clockState = artifacts.resolve("clock-state");
        assertTrue(Files.isRegularFile(clockState.resolve("ready")));
        assertTrue(Files.isRegularFile(clockState.resolve("changed")));
        assertTrue(Files.isRegularFile(clockState.resolve("release")));
        assertTrue(Files.isRegularFile(clockState.resolve("restored")));
        assertEquals(0, JSON.readTree(clockState.resolve("finished").toFile()).path("exitCode").asInt());
        assertEquals("0\n", Files.readString(clockState.resolve("owner-exit-code.txt")));
        assertTrue(Files.readString(clockState.resolve("owner.stdout.log")).contains("fake Windows clock owner"));
        assertTrue(Files.readString(clockState.resolve("chrony.log")).contains("chronyd restored"));

        // Java 夹具保留隔离参数的标准启动提示，除此之外不应出现 stderr。
        String expectedError = "";
        String javaToolOptions = this.environment.get("JAVA_TOOL_OPTIONS");
        if (javaToolOptions != null) {
            expectedError = "Picked up JAVA_TOOL_OPTIONS: " + javaToolOptions + "\n";
        }
        assertEquals(expectedError, Files.readString(clockState.resolve("owner.stderr.log")));
        assertTrue(Files.isRegularFile(diagnostics().get(0).resolve("clock-state/restored")));

        // 对外报告相对 meteor-test 目录，不泄露运行机器的仓库位置。
        String report = Files.readString(artifacts.resolve("report.md"));
        assertTrue(report.contains("校时诊断：[clock-state](<build/artifacts/clock-state>)。"));
        assertFalse(report.contains(this.privateBuild.getParent().toString()));
    }

    @Test
    public void failedWslRestorationArchivesItsRawFailureAndProcessOutput() throws Exception {
        // 原始恢复异常同时出现在 marker、stderr 和远程日志中。
        enableWsl(Map.of("ownerRestoreFail", true));
        Path artifacts = prepareReport();
        Map<String, Object> result = runGuard(command("report-child").toString());

        // 归档保存完整多行异常，摘要列出具体原因，控制台也显示原始堆栈。
        assertNotEquals(0, result.get("exitCode"));
        Path clockState = artifacts.resolve("clock-state");
        String expectedFailure = "fake Windows restore failure\nRemote transcript: denied → original PID=17\n";
        assertEquals(expectedFailure, Files.readString(clockState.resolve("failed")));
        String rawError = Files.readString(clockState.resolve("owner.stderr.log"));
        assertTrue(rawError.contains("IllegalStateException: fake Windows restore failure"));
        assertTrue(rawError.contains("\tat fake.restore(ClockOwner.java:17)"));
        assertTrue(result.get("stderr").toString().contains("at fake.restore(ClockOwner.java:17)"));
        String errors = Files.readString(artifacts.resolve("script-errors.tsv"));
        assertTrue(errors.contains("wsl.restore\t"));
        assertTrue(errors.contains("fake Windows restore failure"));
        assertEquals("1\n", Files.readString(clockState.resolve("owner-exit-code.txt")));
    }

    @Test
    public void restoredMarkerCannotHideWindowsProcessFailure() throws Exception {
        // 远程 worker 恢复成功，但外层 Windows 进程以独立非零状态退出。
        enableWsl(Map.of("ownerExitCode", 23));
        Path artifacts = prepareReport();
        Map<String, Object> result = runGuard(command("report-child").toString());

        // 已存在的 restored 和成功 finished 不能掩盖真实进程退出码。
        assertNotEquals(0, result.get("exitCode"));
        Path clockState = artifacts.resolve("clock-state");
        assertTrue(Files.isRegularFile(clockState.resolve("restored")));
        assertEquals(0, JSON.readTree(clockState.resolve("finished").toFile()).path("exitCode").asInt());
        assertEquals("23\n", Files.readString(clockState.resolve("owner-exit-code.txt")));
        String errors = Files.readString(artifacts.resolve("script-errors.tsv"));
        assertTrue(errors.contains("exit=23"), errors);
    }

    @Test
    public void successfulWindowsProcessCannotHideFailedWorkerMarker() throws Exception {
        // Windows 外层进程返回零，但远程 worker 已明确记录失败。
        enableWsl(Map.of("ownerFinishedExitCode", 9));
        Path artifacts = prepareReport();
        Map<String, Object> result = runGuard(command("report-child").toString());

        // worker 的非零结果独立参与最终恢复校验。
        assertNotEquals(0, result.get("exitCode"));
        Path clockState = artifacts.resolve("clock-state");
        assertEquals("0\n", Files.readString(clockState.resolve("owner-exit-code.txt")));
        assertEquals(9, JSON.readTree(clockState.resolve("finished").toFile()).path("exitCode").asInt());
        String errors = Files.readString(artifacts.resolve("script-errors.tsv"));
        assertTrue(errors.contains("finished exit=9"), errors);
    }

    @Test
    public void missingRestoredMarkerHasItsOwnArchivedFailure() throws Exception {
        // Windows 进程退出且 finished 为零，但没有恢复状态的确认文件。
        enableWsl(Map.of("ownerOmitRestored", true));
        Path artifacts = prepareReport();
        Map<String, Object> result = runGuard(command("report-child").toString());

        // 具体缺失原因与现存 marker 一同归档，不能只报告笼统恢复失败。
        assertNotEquals(0, result.get("exitCode"));
        Path clockState = artifacts.resolve("clock-state");
        assertFalse(Files.exists(clockState.resolve("restored")));
        assertTrue(Files.isRegularFile(clockState.resolve("finished")));
        assertEquals("0\n", Files.readString(clockState.resolve("owner-exit-code.txt")));
        String errors = Files.readString(artifacts.resolve("script-errors.tsv"));
        assertTrue(errors.contains("missing restored marker"), errors);
        assertFalse(errors.contains("missing finished marker"), errors);
    }

    @Test
    public void missingFinishedMarkerHasItsOwnArchivedFailure() throws Exception {
        // Windows 进程正常退出并写出 restored，但缺少 worker 完成标记。
        enableWsl(Map.of("ownerOmitFinished", true));
        Path artifacts = prepareReport();
        Map<String, Object> result = runGuard(command("report-child").toString());

        // worker 完成缺失与恢复确认缺失分别报告，原始状态仍完整保留。
        assertNotEquals(0, result.get("exitCode"));
        Path clockState = artifacts.resolve("clock-state");
        assertTrue(Files.isRegularFile(clockState.resolve("restored")));
        assertFalse(Files.exists(clockState.resolve("finished")));
        assertEquals("0\n", Files.readString(clockState.resolve("owner-exit-code.txt")));
        String errors = Files.readString(artifacts.resolve("script-errors.tsv"));
        assertTrue(errors.contains("missing finished marker"), errors);
        assertFalse(errors.contains("missing restored marker"), errors);
    }

    @Test
    public void defaultModeContinuesAfterLinuxPauseFailure() throws Exception {
        // 默认策略保留暂停错误，但继续执行测试命令。
        this.environment.remove("METEOR_TEST_SKIP_ERROR");
        configure("active", Map.of("stopFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 最终失败与完整恢复同时保留，不把继续执行当成成功。
        assertNotEquals(0, result.get("exitCode"));
        assertTrue(recorded().contains("child"));
        assertEquals("active", currentState());
        assertTrue(result.get("stderr").toString().contains("timesyncd.pause"));
        assertTrue(result.get("stderr").toString().contains("整轮结果：失败"));
        List<Path> diagnostics = diagnostics();
        assertEquals(1, diagnostics.size());
        String errors = Files.readString(diagnostics.get(0).resolve("errors.tsv"));
        assertTrue(errors.contains("timesyncd.pause\t"));
    }

    @Test
    public void defaultModeContinuesWhenInitialServiceStateIsUnknown() throws Exception {
        // 无法确定初态时不尝试启停服务，仍执行测试。
        this.environment.remove("METEOR_TEST_SKIP_ERROR");
        configure("failed", Map.of());
        Map<String, Object> result = runGuard(this.child.toString());

        // 不伪造服务恢复或整轮通过。
        assertNotEquals(0, result.get("exitCode"));
        assertEquals(List.of("is-active", "child"), recorded());
        assertEquals("failed", currentState());
    }

    @Test
    public void defaultModeContinuesAfterWindowsElevationFailure() throws Exception {
        // Windows 拒绝提权不会吞日志或中止剩余测试。
        this.environment.remove("METEOR_TEST_SKIP_ERROR");
        enableWsl(Map.of("ownerStartFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // Linux 暂停和恢复独立完成，Windows 错误仍让最终结果失败。
        assertNotEquals(0, result.get("exitCode"));
        assertTrue(recorded().contains("child"));
        assertEquals("active", currentState());
        assertTrue(result.get("stderr").toString().contains("Windows elevation denied"));
    }

    @Test
    public void inaccessibleWindowsStateDoesNotPauseClockOrLoseTestExecution() throws Exception {
        // Windows 无法访问仓库内的状态目录时，不执行任何远程校时变更。
        this.environment.remove("METEOR_TEST_SKIP_ERROR");
        enableWsl(Map.of("ownerPathFail", true));
        Map<String, Object> result = runGuard(this.child.toString());

        // 原始路径错误保留在控制台和仓库诊断中，测试按继续策略运行。
        assertNotEquals(0, result.get("exitCode"));
        assertTrue(recorded().contains("child"));
        assertFalse(recorded().contains("chrony-paused"));
        assertTrue(result.get("stderr").toString().contains("fake Windows state directory access failure"));
        Path clockState = diagnostics().get(0).resolve("clock-state");
        assertFalse(Files.exists(clockState.resolve("changed")));
        assertFalse(Files.exists(clockState.resolve("ready")));
        String rawError = Files.readString(clockState.resolve("owner.stderr.log"));
        assertTrue(rawError.contains("fake Windows state directory access failure"));
        assertEquals("active", currentState());
    }

    @Test
    public void defaultModeAllowsChildToFinishAfterWindowsOwnerExits() throws Exception {
        // 测试期间校时所有者退出时，默认策略仍允许命令走完。
        this.environment.remove("METEOR_TEST_SKIP_ERROR");
        enableWsl(Map.of("ownerExitAfterChildReady", true));
        this.environment.put("GUARD_TEST_CHILD_PID", this.directory.resolve("child.pid").toString());
        Map<String, Object> result = runGuard(command("owner-exit-child").toString());

        // 完成事件发生在所有者退出之后，环境错误单独汇总。
        assertNotEquals(0, result.get("exitCode"));
        assertTrue(recorded().contains("child-after-owner-exit"));
        assertEquals("active", currentState());
        assertTrue(result.get("stdout").toString().contains("child completed after owner exit"));
    }

    @Test
    public void continueModePreservesFailedChildExitCode() throws Exception {
        // 暂停失败后继续运行的测试仍以自身退出码为主。
        this.environment.put("METEOR_TEST_SKIP_ERROR", "true");
        configure("active", Map.of("stopFail", true));
        Map<String, Object> result = runGuard(this.child.toString(), "42");

        // 恢复或脚本汇总不能覆盖原始测试失败。
        assertEquals(42, result.get("exitCode"));
        assertTrue(recorded().contains("child"));
        assertEquals("active", currentState());
    }

    @Test
    public void commandLineContinueOptionOverridesFailFastEnvironment() throws Exception {
        // 命令行 true 覆盖环境中的 false，同时保留参数原样交给实际命令。
        configure("active", Map.of("stopFail", true));
        Path child = command("argument-child");
        Map<String, Object> result = runGuard(child.toString(), "--skipError", "true");

        // 只改变执行策略，整轮仍失败。
        assertNotEquals(0, result.get("exitCode"));
        assertTrue(recorded().contains(Map.of("arguments", List.of("--skipError", "true"))));
    }

    @Test
    public void commandLineFailFastOptionOverridesContinueEnvironment() throws Exception {
        // 命令行 false 覆盖环境中的 true。
        this.environment.put("METEOR_TEST_SKIP_ERROR", "true");
        configure("active", Map.of("stopFail", true));
        Map<String, Object> result = runGuard(command("argument-child").toString(), "--skipError=false");

        // 失败阶段后不启动测试命令，恢复仍照常执行。
        assertNotEquals(0, result.get("exitCode"));
        assertEquals(List.of("is-active", "stop", "start", "is-active"), recorded());
    }

    @Test
    public void lockConflictContinuesWithoutTouchingOwnedClockState() throws Exception {
        // 模拟入口检测到另一轮已持有锁，不访问其服务和 Windows 所有者。
        this.environment.remove("METEOR_TEST_SKIP_ERROR");
        String body = "source \"$1\"; shift; clock_control_error='Another run owns clocks'; with_clock_paused \"$@\"";
        Map<String, Object> result = run(List.of("/bin/bash", "-c", body, "guard", this.privateGuard.toString(),
                this.child.toString()));

        // 当前轮执行测试并保留锁错误，但不改变其它轮的校时控制状态。
        assertNotEquals(0, result.get("exitCode"));
        assertEquals(List.of("child"), recorded());
        assertEquals("active", currentState());
    }

    @Test
    public void restoreFailureUpdatesCompletedReportAndLatestCopy() throws Exception {
        // 私有仓库中的测试先生成通过报告，随后注入恢复失败。
        Path artifacts = prepareReport();
        configure("active", Map.of("startFail", true));
        Map<String, Object> result = runGuard(command("report-child").toString());

        // 恢复错误进入脚本阶段，归档和目录入口都呈现最终失败。
        assertNotEquals(0, result.get("exitCode"));
        String report = Files.readString(artifacts.resolve("report.md"));
        assertTrue(report.contains("结果：失败（含脚本异常）"));
        assertTrue(report.contains("整轮结果：失败"));
        assertTrue(Files.readString(artifacts.resolve("script-errors.tsv")).contains("timesyncd.restore\t"));
        assertEquals("1\n", Files.readString(artifacts.resolve("script-exit-code.txt")));
        Path latest = this.privateBuild.getParent().resolve("report.md");
        assertEquals(report, Files.readString(latest));
    }

    @Test
    public void archivedScriptErrorPreservesChildExitCode() throws Exception {
        // 继续策略下的 RPC 命令失败与脚本失败分别保留。
        Path artifacts = prepareReport();
        this.environment.remove("METEOR_TEST_SKIP_ERROR");
        configure("active", Map.of("stopFail", true));
        Map<String, Object> result = runGuard(command("report-child").toString(), "42");

        // 独立的最终退出码文件保存原始业务失败，报告仍列出脚本问题。
        assertEquals(42, result.get("exitCode"));
        assertEquals("42\n", Files.readString(artifacts.resolve("script-exit-code.txt")));
        assertTrue(Files.readString(artifacts.resolve("script-errors.tsv")).contains("timesyncd.pause\t"));
    }

    @Test
    public void successfulRestorationPublishesPassingScriptSummary() throws Exception {
        // RPC 报告完成后，脚本继续附上成功的恢复结果。
        Path artifacts = prepareReport();
        Map<String, Object> result = runGuard(command("report-child").toString());

        // 通过状态和零错误归档完整保留，不制造环境失败。
        assertEquals(0, result.get("exitCode"));
        String report = Files.readString(artifacts.resolve("report.md"));
        assertTrue(report.startsWith("结果：通过"));
        assertTrue(report.contains("整轮结果：通过"));
        assertEquals("", Files.readString(artifacts.resolve("script-errors.tsv")));
        assertEquals("0\n", Files.readString(artifacts.resolve("script-exit-code.txt")));
        Path latest = this.privateBuild.getParent().resolve("report.md");
        assertEquals(report, Files.readString(latest));
    }

    @Test
    public void successfulCommandWithoutArchiveKeepsRepositoryLocalEvidence() throws Exception {
        // 无归档指针的普通命令只显示控制台汇总。
        Map<String, Object> result = runGuard(this.child.toString());

        // 成功结果也保留可复核的本轮证据，所有文件均位于 meteor-test/build 下。
        assertEquals(0, result.get("exitCode"));
        assertTrue(result.get("stderr").toString().contains("整轮结果：通过"));
        List<Path> diagnostics = diagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals("0\n", Files.readString(diagnostics.get(0).resolve("script-exit-code.txt")));
        assertEquals("", Files.readString(diagnostics.get(0).resolve("errors.tsv")));
        assertFalse(Files.exists(this.privateBuild.getParent().getParent().resolve("build")));
    }

    @Test
    public void failedCommandWithoutArchiveReplacesOldPassingReportAndKeepsDiagnostics() throws Exception {
        // 模拟 Gradle 在产生归档之前失败，目录入口还保留上一轮通过报告。
        enableWsl(Map.of());
        Path latest = this.privateBuild.getParent().resolve("report.md");
        Files.writeString(latest, "结果：通过\n\nPrevious RPC results.\n", StandardCharsets.UTF_8);
        Map<String, Object> result = runGuard(this.child.toString(), "42");

        // 本轮仅报告脚本阶段，不能继续显示旧的 RPC 通过结果。
        assertEquals(42, result.get("exitCode"));
        String report = Files.readString(latest);
        assertTrue(report.startsWith("结果：失败"));
        assertTrue(report.contains("未生成 RPC 用例报告"));
        assertTrue(report.contains("测试命令退出码：42"));
        assertFalse(report.contains("Previous RPC results"));
        List<Path> diagnostics = diagnostics();
        assertEquals(1, diagnostics.size());
        assertEquals("42\n", Files.readString(diagnostics.get(0).resolve("script-exit-code.txt")));

        // 未生成 RPC 归档时，诊断仍指向 meteor-test/build 下的本轮状态目录。
        Path reportDirectory = this.privateBuild.getParent();
        String diagnosticPath = reportDirectory.relativize(diagnostics.get(0).resolve("clock-state")).toString();
        assertTrue(report.contains("校时诊断：[clock-state](<" + diagnosticPath + ">)。"));
        assertFalse(report.contains(reportDirectory.toString()));
    }

    @Test
    public void termKeepsWslClockPausedUntilChildCleanupFinishes() throws Exception {
        // TERM 不能提前释放 WSL 的暂停所有者。
        enableWsl(Map.of());
        assertSignalCleanup("TERM", 143);
    }

    @Test
    public void intKeepsWslClockPausedUntilChildCleanupFinishes() throws Exception {
        // INT 也必须等待子进程自己的清理完成。
        enableWsl(Map.of());
        assertSignalCleanup("INT", 130);
    }

    @Test
    public void unexpectedWindowsOwnerExitWaitsForCancelledChildCleanup() throws Exception {
        // 子进程启动后让 Windows 所有者退出，守卫必须取消测试并等其清理。
        enableWsl(Map.of("ownerExitAfterChildReady", true));
        Path release = createCleanupRelease();
        this.environment.put("GUARD_TEST_CHILD_PID", this.directory.resolve("signal-child.pid").toString());
        Path signalChild = command("signal-child");
        ProcessBuilder builder = new ProcessBuilder(arguments(signalChild.toString(), release.toString()));
        builder.environment().clear();
        builder.environment().putAll(this.environment);
        Process process = builder.start();
        CompletableFuture<String> errors = read(process.getErrorStream());
        try (BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
            assertTrue(readUntil(output, "CLEANUP"));
            assertTrue(process.isAlive());
            assertFalse(recorded().contains("start"));

            // 明确释放测试自身的清理之后，守卫才恢复 Linux 服务并报告非零状态。
            Files.writeString(release, "finish", StandardCharsets.UTF_8);
            assertTrue(process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            String console = errors.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertNotEquals(0, process.exitValue(), console);
            List<Object> records = recorded();
            int exited = records.indexOf("child-exited");
            assertTrue(exited > records.indexOf("chrony-owner-exited"), records.toString());
            assertTrue(records.indexOf("start") > exited, records.toString());
            assertTrue(console.contains("Windows clock owner exited unexpectedly"));
            assertEquals("active", currentState());
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private void assertSignalCleanup(String signal, int exitCode) throws Exception {
        // FIFO 用显式握手阻塞子进程清理，不用固定休眠推断先后顺序。
        Path release = createCleanupRelease();
        Path childPid = this.directory.resolve("signal-child.pid");
        this.environment.put("GUARD_TEST_CHILD_PID", childPid.toString());
        Path signalChild = command("signal-child");
        ProcessBuilder builder = new ProcessBuilder(arguments(signalChild.toString(), release.toString()));
        builder.environment().clear();
        builder.environment().putAll(this.environment);
        Process process = builder.start();
        CompletableFuture<String> errors = read(process.getErrorStream());
        try (BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
            assertTrue(readUntil(output, "READY"));
            Process delivery = new ProcessBuilder("/bin/kill", "-s", signal, Long.toString(process.pid())).start();
            assertTrue(delivery.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(0, delivery.exitValue());

            // 子进程已进入清理，但守卫仍在等待，尚未恢复服务。
            assertTrue(readUntil(output, "CLEANUP"));
            assertTrue(process.isAlive());
            assertFalse(recorded().contains("start"));
            assertFalse(recorded().contains("chrony-restored"));
            Files.writeString(release, "finish", StandardCharsets.UTF_8);
            assertTrue(process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(exitCode, process.exitValue(), errors.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            if (StringUtils.equals("true", this.environment.get("GUARD_TEST_WSL"))) {
                assertChronyLifecycle("chrony-paused", "child-exited", "chrony-restored");
            } else {
                assertEquals(serviceLifecycle("child-exited"), recorded());
            }
        } finally {
            // 断言失败时也只终止本 testcase 创建的子进程和守卫。
            if (process.isAlive()) {
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
                process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
        }
    }

    private Map<String, Object> runGuard(String... command) throws Exception {
        return run(arguments(command));
    }

    private Path createCleanupRelease() throws Exception {
        Path release = this.directory.resolve("cleanup-release");
        Process fifo = new ProcessBuilder("/usr/bin/mkfifo", release.toString()).start();
        assertTrue(fifo.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        assertEquals(0, fifo.exitValue());
        return release;
    }

    private Path prepareReport() throws Exception {
        Path artifacts = Files.createDirectory(this.privateBuild.resolve("artifacts"));
        this.environment.put("GUARD_TEST_ARTIFACTS", artifacts.toString());
        return artifacts;
    }

    private List<Path> diagnostics() throws Exception {
        Path reports = this.privateBuild.resolve("meteor-test-clock");
        if (!Files.isDirectory(reports)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(reports)) {
            return entries //
                    .filter(path -> path.getFileName().toString().startsWith("guard.")) //
                    .collect(Collectors.toList());
        }
    }

    private Map<String, Object> run(List<String> arguments) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.environment().clear();
        builder.environment().putAll(this.environment);
        Process process = builder.start();
        CompletableFuture<String> output = read(process.getInputStream());
        CompletableFuture<String> errors = read(process.getErrorStream());
        try {
            assertTrue(process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Guard command timed out");
            return Map.of("exitCode", process.exitValue(),
                    "stdout", output.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "stderr", errors.get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(handle -> handle.destroyForcibly());
                process.destroyForcibly();
            }
        }
    }

    private List<String> arguments(String... command) throws Exception {
        List<String> arguments = new ArrayList<>();
        arguments.add("/bin/bash");
        arguments.add("-c");
        arguments.add("source \"$1\"; shift; with_clock_paused \"$@\"");
        arguments.add("guard");
        arguments.add(this.privateGuard.toString());
        arguments.addAll(List.of(command));
        return arguments;
    }

    private Path command(String name) throws Exception {
        Path executable = this.directory.resolve("bin").resolve(name);
        Path java = Path.of(System.getProperty("java.home"), "bin/java");
        String classpath = Stream.of(FakeGuardMain.class, ObjectMapper.class, JsonFactory.class,
                JsonAlias.class, StringUtils.class) //
                .map(NxnClockGuardTest::classLocation) //
                .map(Path::toString) //
                .distinct() //
                .collect(Collectors.joining(System.getProperty("path.separator")));
        String script = "#!/bin/bash\nexec " + quote(java.toString()) + " -cp " + quote(classpath)
                + " " + FakeGuardMain.class.getName() + " " + quote(name) + " \"$@\"\n";
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        assertTrue(executable.toFile().setExecutable(true));
        return executable;
    }

    private void configure(String active, Map<String, Object> failures) throws Exception {
        Map<String, Object> values = new LinkedHashMap<>(failures);
        values.put("active", active);
        JSON.writeValue(this.state.toFile(), values);
    }

    private void enableWsl(Map<String, Object> failures) throws Exception {
        this.environment.put("GUARD_TEST_WSL", "true");
        configure("active", failures);
    }

    private void assertChronyLifecycle(String pause, String childEvent, String restoration) throws Exception {
        List<Object> records = recorded();
        int pauseIndex = records.indexOf(pause);
        int childIndex = records.indexOf(childEvent);
        int restoredIndex = records.indexOf(restoration);
        assertTrue(pauseIndex >= 0, records.toString());
        assertTrue(childIndex > pauseIndex, records.toString());
        assertTrue(restoredIndex > childIndex, records.toString());
    }

    private String currentState() throws Exception {
        return JSON.readTree(this.state.toFile()).path("active").asText();
    }

    private List<Object> recorded() throws Exception {
        List<Object> records = new ArrayList<>();
        if (Files.exists(this.events)) {
            for (String line : Files.readAllLines(this.events)) {
                records.add(JSON.readValue(line, Object.class));
            }
        }
        return records;
    }

    private static List<String> serviceLifecycle(String childEvent) {
        return List.of("is-active", "stop", "is-active", childEvent, "start", "is-active");
    }

    private static CompletableFuture<String> read(InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private static String readLine(BufferedReader input) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return input.readLine();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).get(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static boolean readUntil(BufferedReader input, String expected) throws Exception {
        for (int count = 0; count < 30; count++) {
            String line = readLine(input);
            if (line == null) {
                return false;
            }
            if (StringUtils.equals(expected, line)) {
                return true;
            }
        }
        return false;
    }

    private static Path guard() throws Exception {
        Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (repository != null) {
            Path candidate = repository.resolve("meteor-test/tools/clock/with-clock-paused.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            repository = repository.getParent();
        }
        throw new IllegalStateException("Clock-service guard script is missing.");
    }



    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static Path classLocation(Class<?> type) {
        String location = type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        return Path.of(URI.create(location));
    }
}
