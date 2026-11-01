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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 正式 Shell 入口使用私有假 Gradle，验证终端中断与重新启动，不访问 Docker 或校时服务。 */
public class NxnEntryTest {
    @TempDir
    public Path temporary;
    private Path repository;
    private Path entry;
    private Path home;
    private Map<String, String> environment;
    private final List<Process> processes = new ArrayList<>();

    @BeforeEach
    public void prepareEntry() throws Exception {
        // 所有缓存、控制请求、日志和假命令都属于当前 testcase。
        this.repository = this.temporary;
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
                    pointer=$ENTRY_TEST_DIRECTORY/meteor-test/build/meteor-test-control/docker.control
                    printf '%s\\n%s\\n' "$METEOR_TEST_CONTROL_DIRECTORY/cancel.request" \\
                        "$METEOR_TEST_ENTRY_PID" > "$pointer"
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

    @AfterEach
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
        Process process = start("first", "all", "--groups", "4", "--tests", "demo with spaces.*");

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
        Process process = start("first", "all");

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
        Process process = start("first", "all");

        // 入口不将业务失败转成成功，也释放其私有控制目录。
        assertEquals(42, awaitExit(process));
        assertFalse(Files.exists(control("first")));
    }

    @Test
    public void backgroundGradleReceivesStdinEofInsteadOfWaitingForTerminalInput() throws Exception {
        // 父进程仍持有打开的 stdin 管道，假 Gradle 主动读取时必须直接收到 EOF。
        this.environment.put("ENTRY_TEST_READ_STDIN", "true");
        Process process = start("first", "all");

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
        Process process = start("first", "all");

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
    public void noArgumentsPrintHelpBeforeCheckingSudoIdentity() throws Exception {
        // 即使 sudo 身份信息不完整，无参仍只展示用法。
        this.environment.put("SUDO_USER", "developer");
        Process process = start("first");

        // 帮助没有创建执行状态，也没有启动 Gradle 或校时守卫。
        assertEquals(0, awaitExit(process));
        assertTrue(output("first").contains("不传参数只显示帮助"));
        assertNoExecution("first");
    }

    @Test
    public void helpTargetDoesNotStartAnExecution() throws Exception {
        // help 与 --help 使用相同的只读入口。
        Process process = start("first", "help");

        // 显式帮助不占用固定环境。
        assertEquals(0, awaitExit(process));
        assertTrue(output("first").contains("用法"));
        assertNoExecution("first");
    }

    @Test
    public void allTargetRunsTheUnfilteredDockerTask() throws Exception {
        // all 只声明完整范围，不成为 Gradle 的另一个任务名。
        Process process = start("first", "all");

        // 未添加协议或 testcase 过滤，保持完整回归范围。
        assertEquals(0, awaitExit(process));
        assertEquals(List.of(":meteor-test:dockerTest"), arguments("first"));
    }

    @Test
    public void rpcTargetForwardsTheSelectedProtocol() throws Exception {
        // 协议选择交给统一的 Gradle 和 Java 编排链路。
        Process process = start("first", "rpc", "grpc");

        // 前端不展开为包名通配符，协议外的公共矩阵仍可参与筛选。
        assertEquals(0, awaitExit(process));
        assertEquals(List.of(":meteor-test:dockerTest", "--rpc", "grpc"), arguments("first"));
    }

    @Test
    public void rpcVersionForwardsTheWireProtocolVersion() throws Exception {
        // 版本值表示 RPC 线协议，不转换为 SDK 发布版本。
        Process process = start("first", "rpc", "soap", "1.2");

        // 原始版本完整传给执行计划。
        assertEquals(0, awaitExit(process));
        List<String> expected = List.of(":meteor-test:dockerTest", "--rpc", "soap", "--rpc-version", "1.2");
        assertEquals(expected, arguments("first"));
    }

    @Test
    public void dubboVersionForwardsItsMajorProtocolFamily() throws Exception {
        // Dubbo 允许按 2 或 3 选择协议大版本。
        Process process = start("first", "rpc", "dubbo", "3");

        // 入口不将大版本替换为某个具体 SDK 版本。
        assertEquals(0, awaitExit(process));
        List<String> expected = List.of(":meteor-test:dockerTest", "--rpc", "dubbo", "--rpc-version", "3");
        assertEquals(expected, arguments("first"));
    }

    @Test
    public void grpcWebAliasUsesTheCanonicalProtocolName() throws Exception {
        // 常见简写统一到矩阵中的正式协议名。
        Process process = start("first", "rpc", "grpcweb");

        // 后续阶段只需要处理一种 gRPC-Web 名称。
        assertEquals(0, awaitExit(process));
        assertEquals(List.of(":meteor-test:dockerTest", "--rpc", "grpc-web"), arguments("first"));
    }

    @Test
    public void oncRpcAliasUsesTheCanonicalProtocolName() throws Exception {
        // 带连字符的 ONC RPC 别名可用于命令入口。
        Process process = start("first", "rpc", "onc-rpc");

        // 执行计划收到统一的 oncrpc 标识。
        assertEquals(0, awaitExit(process));
        assertEquals(List.of(":meteor-test:dockerTest", "--rpc", "oncrpc"), arguments("first"));
    }

    @Test
    public void protocolNamesIgnoreInputCase() throws Exception {
        // 协议名称的输入大小写不改变选中范围。
        Process process = start("first", "rpc", "GRpC");

        // 统一的小写名称交给执行计划处理。
        assertEquals(0, awaitExit(process));
        assertEquals(List.of(":meteor-test:dockerTest", "--rpc", "grpc"), arguments("first"));
    }

    @Test
    public void testTargetPreservesAParameterizedMethodPattern() throws Exception {
        // 方法、方括号、空格和通配符都属于同一个 testcase 选择参数。
        String pattern = "OrderSelfMatrixTest.savesOrder[self/grpc/direct with spaces]*";
        Process process = start("first", "test", pattern);

        // Shell 不重新拆分参数化名称。
        assertEquals(0, awaitExit(process));
        assertEquals(List.of(":meteor-test:dockerTest", "--tests", pattern), arguments("first"));
    }

    @Test
    public void testsOptionProvidesAnExplicitScopeWithoutATarget() throws Exception {
        // 原有 --tests 形式本身已声明范围，无需再增加 all。
        Process process = start("first", "--tests", "SomeTest.someMethod");

        // 具体方法仍通过统一的 JUnit 筛选参数传递。
        assertEquals(0, awaitExit(process));
        List<String> expected = List.of(":meteor-test:dockerTest", "--tests", "SomeTest.someMethod");
        assertEquals(expected, arguments("first"));
    }

    @Test
    public void rpcSelectionKeepsRepeatedTestcaseFilters() throws Exception {
        // 协议和具体 testcase 同时声明，由执行计划取交集。
        Process process = start("first", "rpc", "grpc", "--tests", "FirstTest.call[json]",
                "--tests", "SecondTest.some case");

        // 重复筛选和值边界不会被协议目标覆盖。
        assertEquals(0, awaitExit(process));
        List<String> expected = List.of(":meteor-test:dockerTest", "--rpc", "grpc", "--tests",
                "FirstTest.call[json]", "--tests", "SecondTest.some case");
        assertEquals(expected, arguments("first"));
    }

    @Test
    public void equalsTestOptionPreservesItsCompletePattern() throws Exception {
        // 等号形式与独立参数形式具有相同语义。
        Process process = start("first", "--tests=SomeTest.call[with spaces]");

        // 等号后的完整内容被保留为一个测试筛选值。
        assertEquals(0, awaitExit(process));
        List<String> expected = List.of(":meteor-test:dockerTest", "--tests", "SomeTest.call[with spaces]");
        assertEquals(expected, arguments("first"));
    }

    @Test
    public void jobsAliasPreservesGradleOptions() throws Exception {
        // --jobs 只作为并发数量别名，普通 Gradle 选项继续传递。
        Process process = start("first", "all", "--jobs", "4", "--console", "plain", "--stacktrace");

        // 转换只影响别名，参数的原始顺序保持不变。
        assertEquals(0, awaitExit(process));
        List<String> expected = List.of(":meteor-test:dockerTest", "--groups", "4", "--console", "plain",
                "--stacktrace");
        assertEquals(expected, arguments("first"));
    }

    @Test
    public void unknownTargetFailsBeforeCreatingExecutionState() throws Exception {
        // 拼错目标不能退化成全量回归。
        Process process = start("first", "everything");

        // 参数错误保留独立退出码且不启动任何执行器。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("Unknown test target: everything"));
        assertNoExecution("first");
    }

    @Test
    public void unknownRpcFailsBeforeCheckingSudoIdentity() throws Exception {
        // 错误协议在 sudo 身份查询及校时控制之前报告。
        this.environment.put("SUDO_USER", "developer");
        Process process = start("first", "rpc", "missing");

        // 不认识的协议不会启动测试，也不掩盖为身份错误。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("Unknown RPC protocol: missing"));
        assertNoExecution("first");
    }

    @Test
    public void missingRpcNameFailsWithoutStartingGradle() throws Exception {
        // rpc 必须跟随一个明确的协议名。
        Process process = start("first", "rpc");

        // 缺失值不会被解释为完整回归。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("rpc requires a protocol name"));
        assertNoExecution("first");
    }

    @Test
    public void extraRpcVersionFailsBeforeExecution() throws Exception {
        // 一个协议目标最多接受一个线协议版本。
        Process process = start("first", "rpc", "soap", "1.2", "extra");

        // 多余位置参数不会作为 Gradle 任务启动。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("Unexpected argument: extra"));
        assertNoExecution("first");
    }

    @Test
    public void missingTestTargetPatternFailsBeforeExecution() throws Exception {
        // test 目标必须明确选择 testcase。
        Process process = start("first", "test");

        // 参数错误不会建立控制目录。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("test requires a test pattern"));
        assertNoExecution("first");
    }

    @Test
    public void missingTestsOptionValueFailsBeforeExecution() throws Exception {
        // 单独的 --tests 不能构成有效筛选。
        Process process = start("first", "--tests");

        // 缺失值在 Shell 入口直接报告。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("--tests requires a test pattern"));
        assertNoExecution("first");
    }

    @Test
    public void missingJobsValueFailsBeforeExecution() throws Exception {
        // 并发别名同样要求完整的参数值。
        Process process = start("first", "all", "--jobs");

        // 无效并发值不会交给实际 Gradle 或 Docker 才报错。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("--jobs requires an integer between 1 and 16"));
        assertNoExecution("first");
    }

    @Test
    public void optionsWithoutATestScopeCannotStartAFullRun() throws Exception {
        // 只指定并发不能隐式触发全部测试。
        Process process = start("first", "--groups", "4");

        // 用户必须显式声明 all、协议或 testcase。
        assertEquals(2, awaitExit(process));
        assertTrue(output("first").contains("Select all, rpc NAME, or a test pattern"));
        assertNoExecution("first");
    }

    @Test
    public void terminalInterruptKeepsClientAliveUntilCleanupLogsFinish() throws Exception {
        // 对整个入口进程组发送终端 INT，假 Gradle 在独立进程组中等待取消请求。
        this.environment.put("ENTRY_TEST_HOLD", "true");
        Process process = start("first", "all");
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
        Process process = start("first", "all");
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
        Process process = start("first", "all");
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
        Process process = start("first", "all");
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
        Process process = start("first", "all");
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
        Process process = start("first", "all");
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
        Process process = start("first", "all");
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
        Process process = start("first", "all", "--groups", "2");

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
        Process previous = start("first", "all");
        awaitFile("first.started");
        this.environment.remove("ENTRY_TEST_HOLD");
        Process current = start("second", "all");
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
        Process previous = start("first", "all");
        awaitFile("first.startup");
        Path pointer = this.repository.resolve("meteor-test/build/meteor-test-control/docker.control");
        List<String> owner = Files.readAllLines(pointer);
        assertEquals(Long.toString(previous.pid()), owner.get(1));
        assertFalse(Files.exists(this.repository.resolve("first.control")));
        assertFalse(Files.exists(this.repository.resolve("first.arguments")));

        // 新入口根据 wrapper 提前登记的 pointer 请求取消，并等待启动阶段清理完成。
        this.environment.remove("ENTRY_TEST_STARTUP_HOLD");
        Process current = start("second", "all");
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
        Process previous = start("first", "all");
        awaitFile("first.started");
        Files.writeString(control("first").resolve("cancel.request"), "INT\n");
        awaitFile("first.cancelled");
        Files.createFile(this.repository.resolve("first.release"));
        awaitFile("restore.started");

        // 新入口仍能从 pointer 找到外层 wrapper，在校时恢复屏障前不得启动客户端。
        this.environment.remove("ENTRY_TEST_HOLD");
        this.environment.remove("ENTRY_TEST_RESTORE_HOLD");
        Process current = start("second", "all");
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
        Process process = start("first", "all");
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
        Process current = start("first", "all");

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
        Process current = start("first", "all");

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
        assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Entry did not finish");
        return process.exitValue();
    }

    private List<String> arguments(String name) throws Exception {
        return Files.readAllLines(this.repository.resolve(name + ".arguments"));
    }

    private void assertNoExecution(String name) throws Exception {
        assertFalse(Files.exists(this.repository.resolve(name + ".arguments")));
        assertFalse(Files.exists(this.repository.resolve(name + ".control")));
        assertFalse(Files.exists(this.repository.resolve("meteor-test/build/meteor-test-control")));
        assertFalse(output(name).contains("[fake-clock]"));
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
        assertTrue(condition.getAsBoolean(), "Expected entry state was not observed");
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
