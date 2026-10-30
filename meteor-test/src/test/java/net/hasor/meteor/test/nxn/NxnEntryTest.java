package net.hasor.meteor.test.nxn;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 正式 Shell 入口使用私有假 Gradle，验证终端中断与重新启动，不访问 Docker 或校时服务。 */
public class NxnEntryTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();
    private Path repository;
    private Path entry;
    private Path home;
    private Map<String, String> environment;
    private final List<Process> processes = new ArrayList<>();

    @Before
    public void prepareEntry() throws Exception {
        // 所有缓存、控制请求、日志和假命令都属于当前 testcase。
        this.repository = this.temporary.getRoot().toPath();
        Path clockTools = Files.createDirectories(this.repository.resolve("meteor-test/tools/clock"));
        this.entry = this.repository.resolve("meteor-test/runnxn.sh");
        Files.copy(sourceEntry(), this.entry);
        this.home = Files.createDirectory(this.repository.resolve("home"));
        this.environment = new LinkedHashMap<>(System.getenv());
        this.environment.remove("SUDO_USER");
        this.environment.remove("SUDO_UID");
        this.environment.remove("SUDO_GID");
        this.environment.put("HOME", this.home.toString());
        this.environment.put("ENTRY_TEST_DIRECTORY", this.repository.toString());
        Files.writeString(this.repository.resolve("gradlew"), """
                #!/usr/bin/env bash
                set -euo pipefail
                run=${ENTRY_TEST_RUN:-first}
                if [[ ${ENTRY_TEST_STARTUP_HOLD:-false} == true ]]; then
                    printf '[fake-gradle] %s startup waiting\\n' "$run"
                    touch "$ENTRY_TEST_DIRECTORY/$run.startup"
                    while [[ ! -f "$METEOR_TEST_CONTROL_DIRECTORY/cancel.request" ]]; do sleep 0.02; done
                    cat "$METEOR_TEST_CONTROL_DIRECTORY/cancel.request" > "$ENTRY_TEST_DIRECTORY/$run.cancel"
                    touch "$ENTRY_TEST_DIRECTORY/$run.cancelled"
                    while [[ ! -f "$ENTRY_TEST_DIRECTORY/$run.release" ]]; do sleep 0.02; done
                    printf '[fake-gradle] %s startup cleanup complete\\n' "$run"
                    exit 1
                fi
                printf '%s\\n' "$METEOR_TEST_CONTROL_DIRECTORY" > "$ENTRY_TEST_DIRECTORY/$run.control"
                printf '%s\\n' "$@" > "$ENTRY_TEST_DIRECTORY/$run.arguments"
                printf '[fake-gradle] %s started\\n' "$run"
                if [[ ${ENTRY_TEST_READ_STDIN:-false} == true ]]; then
                    if IFS= read -r input; then
                        printf '[fake-gradle] unexpected stdin: %s\\n' "$input"
                        exit 42
                    fi
                    printf '[fake-gradle] stdin EOF\\n'
                fi
                if [[ ${ENTRY_TEST_HOLD:-false} == true ]]; then
                    printf '%s\\n%s\\n' "$METEOR_TEST_CONTROL_DIRECTORY/cancel.request" \\
                        "$METEOR_TEST_ENTRY_PID" > "$ENTRY_TEST_DIRECTORY/meteor-test/build/meteor-test-control/docker.control"
                    touch "$ENTRY_TEST_DIRECTORY/$run.started"
                    while [[ ! -f "$METEOR_TEST_CONTROL_DIRECTORY/cancel.request" ]]; do sleep 0.02; done
                    cat "$METEOR_TEST_CONTROL_DIRECTORY/cancel.request" > "$ENTRY_TEST_DIRECTORY/$run.cancel"
                    touch "$ENTRY_TEST_DIRECTORY/$run.cancelled"
                    printf '[fake-gradle] %s cancellation observed\\n' "$run"
                    while [[ ! -f "$ENTRY_TEST_DIRECTORY/$run.release" ]]; do sleep 0.02; done
                    printf '[fake-gradle] %s archive complete\\n' "$run"
                    printf '[fake-gradle] %s cleanup complete\\n' "$run"
                    touch "$ENTRY_TEST_DIRECTORY/$run.cleaned"
                    exit "${ENTRY_TEST_EXIT:-1}"
                fi
                exit "${ENTRY_TEST_EXIT:-0}"
                """, StandardCharsets.UTF_8);
        Files.writeString(clockTools.resolve("with-clock-paused.sh"), """
                #!/usr/bin/env bash
                printf '[fake-clock] paused\\n'
                "$@"
                result=$?
                if [[ ${ENTRY_TEST_RESTORE_HOLD:-false} == true ]]; then
                    touch "$ENTRY_TEST_DIRECTORY/restore.started"
                    while [[ ! -f "$ENTRY_TEST_DIRECTORY/restore.release" ]]; do sleep 0.02; done
                fi
                printf '[fake-clock] restored\\n'
                exit "$result"
                """, StandardCharsets.UTF_8);
    }

    @After
    public void stopOwnedProcesses() throws Exception {
        // 只释放 testcase 自己启动的进程；失败断言也不能遗留假客户端。
        for (Process process : this.processes) {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void ordinaryEntryPreservesArgumentsAndOutput() throws Exception {
        // 参数中保留空格，入口不启用任何校时操作。
        Process process = start("first", "--groups", "4", "--tests", "demo with spaces.*");

        // 假 Gradle 收到正式任务和原始参数，退出后控制目录被释放。
        assertEquals(0, awaitExit(process));
        List<String> expected = List.of(":meteor-test:dockerTest", "--groups", "4", "--tests", "demo with spaces.*");
        assertEquals(expected, Files.readAllLines(this.repository.resolve("first.arguments")));
        assertTrue(output("first").contains("[fake-gradle] first started"));
        assertFalse(output("first").contains("[fake-clock]"));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void entryControlStateStaysInsideTestModuleBuild() throws Exception {
        // 正式入口在测试工程内登记取消请求，不将控制文件写入调用者的 HOME。
        Process process = start("first");

        // 本轮目录完成后释放，其父目录和锁归属仍位于测试工程的 build 中。
        assertEquals(0, awaitExit(process));
        Path expected = this.repository.resolve("meteor-test/build/meteor-test-control");
        assertEquals(expected, control("first").getParent());
        assertFalse(Files.exists(this.repository.resolve("build/meteor-test-control")));
        assertFalse(Files.exists(this.home.resolve(".cache")));
    }

    @Test
    public void commandFailurePreservesOriginalExitCode() throws Exception {
        // 命令本身失败与用户取消具有不同退出码。
        this.environment.put("ENTRY_TEST_EXIT", "42");
        Process process = start("first");

        // 入口不将业务失败转成成功，也释放其私有控制目录。
        assertEquals(42, awaitExit(process));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void backgroundGradleReceivesStdinEofInsteadOfWaitingForTerminalInput() throws Exception {
        // 父进程仍持有打开的 stdin 管道，假 Gradle 主动读取时必须直接收到 EOF。
        this.environment.put("ENTRY_TEST_READ_STDIN", "true");
        Process process = start("first");

        // 入口将后台客户端输入连接到 /dev/null，避免读取终端时因 SIGTTIN 停住。
        assertEquals(0, awaitExit(process));
        assertTrue(output("first").contains("[fake-gradle] stdin EOF"));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void directRootEntryWithoutSudoDoesNotControlClocks() throws Exception {
        // root 直接运行与 sudo 启动具有不同语义，前者不应自动暂停校时。
        String source = Files.readString(this.entry);
        Files.writeString(this.entry, source.replace("\"$EUID\"", "\"0\""));
        Process process = start("first");

        // 命令正常执行，且没有经过任何假校时守卫。
        assertEquals(0, awaitExit(process));
        assertTrue(output("first").contains("[fake-gradle] first started"));
        assertFalse(output("first").contains("[fake-clock]"));
    }

    @Test
    public void helpDoesNotStartGradleOrCreateControlDirectory() throws Exception {
        // 帮助应在测试控制和 sudo 分流之前完成。
        this.environment.put("SUDO_USER", "developer");
        Process process = start("first", "--help");

        // 不创建测试环境，不触发任何假客户端或校时守卫。
        assertEquals(0, awaitExit(process));
        assertTrue(output("first").contains("用法"));
        assertFalse(Files.exists(this.repository.resolve("build")));
        assertFalse(Files.exists(this.home.resolve(".cache")));
        assertFalse(Files.exists(this.repository.resolve("first.control")));
    }

    @Test
    public void terminalInterruptKeepsClientAliveUntilCleanupLogsFinish() throws Exception {
        // 对整个入口进程组发送终端 INT，假 Gradle 在独立进程组中等待取消请求。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first");
        awaitFile("first.started");
        signal(process, "INT");
        awaitFile("first.cancelled");

        // 取消不关闭客户端，归档及清理日志仍送达原控制台。
        assertTrue(process.isAlive());
        assertEquals("INT", Files.readString(this.repository.resolve("first.cancel")).trim());
        Files.createFile(this.repository.resolve("first.release"));
        assertEquals(130, awaitExit(process));
        assertTrue(output("first").contains("archive complete"));
        assertTrue(output("first").contains("cleanup complete"));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void terminationWaitsForCleanupAndReturnsTermExitCode() throws Exception {
        // TERM 与 INT 同样通过控制文件传递，不打断客户端日志链路。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first");
        awaitFile("first.started");
        signal(process, "TERM");
        awaitFile("first.cancelled");

        // 用户终止保留 143，同时等待实际清理结束。
        assertTrue(process.isAlive());
        assertEquals("TERM", Files.readString(this.repository.resolve("first.cancel")).trim());
        Files.createFile(this.repository.resolve("first.release"));
        assertEquals(143, awaitExit(process));
        assertTrue(Files.exists(this.repository.resolve("first.cleaned")));
        assertTrue(output("first").contains("cleanup complete"));
    }

    @Test
    public void externalRestartRequestPreservesCancellationExitCodeAfterCleanup() throws Exception {
        // 新入口通过控制文件取消旧轮，不会向旧入口或 Gradle 客户端发送信号。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first");
        awaitFile("first.started");
        Files.writeString(control("first").resolve("cancel.request"), "RESTART\n");
        awaitFile("first.cancelled");

        // 即使客户端只返回普通失败码，也保留 RESTART 的取消语义并等到清理结束。
        assertTrue(process.isAlive());
        Files.createFile(this.repository.resolve("first.release"));
        assertEquals(130, awaitExit(process));
        assertTrue(output("first").contains("archive complete"));
        assertTrue(output("first").contains("cleanup complete"));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void successfulClientCannotHideAnExternalCancellationRequest() throws Exception {
        // 客户端可能正常完成退出，但本轮已经收到明确的取消请求。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        this.environment.put("ENTRY_TEST_EXIT", "0");
        Process process = start("first");
        awaitFile("first.started");
        Files.writeString(control("first").resolve("cancel.request"), "INT\n");
        awaitFile("first.cancelled");
        Files.createFile(this.repository.resolve("first.release"));

        // 取消保留非零退出码，正常的清理日志仍完整输出。
        assertEquals(130, awaitExit(process));
        assertTrue(output("first").contains("cleanup complete"));
    }

    @Test
    public void externalTermRequestRetainsItsOwnExitCode() throws Exception {
        // 外部终止只写控制文件，入口没有收到 TERM 信号。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first");
        awaitFile("first.started");
        Files.writeString(control("first").resolve("cancel.request"), "TERM\n");
        awaitFile("first.cancelled");
        Files.createFile(this.repository.resolve("first.release"));

        // 文件内的终止原因仍转换为 143，而非客户端通用的失败码。
        assertEquals(143, awaitExit(process));
        assertTrue(output("first").contains("cleanup complete"));
    }

    @Test
    public void repeatedInterruptDoesNotKillClientDuringCleanup() throws Exception {
        // 第二次 INT 到达时清理尚未完成。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first");
        awaitFile("first.started");
        signal(process, "INT");
        awaitFile("first.cancelled");
        signal(process, "INT");
        awaitCondition(() -> uncheckedOutput("first").contains("Cancellation is already requested"));

        // 首次取消原因保持不变，第二次仅提示等待。
        assertTrue(process.isAlive());
        assertEquals("INT", Files.readString(control("first").resolve("cancel.request")).trim());
        Files.createFile(this.repository.resolve("first.release"));
        assertEquals(130, awaitExit(process));
        assertTrue(output("first").contains("cleanup complete"));
    }

    @Test
    public void sudoEntryWaitsForClockRestorationAfterCancellation() throws Exception {
        // 私有身份查询和 setpriv 只执行本 testcase 的假命令，不改变系统身份或时钟。
        prepareSudo();
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first");
        awaitFile("first.started");
        signal(process, "INT");
        awaitFile("first.cancelled");
        Files.createFile(this.repository.resolve("first.release"));

        // 最外层入口一直等待到守卫完成恢复，恢复日志位于清理日志之后。
        assertEquals(130, awaitExit(process));
        String log = output("first");
        assertTrue(log.contains("[fake-clock] paused"));
        assertTrue(log.indexOf("[fake-clock] restored") > log.indexOf("cleanup complete"));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void sudoEntryPreservesFailureAfterClockRestoration() throws Exception {
        // 普通 sudo 完成路径仍由原守卫封装 Gradle。
        prepareSudo();
        this.environment.put("ENTRY_TEST_EXIT", "42");
        Process process = start("first", "--groups", "2");

        // 原命令退出码、参数和恢复日志都得到保留。
        assertEquals(42, awaitExit(process));
        assertTrue(output("first").contains("[fake-clock] restored"));
        assertEquals(List.of(":meteor-test:dockerTest", "--groups", "2"),
                Files.readAllLines(this.repository.resolve("first.arguments")));
    }

    @Test
    public void restartRequestsOldEntryCleanupBeforeStartingNewGradle() throws Exception {
        // 旧进程通过同一用户缓存登记控制路径，第二个入口应先协调其退出。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process previous = start("first");
        awaitFile("first.started");
        this.environment.remove("ENTRY_TEST_HOLD");
        Process current = start("second");
        awaitFile("first.cancelled");

        // 新命令等待旧命令归档清理，不能提前接管固定环境。
        assertEquals("RESTART", Files.readString(this.repository.resolve("first.cancel")).trim());
        assertFalse(Files.exists(this.repository.resolve("second.control")));
        Files.createFile(this.repository.resolve("first.release"));
        assertEquals(130, awaitExit(previous));
        assertEquals(0, awaitExit(current));
        assertTrue(Files.exists(this.repository.resolve("first.cleaned")));
        assertTrue(output("second").contains("Previous test cancellation requested"));
    }

    @Test
    public void restartFindsAnEntryBeforeItsGradleTaskStarts() throws Exception {
        // 旧客户端停在 Gradle 启动阶段，尚未由测试任务发布任何控制信息。
        this.environment.put("ENTRY_TEST_STARTUP_HOLD", "true");
        Process previous = start("first");
        awaitFile("first.startup");
        Path pointer = this.repository.resolve("meteor-test/build/meteor-test-control/docker.control");
        List<String> owner = Files.readAllLines(pointer);
        assertEquals(Long.toString(previous.pid()), owner.get(1));
        assertFalse(Files.exists(this.repository.resolve("first.control")));
        assertFalse(Files.exists(this.repository.resolve("first.arguments")));

        // 新入口根据 wrapper 提前登记的 pointer 请求取消，并等待启动阶段清理完成。
        this.environment.remove("ENTRY_TEST_STARTUP_HOLD");
        Process current = start("second");
        awaitFile("first.cancelled");
        assertEquals("RESTART", Files.readString(Path.of(owner.get(0))).trim());
        assertTrue(current.isAlive());
        assertFalse(Files.exists(this.repository.resolve("second.arguments")));
        Files.createFile(this.repository.resolve("first.release"));

        // 旧轮退出后新任务才启动，两轮都不会遗留入口 pointer。
        assertEquals(130, awaitExit(previous));
        assertEquals(0, awaitExit(current));
        assertTrue(output("first").contains("startup cleanup complete"));
        assertFalse(Files.exists(pointer));
    }

    @Test
    public void restartWaitsForSudoClockRestorationAfterGradleHasFinished() throws Exception {
        // 旧轮的 Gradle 已清理结束，sudo 守卫仍持有本轮校时恢复责任。
        prepareSudo();
        this.environment.put("ENTRY_TEST_HOLD", "true");
        this.environment.put("ENTRY_TEST_RESTORE_HOLD", "true");
        Process previous = start("first");
        awaitFile("first.started");
        Files.writeString(control("first").resolve("cancel.request"), "INT\n");
        awaitFile("first.cancelled");
        Files.createFile(this.repository.resolve("first.release"));
        awaitFile("restore.started");

        // 新入口仍能从 pointer 找到外层 wrapper，在校时恢复屏障前不得启动客户端。
        this.environment.remove("ENTRY_TEST_HOLD");
        this.environment.remove("ENTRY_TEST_RESTORE_HOLD");
        Process current = start("second");
        awaitCondition(() -> uncheckedOutput("second").contains("Previous test cancellation requested"));
        assertTrue(previous.isAlive());
        assertTrue(current.isAlive());
        assertFalse(Files.exists(this.repository.resolve("second.control")));
        Files.createFile(this.repository.resolve("restore.release"));

        // 旧守卫完成恢复之后，新轮才取得环境并继续执行。
        assertEquals(130, awaitExit(previous));
        assertEquals(0, awaitExit(current));
        assertTrue(output("first").contains("[fake-clock] restored"));
        assertFalse(Files.exists(this.repository.resolve("meteor-test/build/meteor-test-control/docker.control")));
    }

    @Test
    public void finishingEntryCannotRemoveAnotherOwnersPointer() throws Exception {
        // 当前命令运行期间 pointer 已由另一个所有者登记。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first");
        awaitFile("first.started");
        Path pointer = this.repository.resolve("meteor-test/build/meteor-test-control/docker.control");
        Path anotherRequest = pointer.getParent().resolve("control.another/cancel.request");
        String anotherOwner = anotherRequest + "\n\n";
        Files.writeString(pointer, anotherOwner);
        signal(process, "INT");
        awaitFile("first.cancelled");
        Files.createFile(this.repository.resolve("first.release"));

        // 退出只清理本入口控制目录，不能删除别人的活动 pointer。
        assertEquals(130, awaitExit(process));
        assertEquals(anotherOwner, Files.readString(pointer));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void directGradleOwnerReceivesRestartWithoutEntryPid() throws Exception {
        // 直接 Gradle 没有 Shell 入口 PID，仍能从已登记控制路径收到协作取消。
        Path cache = Files.createDirectories(this.repository.resolve("meteor-test/build/meteor-test-control"));
        Path previous = Files.createDirectory(cache.resolve("control.previous"));
        Path request = previous.resolve("cancel.request");
        Files.writeString(cache.resolve("docker.control"), request + "\n\n");
        Process current = start("first");

        // Shell 只发请求，真正的资源身份与锁接管由任务继续核验。
        assertEquals(0, awaitExit(current));
        assertEquals("RESTART", Files.readString(request).trim());
    }

    @Test
    public void pointerOutsideRepositoryControlCannotBeWrittenOrSignalled() throws Exception {
        // 即使 pointer 提供当前测试进程 PID，非法路径也不能触发写入或等待。
        Path cache = Files.createDirectories(this.repository.resolve("meteor-test/build/meteor-test-control"));
        Path outside = Files.createDirectory(this.repository.resolve("outside"));
        Path request = outside.resolve("cancel.request");
        Files.writeString(cache.resolve("docker.control"), request + "\n" + ProcessHandle.current().pid() + "\n");
        Process current = start("first");

        // 非法 pointer 不影响启动，只由 Gradle 后续验证其拥有的环境。
        assertEquals(0, awaitExit(current));
        assertFalse(Files.exists(request));
        assertTrue(output("first").contains("Ignoring an invalid previous control path"));
    }

    private Process start(String name, String... arguments) throws Exception {
        // 独立会话模拟真实终端向入口进程组广播信号，绝不向测试 JVM 发送信号。
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/setsid");
        command.add("/bin/bash");
        command.add(this.entry.toString());
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(this.environment);
        builder.environment().put("ENTRY_TEST_RUN", name);
        builder.redirectErrorStream(true);
        builder.redirectOutput(this.repository.resolve(name + ".log").toFile());
        Process process = builder.start();
        this.processes.add(process);
        return process;
    }

    private void prepareSudo() throws Exception {
        // 只在私有脚本中固定 EUID；身份与降权命令都保持在封闭夹具内。
        String source = Files.readString(this.entry);
        assertTrue(source.contains("\"$EUID\""));
        Files.writeString(this.entry, source.replace("\"$EUID\"", "\"0\""));
        Path bin = Files.createDirectory(this.repository.resolve("bin"));
        String accountLookup = """
                #!/bin/bash
                printf 'developer:x:1234:5678:Example:%s:/bin/bash\\n' "$HOME"
                """;
        Files.writeString(bin.resolve("getent"), accountLookup);
        Files.writeString(bin.resolve("setpriv"), """
                #!/bin/bash
                while [[ "$1" != -- ]]; do shift; done
                shift
                exec "$@"
                """);
        assertTrue(bin.resolve("getent").toFile().setExecutable(true));
        assertTrue(bin.resolve("setpriv").toFile().setExecutable(true));
        this.environment.put("PATH", bin + ":" + this.environment.get("PATH"));
        this.environment.put("SUDO_USER", "developer");
        this.environment.put("SUDO_UID", "1234");
        this.environment.put("SUDO_GID", "5678");
    }

    private int awaitExit(Process process) throws Exception {
        assertTrue("Entry did not finish", process.waitFor(15, TimeUnit.SECONDS));
        return process.exitValue();
    }

    private void signal(Process process, String signal) throws Exception {
        Process command = new ProcessBuilder("/bin/kill", "-" + signal, "--", "-" + process.pid()).start();
        assertTrue(command.waitFor(5, TimeUnit.SECONDS));
        assertEquals(0, command.exitValue());
    }

    private Path control(String name) throws Exception {
        return Path.of(Files.readString(this.repository.resolve(name + ".control")).trim());
    }

    private String output(String name) throws Exception {
        return Files.readString(this.repository.resolve(name + ".log"));
    }

    private String uncheckedOutput(String name) {
        try {
            return output(name);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private void awaitFile(String name) throws Exception {
        awaitCondition(() -> Files.exists(this.repository.resolve(name)));
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("Expected entry state was not observed", condition.getAsBoolean());
    }

    private static Path sourceEntry() {
        String explicit = System.getProperty("meteor.test.repository");
        Path current = explicit == null ? Path.of("").toAbsolutePath() : Path.of(explicit);
        while (current != null) {
            Path candidate = current.resolve("meteor-test/runnxn.sh");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Meteor test entry script is missing.");
    }
}
