package net.hasor.meteor.test.nxn;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class NxnRunnerTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void groupExecutionBorrowsOnlyExistingContainer() throws Exception {
        // 调度器只能进入 Compose 已经创建的 Java 容器。
        String[] arguments = { "--run-id", "run", "--container", "compose-java" };
        NxnPlan.Options options = NxnPlan.parseArguments(arguments);
        Map<String, Object> group = Map.of("id", "g1", "outputDirectory", "/g1",
                "launchFile", "/g1/process/launch.json");
        List<String> command = NxnRunner.groupCommand(group, options);

        // 启动参数不包含容器创建或删除操作。
        assertEquals("exec", command.get(1));
        assertTrue(command.contains("compose-java"));
        assertFalse(command.contains("create"));
        assertFalse(command.contains("rm"));
    }

    @Test
    public void successRequiresRealJvmExitEvidence() throws Exception {
        // 退出成功由 JVM 执行器证据确认。
        Map<String, Object> group = execute(status("run-g1-junit"), 0);
        assertEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void missingJvmEvidenceFailsSuccessfulExec() throws Exception {
        // Docker exec 成功不能代替被执行 JVM 的终态。
        Map<String, Object> group = execute(null, 0);
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void jvmFailureSurvivesSuccessfulExecExit() throws Exception {
        // 传输客户端成功退出不能覆盖 JVM 的业务退出码。
        Map<String, Object> status = status("run-g1-junit");
        status.put("exitCode", 17);
        Map<String, Object> group = execute(status, 0);
        assertEquals(17, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void exitEvidenceFromAnotherRunIsRejected() throws Exception {
        // 遗留运行的终态不能让本轮通过。
        Map<String, Object> status = status("run-g1-junit");
        status.put("runId", "old");
        Map<String, Object> group = execute(status, 0);
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void exitEvidenceFromAnotherProcessIsRejected() throws Exception {
        // 同一组内其它进程的终态不能冒充 JUnit JVM。
        Map<String, Object> group = execute(status("other-process"), 0);
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void exitEvidenceFromAnotherGroupIsRejected() throws Exception {
        // 同一轮中其它工作组的终态不能让本组通过。
        Map<String, Object> status = status("run-g1-junit");
        status.put("groupId", "g2");
        Map<String, Object> group = execute(status, 0);
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void nonIntegerJvmExitEvidenceIsRejected() throws Exception {
        // 退出码必须是执行器记录的整数，不能从字符串小数猜测。
        Map<String, Object> status = status("run-g1-junit");
        status.put("exitCode", "0.5");
        Map<String, Object> group = execute(status, 0);
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void jsonSnapshotCannotReplaceMissingNativeExitEvidence() throws Exception {
        // 物化 JSON 只是报告快照，缺少执行器终态时不得用于验收。
        Path directory = this.temporary.newFolder("json-only").toPath();
        Map<String, Object> group = group(directory);
        NxnPlan.writeJson(directory.resolve("process/status.json"), status("run-g1-junit"));
        NxnPlan.Options options = fakeOptions(directory, "exit 0\n");

        // 旧快照的成功不能覆盖本次真实执行证据缺失。
        NxnRunner.executeGroup(group, options, new AtomicBoolean(false), new AtomicBoolean(false));
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void survivingJvmProcessGroupFailsCleanup() throws Exception {
        // JVM 已退出但仍有子进程时整组失败。
        Map<String, Object> status = status("run-g1-junit");
        status.put("processGroupEmpty", false);
        status.put("remainingPids", List.of(45));
        Map<String, Object> group = execute(status, 0);
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void unremovedTemporaryDirectoryFailsCleanup() throws Exception {
        // 临时 HOME 或缓存目录未释放属于生命周期失败。
        Map<String, Object> status = status("run-g1-junit");
        status.put("temporaryDirectoryRemoved", false);
        Map<String, Object> group = execute(status, 0);
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test
    public void failedExecTransportHasSeparateExitCode() throws Exception {
        // Docker exec 链路失败与 JVM 真实终态分别记录。
        Map<String, Object> group = execute(status("run-g1-junit"), 137);
        assertEquals(137, ((Number) group.get("attachExitCode")).intValue());
        assertNotEquals(0, ((Number) group.get("exitCode")).intValue());
    }

    @Test(timeout = 30000)
    public void failedGroupDoesNotStopOtherGroupsByDefault() throws Exception {
        // 两组均已开始；先让第二组返回真实失败，第一组继续持有自己的程序。
        Path directory = this.temporary.newFolder("continue-failed-group").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path groups = directory.resolve("build/groups");
            Map<String, Object> failed = status("run-g2-junit");
            failed.put("groupId", "g2");
            failed.put("exitCode", 17);
            writeNativeStatus(groups.resolve("g2/process/status.native"), failed);
            Files.writeString(groups.resolve("g2/process/released"), "");
            awaitText(directory.resolve("runner-cli.log"), "Finished g2: exit 17", 5);

            // 默认策略不发送组间取消，保留失败并允许其余组自然结束。
            long firstPid = Long.parseLong(Files.readString(groups.resolve("g1/process/attach.pid")));
            assertTrue(ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false));
            List<String> controls = Files.readAllLines(directory.resolve("docker-calls.log"));
            assertFalse(controls.stream().anyMatch(line -> line.startsWith("--stop ")));
            Files.copy(directory.resolve("finished-g1.native"), groups.resolve("g1/process/status.native"));
            Files.writeString(groups.resolve("g1/process/released"), "");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(1, runner.exitValue());
            assertEquals(Boolean.FALSE, finishedState(directory).get("interrupted"));
            Map<String, Object> first = NxnPlan.readObject(groups.resolve("g1/group.json"));
            assertEquals(0, ((Number) first.get("exitCode")).intValue());
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void groupEvidenceExceptionDoesNotCancelOtherGroupsByDefault() throws Exception {
        // 第一组已执行完毕后，故意阻止它覆盖 group.json，触发 Future 的异常终态。
        Path directory = this.temporary.newFolder("continue-group-exception").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path groups = directory.resolve("build/groups");
            Path evidence = groups.resolve("g1/group.json");
            Files.delete(evidence);
            Files.createDirectory(evidence);
            Files.copy(directory.resolve("finished-g1.native"), groups.resolve("g1/process/status.native"));
            Files.writeString(groups.resolve("g1/process/released"), "");
            awaitText(directory.resolve("runner-cli.log"), "Cannot preserve group execution evidence", 5);

            // 编排异常进入最终错误列表，不把仍在运行的第二组视作用户取消。
            long secondPid = Long.parseLong(Files.readString(groups.resolve("g2/process/attach.pid")));
            assertTrue(ProcessHandle.of(secondPid).map(ProcessHandle::isAlive).orElse(false));
            Files.copy(directory.resolve("finished-g2.native"), groups.resolve("g2/process/status.native"));
            Files.writeString(groups.resolve("g2/process/released"), "");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(1, runner.exitValue());
            assertEquals(Boolean.FALSE, finishedState(directory).get("interrupted"));
            Map<String, Object> manifest = NxnPlan.readObject(groups.resolve("manifest.json"));
            assertFalse(strings(manifest.get("orchestrationErrors")).isEmpty());
            Map<String, Object> second = NxnPlan.readObject(groups.resolve("g2/group.json"));
            assertEquals(0, ((Number) second.get("exitCode")).intValue());
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void disabledSkipErrorStopsOtherGroupsWithoutReportingUserCancellation() throws Exception {
        // 调度线程正在等待第一组时，第二组先失败，快速停止仍应立即生效。
        Path directory = this.temporary.newFolder("stop-after-failed-group").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory, "--skipError", "false");
        try {
            awaitWorkers(directory);
            Path groups = directory.resolve("build/groups");
            Map<String, Object> failed = status("run-g2-junit");
            failed.put("groupId", "g2");
            failed.put("exitCode", 17);
            writeNativeStatus(groups.resolve("g2/process/status.native"), failed);
            Files.writeString(groups.resolve("g2/process/released"), "");

            // 当前组由自己的执行器回收；整轮是测试失败，不是 130 用户取消。
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(1, runner.exitValue());
            Map<String, Object> state = finishedState(directory);
            assertEquals(Boolean.FALSE, state.get("interrupted"));
            assertEquals(Boolean.TRUE, state.get("failureStopped"));
            Map<String, Object> first = NxnPlan.readObject(groups.resolve("g1/group.json"));
            assertEquals(Boolean.TRUE, first.get("failureStopped"));
            assertWorkersReleased(directory);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test
    public void failedGroupCanStopAnUnstartedGroupWithoutMarkingItInterrupted() throws Exception {
        // 快速失败信号在启动之前到达，仍执行本组的远端进程检查。
        Path directory = this.temporary.newFolder("failure-before-startup").toPath();
        Map<String, Object> group = group(directory);
        NxnPlan.Options options = fakeOptions(directory, "exit 0\n");
        options.skipError = false;
        AtomicBoolean interrupted = new AtomicBoolean();

        // 没有启动 JUnit JVM，也没有伪造用户中断退出码。
        NxnRunner.executeGroup(group, options, interrupted, new AtomicBoolean(true));
        assertEquals(1, ((Number) group.get("exitCode")).intValue());
        assertEquals(Boolean.TRUE, group.get("failureStopped"));
        assertFalse(interrupted.get());
        assertEquals(Boolean.TRUE, object(group.get("remoteCleanup")).get("successful"));
    }

    @Test
    public void interruptedStartupStillChecksRemoteProcesses() throws Exception {
        // 本地工作组启动前取消，仍然检查它已经登记的远端对端。
        Path directory = this.temporary.newFolder("interrupted").toPath();
        Map<String, Object> group = group(directory);
        peerFixture(directory, status("peer-1"));
        NxnPlan.Options options = fakeOptions(directory, "exit 4\n");

        // 本地取消失败也不截断远端生命周期门禁。
        NxnRunner.executeGroup(group, options, new AtomicBoolean(true), new AtomicBoolean(false));
        Map<String, Object> cleanup = object(group.get("remoteCleanup"));
        assertEquals(1, ((Number) cleanup.get("checked")).intValue());
        assertEquals(Boolean.TRUE, cleanup.get("successful"));
    }

    @Test
    public void failedActiveCancellationStillChecksRemotePeers() throws Exception {
        // 模拟正在运行的 Docker exec，控制链路失败但本场景远端证据仍可审核。
        Path directory = this.temporary.newFolder("active-interruption").toPath();
        Map<String, Object> group = group(directory);
        peerFixture(directory, status("peer-1"));
        Path started = directory.resolve("attach.pid");
        String runAction = "printf '%s' \"$$\" > " + quote(started) + "; while :; do sleep 1; done";
        String action = "case \" $* \" in *\" --run \"*) " + runAction + ";; *) exit 4;; esac\n";
        NxnPlan.Options options = fakeOptions(directory, action);
        AtomicBoolean interrupted = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> execution = executor.submit(() -> {
                return NxnRunner.executeGroup(group, options, interrupted, new AtomicBoolean(false));
            });
            awaitFile(started);

            // 真正发出取消，并等待失败控制之后的强制本地回收和远端清理。
            interrupted.set(true);
            execution.get(20, TimeUnit.SECONDS);
            Map<String, Object> cleanup = object(group.get("remoteCleanup"));
            assertEquals(1, ((Number) cleanup.get("checked")).intValue());
            assertEquals(Boolean.TRUE, cleanup.get("successful"));
            assertEquals(130, ((Number) group.get("exitCode")).intValue());

            // 只回收当前 exec 子进程，失败控制的诊断保留在本组。
            long processId = Long.parseLong(Files.readString(started));
            assertFalse(ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false));
            List<String> controls = Files.readAllLines(directory.resolve("docker.args"));
            assertTrue(controls.contains("--force"));
            assertFalse(strings(group.get("cleanupFailures")).isEmpty());
        } finally {
            interrupted.set(true);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(25, TimeUnit.SECONDS));
        }
    }

    @Test
    public void groupWithoutRemotePeersHasExplicitCleanGate() throws Exception {
        // 即使没有远端程序，也保存明确的清理结果。
        Path directory = this.temporary.newFolder("empty").toPath();
        NxnPlan.Options options = fakeOptions(directory, "exit 9\n");
        Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);
        assertEquals(0, ((Number) result.get("checked")).intValue());
        assertEquals(0, ((Number) result.get("stopped")).intValue());
        assertEquals(Boolean.TRUE, result.get("successful"));
        assertEquals(List.of(), result.get("processes"));
        assertEquals(List.of(), result.get("failures"));
    }

    @Test
    public void alreadyCleanPeerDoesNotExecuteDockerControl() throws Exception {
        // 对端已正常释放时不再发出取消命令。
        Path directory = this.temporary.newFolder("clean").toPath();
        peerFixture(directory, status("peer-1"));
        NxnPlan.Options options = fakeOptions(directory, "exit 9\n");
        Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);

        // 没有调用 Docker，且清理门禁通过。
        assertFalse(Files.exists(directory.resolve("docker.args")));
        assertEquals(Boolean.TRUE, result.get("successful"));
    }

    @Test
    public void expectedNegativePeerExitIsNotCleanupFailure() throws Exception {
        // 反例程序可以非零退出，只要进程树和临时目录已经释放。
        Path directory = this.temporary.newFolder("negative").toPath();
        Map<String, Object> status = status("peer-1");
        status.put("exitCode", 7);
        peerFixture(directory, status);
        NxnPlan.Options options = fakeOptions(directory, "exit 9\n");
        Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);
        assertEquals(Boolean.TRUE, result.get("successful"));
    }

    @Test
    public void runningPeerStopsInItsActualLanguageContainer() throws Exception {
        // 对端元数据决定控制命令使用哪个语言容器。
        Path directory = this.temporary.newFolder("language").toPath();
        Path peer = peerFixture(directory, runningStatus());
        NxnPlan.Options options = stoppingOptions(directory, peer, false);
        NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);

        // Python 对端不会被错误地发到 Java 容器执行清理。
        List<String> arguments = Files.readAllLines(directory.resolve("docker.args"));
        assertEquals("exec", arguments.get(0));
        assertEquals("compose-python", arguments.get(1));
    }

    @Test
    public void reclaimedLeakRemainsFailedLifecycle() throws Exception {
        // 已泄漏的对端即便补救释放，也必须保留本轮失败。
        Path directory = this.temporary.newFolder("reclaimed").toPath();
        Path peer = peerFixture(directory, runningStatus());
        NxnPlan.Options options = stoppingOptions(directory, peer, false);
        Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);
        assertEquals(Boolean.FALSE, result.get("successful"));
        assertEquals(1, ((Number) result.get("stopped")).intValue());
    }

    @Test
    public void uncleanGracefulStopEscalatesToForce() throws Exception {
        // 普通取消未释放进程时，继续发出强制回收。
        Path directory = this.temporary.newFolder("force").toPath();
        Path peer = peerFixture(directory, runningStatus());
        NxnPlan.Options options = stoppingOptions(directory, peer, true);
        NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);

        // 同一对端完成普通与强制两次有界控制。
        List<String> arguments = Files.readAllLines(directory.resolve("docker.args"));
        assertTrue(arguments.contains("--force"));
        long controls = arguments.stream().filter(argument -> StringUtils.equals(argument, "exec")).count();
        assertEquals(2, controls);
    }

    @Test
    public void missingStatusStillReclaimsOwnedRemotePeer() throws Exception {
        // 已登记且身份完整的对端，即便没有终态仍要回收。
        Path directory = this.temporary.newFolder("missing-status").toPath();
        Path peer = peerFixture(directory, null);
        NxnPlan.Options options = stoppingOptions(directory, peer, false);
        NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);

        // 缺失状态不会跳过取消步骤。
        List<String> arguments = Files.readAllLines(directory.resolve("docker.args"));
        long controls = arguments.stream().filter(argument -> StringUtils.equals(argument, "exec")).count();
        assertEquals(1, controls);
    }

    @Test
    public void foreignGroupRecordCannotStopAnotherGroupsPeer() throws Exception {
        // 启动记录和所属组不一致时禁止发出控制命令。
        Path directory = this.temporary.newFolder("foreign").toPath();
        Path peer = peerFixture(directory, runningStatus());
        Path launchFile = peer.resolve("launch.json");
        Map<String, Object> launch = NxnPlan.readObject(launchFile);
        launch.put("groupId", "other");
        NxnPlan.writeJson(launchFile, launch);
        NxnPlan.Options options = fakeOptions(directory, "exit 9\n");

        // 不碰其它组的进程，同时保留身份错误。
        Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);
        assertFalse(Files.exists(directory.resolve("docker.args")));
        assertEquals(Boolean.FALSE, result.get("successful"));
    }

    @Test
    public void corruptOriginalStatusRemainsFailureAfterReclaim() throws Exception {
        // 原始损坏状态必须保留诊断，不能被后来成功回收覆盖。
        Path directory = this.temporary.newFolder("corrupt-status").toPath();
        Path peer = peerFixture(directory, null);
        Files.writeString(peer.resolve("status.native"), "{");
        NxnPlan.Options options = stoppingOptions(directory, peer, false);
        Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);

        // 回收后的有效终态不改变原始读取失败。
        List<String> failures = strings(result.get("failures"));
        assertTrue(failures.get(0).contains("Cannot read original remote status"));
        assertEquals(Boolean.FALSE, result.get("successful"));
    }

    @Test
    public void remoteWrapperCleanupErrorFailsGate() throws Exception {
        // 执行器报告的清理错误独立于进程是否已退出。
        Path directory = this.temporary.newFolder("wrapper-error").toPath();
        Map<String, Object> status = status("peer-1");
        status.put("state", "failed");
        status.put("cleanupFailure", "pipe did not close");
        peerFixture(directory, status);
        NxnPlan.Options options = fakeOptions(directory, "exit 9\n");
        Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);
        assertEquals(Boolean.FALSE, result.get("successful"));
    }

    @Test
    public void truncatedMetadataUsesDurableStartEventToReclaimPeer() throws Exception {
        // JVM 中断时的半条快照可以从已落盘启动事件恢复身份。
        Path directory = this.temporary.newFolder("truncated").toPath();
        Path peer = peerFixture(directory, runningStatus());
        Path metadata = peer.getParent().resolve("peer-1.json");
        String event = Files.readString(metadata).replace("\n", "");
        Files.writeString(peer.getParent().resolve("peers.jsonl"), event + "\n{");
        Files.writeString(metadata, "{");
        NxnPlan.Options options = stoppingOptions(directory, peer, false);

        // 尾部半条事件被忽略，完整事件仍指向真实语言容器。
        NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);
        List<String> arguments = Files.readAllLines(directory.resolve("docker.args"));
        assertEquals("compose-python", arguments.get(1));
    }

    @Test
    public void runCancellationStopsAllWorkersBeforePublishingFinishedState() throws Exception {
        // 独立 JVM 运行两组阻塞任务，外层只通过同轮次取消文件控制它。
        Path directory = this.temporary.newFolder("run-cancel").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            requestRunCancellation(directory, "run");

            // 终态只能出现在全部工作组回收和报告处理之后。
            assertTrue(runner.waitFor(20, TimeUnit.SECONDS));
            assertEquals(130, runner.exitValue());
            Map<String, Object> state = finishedState(directory);
            assertEquals(Boolean.TRUE, state.get("interrupted"));
            assertEquals(130, ((Number) state.get("exitCode")).intValue());
            Map<String, Object> manifest = NxnPlan.readObject(directory.resolve("build/groups/manifest.json"));
            assertEquals(Boolean.TRUE, manifest.get("interrupted"));
            assertTrue(Files.exists(directory.resolve("build/groups/completed")));
            assertWorkersReleased(directory);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 45000)
    public void silentActiveGroupHasPeriodicCaseAndCompletionProgress() throws Exception {
        // 两组独立工作，一组已结束，另一组长时间停留在同一个 Unicode 用例中。
        Path directory = this.temporary.newFolder("periodic-progress").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path groups = directory.resolve("build/groups");
            Map<String, Object> active = testProgress("g1");
            active.put("completed", 3);
            active.put("total", 4);
            active.put("current", "suite.类型Test#调用[中文]");
            active.put("testElapsedMillis", 75000);
            Files.writeString(groups.resolve("g1/progress.jsonl"), jsonLine(active));
            Map<String, Object> finished = testProgress("g2");
            finished.put("completed", 2);
            finished.put("total", 2);
            finished.put("phase", "cleanup");
            Files.writeString(groups.resolve("g2/progress.jsonl"), jsonLine(finished));
            Files.copy(directory.resolve("finished-g2.native"), groups.resolve("g2/process/status.native"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(groups.resolve("g2/process/released"), "");

            // 无新增业务输出时仍出现十五秒心跳，已完成组不再列入活跃详情。
            Path console = directory.resolve("runner-cli.log");
            String detail = "g1: testing suite.类型Test#调用[中文]; cases 3/4; current 1m";
            awaitText(console, detail, 20);
            String output = Files.readString(console);
            assertTrue(output, output.contains("Progress: groups 1/2 finished"));
            assertTrue(output, output.contains("cases 5 completed; failed 0; skipped 0"));
            assertFalse(output.contains("  g2:"));
            assertFalse(output.contains("[g1][stdout] [JUnit progress]"));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void partialFailureLineIsPublishedOnceAfterTheNewlineArrives() throws Exception {
        // 原生执行器尚未写完一行时，不能提前解析或丢失该失败事件。
        Path directory = this.temporary.newFolder("partial-progress").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Map<String, Object> progress = testProgress("g1");
            progress.put("failed", 1);
            progress.put("case", "suite.类型Test#调用[中文]");
            progress.put("failure", "AssertionError: 第一行\n第二行");
            String line = jsonLine(progress);
            Path log = directory.resolve("build/groups/g1/progress.jsonl");
            Files.writeString(log, line.substring(0, line.length() - 1));
            Path console = directory.resolve("runner-cli.log");

            // 观察至少一个读取周期，证明缺少换行时不会发布这个完整但尚未提交的 JSON。
            Thread.sleep(1250);
            assertFalse(Files.readString(console).contains("[FAIL]"));
            Files.writeString(log, "\n", StandardOpenOption.APPEND);
            String expected = "[FAIL] g1 suite.类型Test#调用[中文]: AssertionError: 第一行 第二行";
            awaitText(console, expected, 5);

            // 失败无需等待十五秒心跳；再次轮询和最终收尾也不能重复输出。
            requestRunCancellation(directory, "run");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(130, runner.exitValue());
            long failures = Files.readAllLines(console).stream().filter(value -> value.startsWith("[FAIL]")).count();
            assertEquals(1, failures);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void malformedProgressIsReportedOnceWithoutChangingCancellation() throws Exception {
        // 损坏的展示事件不能变成业务失败或取消握手错误。
        Path directory = this.temporary.newFolder("invalid-progress").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path log = directory.resolve("build/groups/g1/progress.jsonl");
            Files.writeString(log, "{\n{\n");
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "Cannot read progress for g1:", 5);

            // 正式取消链路仍能完整收尾，进度诊断每组最多提示一次。
            requestRunCancellation(directory, "run");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(130, runner.exitValue());
            String output = Files.readString(console);
            assertFalse(output.contains("[FAIL]"));
            long warnings = output.lines().filter(line -> line.startsWith("Cannot read progress for g1:")).count();
            assertEquals(1, warnings);
            assertEquals(Boolean.TRUE, finishedState(directory).get("cleanupComplete"));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void progressFromAnotherRunCannotPublishFailureOrCounts() throws Exception {
        // 本轮与其它运行的事件写在同一日志时，只接受本轮标识和分组。
        Path directory = this.temporary.newFolder("foreign-progress").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Map<String, Object> foreign = testProgress("g1");
            foreign.put("runId", "other-run");
            foreign.put("completed", 100);
            foreign.put("failure", "must not be published");
            Map<String, Object> current = testProgress("g1");
            current.put("completed", 1);
            current.put("phase", "cleanup");
            String events = jsonLine(foreign) + jsonLine(current);
            Files.writeString(directory.resolve("build/groups/g1/progress.jsonl"), events);

            // 最终增量读取仍按身份过滤，不改变正式取消和清理的结果。
            requestRunCancellation(directory, "run");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(130, runner.exitValue());
            String output = Files.readString(directory.resolve("runner-cli.log"));
            assertTrue(output.contains("cases 1 completed"));
            assertFalse(output.contains("[FAIL]"));
            assertFalse(output.contains("Cannot read progress"));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void ordinaryCarriageReturnOutputDoesNotBlockLaterProgress() throws Exception {
        // 业务进度条使用单独的回车时，后续完整 JUnit 事件仍必须被读取。
        Path directory = this.temporary.newFolder("carriage-return-progress").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Map<String, Object> failure = testProgress("g1");
            failure.put("failure", "AssertionError: after carriage return");
            failure.put("case", "suite.Test#afterProgressBar");
            Path log = directory.resolve("build/groups/g1/runner.log");
            Files.writeString(log, "loading\rnext\n");
            Files.writeString(directory.resolve("build/groups/g1/progress.jsonl"), jsonLine(failure));

            // 回车前后的普通输出均实时转发，失败事件无需等到工作组结束。
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "[FAIL] g1 suite.Test#afterProgressBar:", 5);
            String output = Files.readString(console);
            assertTrue(output.contains("[g1][stdout] loading"));
            assertTrue(output.contains("[g1][stdout] next"));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void runningGroupsForwardBothStandardStreamsWithTheirIdentity() throws Exception {
        // 两组仍在运行时分别产生正常输出、JUnit 用例名称和多行异常堆栈。
        Path directory = this.temporary.newFolder("live-standard-streams").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path groups = directory.resolve("build/groups");
            String stdout = "订单已保存\n[JUnit] suite.OrderTest#find\n";
            Files.writeString(groups.resolve("g1/runner.log"), stdout);
            Files.writeString(groups.resolve("g2/runner.log"), "consumer connected\n");
            String stderr = "java.lang.IllegalStateException: 调用失败\n\tat suite.OrderTest.find(OrderTest.java:10)\n";
            Files.writeString(groups.resolve("g1/runner-stderr.log"), stderr);

            // 无需等待摘要或工作组结束，终端已经能定位到分组和原始输出流。
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "[g2][stdout] consumer connected", 5);
            awaitText(console, "[g1][stderr] \tat suite.OrderTest.find(OrderTest.java:10)", 5);
            String output = Files.readString(console);
            assertTrue(runner.isAlive());
            assertTrue(output.contains("[g1][stdout] 订单已保存"));
            assertTrue(output.contains("[g1][stdout] [JUnit] suite.OrderTest#find"));
            assertTrue(output.contains("[g1][stderr] java.lang.IllegalStateException: 调用失败"));
            assertFalse(output.contains("[g2][stdout] 订单已保存"));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void progressLookingBusinessOutputIsNeverConsumedAsTelemetry() throws Exception {
        // 原来的内部前缀现在也是普通业务输出，不再具有隐藏或修改进度的含义。
        Path directory = this.temporary.newFolder("business-progress-prefix").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            String text = "[JUnit progress] {not telemetry}";
            Path log = directory.resolve("build/groups/g1/runner.log");
            Files.writeString(log, text + "\n");

            // 运行中的控制台完整展示原文，也不会尝试解析这段业务内容。
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "[g1][stdout] " + text, 5);
            assertFalse(Files.readString(console).contains("Cannot read progress"));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void registeredPeersForwardBothStandardStreamsWhileGroupIsRunning() throws Exception {
        // 使用真实登记事件找到独立 SDK 进程，不扫描整个进程归档目录。
        Path directory = this.temporary.newFolder("live-peer-streams").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path group = directory.resolve("build/groups/g1");
            Path peer = peerFixture(group, status("peer-1"));
            Files.writeString(peer.resolve("stdout.log"), "SDK 服务已启动🚀\n");
            Files.writeString(peer.resolve("stderr.log"), "java.lang.IllegalStateException: SDK 错误\n");
            Map<String, Object> metadata = NxnPlan.readObject(peer.getParent().resolve("peer-1.json"));
            Files.writeString(peer.getParent().resolve("peers.jsonl"), jsonLine(metadata));

            // SDK 的输出与异常立即显示，分组和进程标识可以定位各自来源。
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "[g1][peer-1][stdout] SDK 服务已启动🚀", 5);
            awaitText(console, "[g1][peer-1][stderr] java.lang.IllegalStateException: SDK 错误", 5);
            assertTrue(runner.isAlive());
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void finishedPeerDrainsUnterminatedTailOnceBeforeGroupFinishes() throws Exception {
        // 登记事件可以在一次轮询前同时包含启动与结束，结束时仍须排空无换行尾部。
        Path directory = this.temporary.newFolder("finished-peer-tail").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path group = directory.resolve("build/groups/g1");
            Path peer = peerFixture(group, status("peer-1"));
            Files.writeString(peer.resolve("stdout.log"), "SDK 最后输出🚀");
            Files.writeString(peer.resolve("stderr.log"), "SDK 最后诊断");
            Map<String, Object> metadata = NxnPlan.readObject(peer.getParent().resolve("peer-1.json"));
            String started = jsonLine(metadata);
            metadata.put("finishedAt", "finished");
            Path events = peer.getParent().resolve("peers.jsonl");
            Files.writeString(events, started + jsonLine(metadata));

            // 已完成进程退出活跃集合，重复终态事件与最终分组收尾都不重播日志。
            Path console = directory.resolve("runner-cli.log");
            String expected = "[g1][peer-1][stdout] SDK 最后输出🚀";
            awaitText(console, expected, 5);
            awaitText(console, "[g1][peer-1][stderr] SDK 最后诊断", 5);
            Files.writeString(events, jsonLine(metadata), StandardOpenOption.APPEND);
            requestRunCancellation(directory, "run");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            List<String> output = Files.readAllLines(console);
            long copies = output.stream().filter(line -> line.contains(expected)).count();
            assertEquals(1, copies);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void anotherRunsPeerEventCannotExposeItsOutput() throws Exception {
        // 其它运行的进程登记不能借用当前分组的日志出口。
        Path directory = this.temporary.newFolder("foreign-peer-streams").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path group = directory.resolve("build/groups/g1");
            Path peer = peerFixture(group, status("peer-1"));
            Files.writeString(peer.resolve("stdout.log"), "foreign SDK output\n");
            Map<String, Object> metadata = NxnPlan.readObject(peer.getParent().resolve("peer-1.json"));
            metadata.put("runId", "another-run");
            Files.writeString(peer.getParent().resolve("peers.jsonl"), jsonLine(metadata));

            // 本轮完成完整的取消与清理后，仍没有输出属于其它运行的内容。
            requestRunCancellation(directory, "run");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(130, runner.exitValue());
            assertFalse(Files.readString(directory.resolve("runner-cli.log")).contains("foreign SDK output"));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test
    public void failedRemoteControlPrintsOriginalCommandDiagnostic() throws Exception {
        // 远端取消失败时，包装器输出不能随着临时控制日志一起被删除。
        Path directory = this.temporary.newFolder("remote-control-diagnostic").toPath();
        peerFixture(directory, runningStatus());
        NxnPlan.Options options = fakeOptions(directory, "printf '%s' 'native cancellation rejected' >&2\nexit 7\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.err;
        try (PrintStream captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
            System.setErr(captured);
            try {
                Map<String, Object> result = NxnRunner.cleanupRemotePeers(cleanupGroup(directory), options);

                // 清理判定仍失败，控制台同时包含原始命令错误和进程身份。
                assertEquals(Boolean.FALSE, result.get("successful"));
                String expected = "[g1][peer-1][control] native cancellation rejected";
                assertTrue(output.toString(StandardCharsets.UTF_8).contains(expected));
            } finally {
                System.setErr(original);
            }
        }
    }

    @Test(timeout = 30000)
    public void groupDriverFailurePrintsFullStackAndAttachedCommandOutput() throws Exception {
        // 模拟 attached exec 留下诊断后返回不属于本轮 JVM 的退出证据。
        Path directory = this.temporary.newFolder("driver-failure-output").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path group = directory.resolve("build/groups/g1");
            Files.writeString(group.resolve("runner.log"), "docker exec: diagnostic detail\n");
            writeNativeStatus(group.resolve("process/status.native"), status("another-process"));
            Files.writeString(group.resolve("process/released"), "");

            // 驱动异常同时包含原因、调用栈与原命令输出，不要求用户再打开归档。
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "java.lang.IllegalArgumentException: JVM exit evidence belongs to another run/group", 5);
            awaitText(console, "at net.hasor.meteor.test.nxn.NxnRunner.executeGroup", 5);
            awaitText(console, "[g1][stdout] docker exec: diagnostic detail", 5);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void splitUtf8OutputImmediatelyForwardsCompleteCharacters() throws Exception {
        // 一个 UTF-8 字符尚未写完时，先保留原字节和读取位置。
        Path directory = this.temporary.newFolder("split-live-unicode").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            byte[] message = "订单🚀".getBytes(StandardCharsets.UTF_8);
            Path log = directory.resolve("build/groups/g1/runner.log");
            Files.write(log, Arrays.copyOf(message, message.length - 1));
            Path console = directory.resolve("runner-cli.log");

            // 无换行时已完整的字符立即显示，拆开的字符等待后续字节且不重复前缀。
            awaitText(console, "[g1][stdout] 订单", 5);
            assertFalse(Files.readString(console).contains("�"));
            byte[] tail = { message[message.length - 1], '\n' };
            Files.write(log, tail, StandardOpenOption.APPEND);
            awaitText(console, "[g1][stdout] 订单🚀", 5);
            assertTrue(runner.isAlive());
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void splitCarriageReturnAndLineFeedDoNotInsertAnEmptyPrefix() throws Exception {
        // Windows 换行的回车与换行可能分别落在两次读取中。
        Path directory = this.temporary.newFolder("split-crlf-output").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path log = directory.resolve("build/groups/g1/runner.log");
            Files.writeString(log, "first\r");
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "[g1][stdout] first\r", 5);

            // 原样保留 CRLF，只在下一条正文前添加一次来源前缀。
            Files.writeString(log, "\nnext\n", StandardOpenOption.APPEND);
            awaitText(console, "[g1][stdout] first\r\n[g1][stdout] next\n", 5);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void closingGroupFlushesAnIncompleteUtf8TailOnce() throws Exception {
        // 进程退出前仅写出最后一个 UTF-8 字符的部分字节。
        Path directory = this.temporary.newFolder("incomplete-final-unicode").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path group = directory.resolve("build/groups/g1");
            byte[] text = "尾🚀".getBytes(StandardCharsets.UTF_8);
            Files.write(group.resolve("runner.log"), Arrays.copyOf(text, text.length - 1));
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "[g1][stdout] 尾", 5);
            assertFalse(Files.readString(console).contains("�"));

            // 成功退出后将截断字节显示为一个替代字符，不能一直留在未消费状态。
            Files.copy(directory.resolve("finished-g1.native"), group.resolve("process/status.native"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(group.resolve("process/released"), "");
            awaitText(console, "Finished g1: exit 0", 5);
            awaitText(console, "�", 5);
            long replacements = Files.readString(console).chars().filter(character -> character == '�').count();
            assertEquals(1, replacements);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void successfulGroupStillPublishesDockerCliDiagnostics() throws Exception {
        // Docker CLI 的诊断不一定伴随 JVM 失败，也不一定以换行结束。
        Path directory = this.temporary.newFolder("successful-cli-diagnostic").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path group = directory.resolve("build/groups/g1");
            Files.writeString(group.resolve("runner-stderr.log"), "docker exec: diagnostic without newline");
            Path console = directory.resolve("runner-cli.log");
            awaitText(console, "[g1][stderr] docker exec: diagnostic without newline", 5);

            // 原始诊断已经实时显示，工作组仍按真实 JVM 的成功证据结束。
            Files.copy(directory.resolve("finished-g1.native"), group.resolve("process/status.native"),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(group.resolve("process/released"), "");
            awaitText(console, "Finished g1: exit 0", 5);
            Map<String, Object> result = NxnPlan.readObject(group.resolve("group.json"));
            assertEquals(0, ((Number) result.get("exitCode")).intValue());
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test(timeout = 30000)
    public void unterminatedOutputIsPrintedOnceAcrossGroupCompletion() throws Exception {
        // 两个标准流都没有换行，仍须在工作组运行中显示完整内容。
        Path directory = this.temporary.newFolder("final-live-tail").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Path group = directory.resolve("build/groups/g1");
            Files.writeString(group.resolve("runner.log"), "最后输出🚀");
            Files.writeString(group.resolve("runner-stderr.log"), "最后诊断甲");
            Path console = directory.resolve("runner-cli.log");
            String stdout = "[g1][stdout] 最后输出🚀";
            String stderr = "[g1][stderr] 最后诊断甲";
            awaitText(console, stdout, 5);
            awaitText(console, stderr, 5);

            // 正式取消和清理结束后排空尾部，重复轮询不会再输出同一段内容。
            requestRunCancellation(directory, "run");
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(130, runner.exitValue());
            String output = Files.readString(console);
            assertEquals(output.indexOf(stdout), output.lastIndexOf(stdout));
            assertEquals(output.indexOf(stderr), output.lastIndexOf(stderr));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test
    public void cancellationForAnotherRunDoesNotStopCurrentWorkers() throws Exception {
        // 两个工作组已经开始后，写入其它运行的合法取消请求。
        Path directory = this.temporary.newFolder("foreign-cancel").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            requestRunCancellation(directory, "other-run");

            // 观察至少两个轮询周期，确认本轮进程和工作组仍在运行。
            Thread.sleep(750);
            assertTrue(runner.isAlive());
            List<String> controls = Files.readAllLines(directory.resolve("docker-calls.log"));
            assertFalse(controls.stream().anyMatch(line -> line.startsWith("--stop ")));

            // 最后由正确的运行标识释放本测试拥有的进程。
            requestRunCancellation(directory, "run");
            assertTrue(runner.waitFor(20, TimeUnit.SECONDS));
            assertEquals(130, runner.exitValue());
            finishedState(directory);
            assertWorkersReleased(directory);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test
    public void malformedRunCancellationFailsAndReleasesWorkers() throws Exception {
        // 运行中的取消文件被截断时，不能继续等待到测试自然结束。
        Path directory = this.temporary.newFolder("malformed-cancel").toPath();
        prepareRun(directory);
        Process runner = startRunner(directory);
        try {
            awaitWorkers(directory);
            Files.writeString(directory.resolve("build/groups.cancel.json"), "{");

            // 损坏请求被记录为编排错误，同时正常回收所有拥有的工作组。
            assertTrue(runner.waitFor(20, TimeUnit.SECONDS));
            assertNotEquals(0, runner.exitValue());
            Map<String, Object> state = finishedState(directory);
            assertEquals(Boolean.TRUE, state.get("interrupted"));
            Map<String, Object> manifest = NxnPlan.readObject(directory.resolve("build/groups/manifest.json"));
            assertFalse(strings(manifest.get("orchestrationErrors")).isEmpty());
            assertWorkersReleased(directory);
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test
    public void missingRuntimeStillPublishesFinishedState() throws Exception {
        // 在分组创建之前使输入读取失败。
        Path directory = this.temporary.newFolder("missing-runtime").toPath();
        prepareRun(directory);
        Files.delete(directory.resolve("runtime.json"));
        Process runner = startRunner(directory);
        try {
            // 外层能够看到明确终态，不会永远等待一个不会出现的 completed 文件。
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(1, runner.exitValue());
            Map<String, Object> state = finishedState(directory);
            assertEquals(1, ((Number) state.get("exitCode")).intValue());
            assertTrue(String.valueOf(state.get("error")).contains("runtime.json"));
            assertFalse(Files.exists(directory.resolve("docker-calls.log")));
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test
    public void reportFailureStillPublishesFinishedStateAfterWorkersStop() throws Exception {
        // 工作组正常退出后，使用同名文件阻止建立汇总 XML 目录。
        Path directory = this.temporary.newFolder("report-failure").toPath();
        prepareRun(directory);
        Files.writeString(directory.resolve("complete-immediately"), "");
        Files.createDirectories(directory.resolve("build"));
        Files.writeString(directory.resolve("build/test-results"), "blocked");
        Process runner = startRunner(directory);
        try {
            // 报告异常仍须写出失败终态，且终态声明的清理已经完成。
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(1, runner.exitValue());
            Map<String, Object> state = finishedState(directory);
            assertEquals(1, ((Number) state.get("exitCode")).intValue());
            assertTrue(String.valueOf(state.get("error")).contains("test-results"));
            for (int index = 1; index <= 2; index++) {
                Path groupFile = directory.resolve("build/groups/g" + index + "/group.json");
                Map<String, Object> group = NxnPlan.readObject(groupFile);
                assertEquals(0, ((Number) group.get("exitCode")).intValue());
            }
        } finally {
            closeRunner(runner, directory);
        }
    }

    @Test
    public void previousAggregateIsReplacedBeforeCurrentResultsAreReported() throws Exception {
        // 旧汇总留在固定目录，本轮只能基于当前工作组重新生成报告。
        Path directory = this.temporary.newFolder("replace-report").toPath();
        prepareRun(directory);
        Files.writeString(directory.resolve("complete-immediately"), "");
        Path report = Files.createDirectories(directory.resolve("build/interoperability"));
        Files.writeString(report.resolve("summary.json"), "old-summary");
        Files.writeString(report.resolve("old.xml"), "old-xml");
        Process runner = startRunner(directory);
        try {
            // 夹具没有 JUnit 报告，不能因旧成功报告而将本轮标为通过。
            assertTrue(runner.waitFor(15, TimeUnit.SECONDS));
            assertEquals(1, runner.exitValue());
            finishedState(directory);
            assertFalse(Files.exists(report.resolve("old.xml")));
            Map<String, Object> summary = NxnPlan.readObject(report.resolve("summary.json"));
            assertEquals("run", summary.get("runId"));
            assertFalse(strings(summary.get("aggregationErrors")).isEmpty());
            try (var children = Files.list(report)) {
                assertFalse(children.anyMatch(path -> StringUtils.equals(path.getFileName().toString(), "run")));
            }
        } finally {
            closeRunner(runner, directory);
        }
    }

    private Map<String, Object> execute(Map<String, Object> status, int attachExit) throws Exception {
        Path directory = this.temporary.newFolder().toPath();
        Map<String, Object> group = group(directory);
        if (status != null) {
            writeNativeStatus(directory.resolve("process/status.native"), status);
        }
        NxnPlan.Options options = fakeOptions(directory, "exit " + attachExit + "\n");
        NxnRunner.executeGroup(group, options, new AtomicBoolean(false), new AtomicBoolean(false));
        return group;
    }

    private static Map<String, Object> group(Path directory) throws Exception {
        Path launchFile = directory.resolve("process/launch.json");
        Map<String, Object> launch = Map.of("processId", "run-g1-junit", "runId", "run", "groupId", "g1");
        NxnPlan.writeJson(launchFile, launch);
        Map<String, Object> group = cleanupGroup(directory);
        group.put("classes", List.of("test.One"));
        group.put("launchFile", launchFile.toString());
        return group;
    }

    private static Map<String, Object> cleanupGroup(Path directory) {
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("id", "g1");
        group.put("outputDirectory", directory.toString());
        return group;
    }

    private static Map<String, Object> status(String processId) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("processId", processId);
        status.put("runId", "run");
        status.put("groupId", "g1");
        status.put("state", "exited");
        status.put("exitCode", 0);
        status.put("processGroupEmpty", true);
        status.put("remainingPids", List.of());
        status.put("temporaryDirectoryRemoved", true);
        return status;
    }

    private static Map<String, Object> runningStatus() {
        Map<String, Object> status = status("peer-1");
        status.put("state", "running");
        status.put("processGroupEmpty", false);
        return status;
    }

    private static Path peerFixture(Path directory, Map<String, Object> status) throws Exception {
        Path evidence = directory.resolve("process-evidence");
        Path peer = evidence.resolve("peer-1");
        Map<String, Object> launch = Map.of("processId", "peer-1", "runId", "run", "groupId", "g1");
        NxnPlan.writeJson(peer.resolve("launch.json"), launch);
        Map<String, Object> metadata = new LinkedHashMap<>(launch);
        metadata.put("container", "compose-python");
        metadata.put("artifactDirectory", peer.toString());
        NxnPlan.writeJson(evidence.resolve("peer-1.json"), metadata);
        if (status != null) {
            writeNativeStatus(peer.resolve("status.native"), status);
        }
        return peer;
    }

    private static NxnPlan.Options stoppingOptions(Path directory, Path peer, boolean requireForce) throws Exception {
        Path finalStatus = directory.resolve("final-status.native");
        Map<String, Object> status = status("peer-1");
        status.put("exitCode", 143);
        writeNativeStatus(finalStatus, status);
        String copyStatus = "cp " + quote(finalStatus) + " " + quote(peer.resolve("status.native")) + "\n";
        String action = copyStatus;
        if (requireForce) {
            action = "case \" $* \" in *\" --force \"*) " + copyStatus + ";; esac\n";
        }
        return fakeOptions(directory, action + "exit 0\n");
    }

    private static NxnPlan.Options fakeOptions(Path directory, String action) throws Exception {
        // 只替换 Docker 可执行文件边界，保留真实 ProcessBuilder 与证据读取链路。
        Path fakeDocker = directory.resolve("fake-docker");
        Path arguments = directory.resolve("docker.args");
        String script = "#!/bin/sh\nprintf '%s\\n' \"$@\" >> " + quote(arguments) + "\n" + action;
        Files.writeString(fakeDocker, script, StandardCharsets.UTF_8);
        assertTrue(fakeDocker.toFile().setExecutable(true));
        NxnPlan.Options options = NxnPlan.parseArguments(new String[] { "--run-id", "run" });
        options.dockerExecutable = fakeDocker.toString();
        return options;
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    private static void prepareRun(Path directory) throws Exception {
        // 使用真实 CLI 输入和原生终态，只替换容器命令的可执行文件边界。
        Map<String, Object> runtime = Map.of("classpath", List.of("/unused"),
                "classNames", List.of("test.One", "test.Two"));
        NxnPlan.writeJson(directory.resolve("runtime.json"), runtime);
        for (int index = 1; index <= 2; index++) {
            String groupId = "g" + index;
            Map<String, Object> status = status("run-" + groupId + "-junit");
            status.put("groupId", groupId);
            writeNativeStatus(directory.resolve("finished-" + groupId + ".native"), status);
            status.put("exitCode", 143);
            writeNativeStatus(directory.resolve("stopped-" + groupId + ".native"), status);
        }

        // 每个 fake exec 只等待自己的释放文件，取消控制不会碰其它工作组。
        Path bin = Files.createDirectory(directory.resolve("bin"));
        Path docker = bin.resolve("docker");
        String script = "#!/bin/sh\n"
                + "mode=; launch=; next=0\n"
                + "for argument in \"$@\"; do\n"
                + "  if [ \"$next\" = 1 ]; then launch=$argument; next=0; fi\n"
                + "  case \"$argument\" in --run|--stop) mode=$argument; next=1;; esac\n"
                + "done\n"
                + "process=${launch%/*}; parent=${process%/*}; group=${parent##*/}\n"
                + "printf '%s %s\\n' \"$mode\" \"$group\" >> " + quote(directory.resolve("docker-calls.log")) + "\n"
                + "if [ \"$mode\" = --run ]; then\n"
                + "  : > \"$process/stdout.log\"; : > \"$process/stderr.log\"\n"
                + "  if [ -f " + quote(directory.resolve("complete-immediately")) + " ]; then\n"
                + "    /bin/cp " + quote(directory) + "/finished-\"$group\".native \"$process/status.native\"\n"
                + "  else\n"
                + "    printf '%s' \"$$\" > \"$process/attach.pid\"\n"
                + "    while [ ! -f \"$process/released\" ]; do /bin/sleep 0.02; done\n"
                + "  fi\n"
                + "else\n"
                + "  /bin/cp " + quote(directory) + "/stopped-\"$group\".native \"$process/status.native\"\n"
                + "  : > \"$process/released\"\n"
                + "fi\nexit 0\n";
        Files.writeString(docker, script, StandardCharsets.UTF_8);
        assertTrue(docker.toFile().setExecutable(true));
    }

    private static Process startRunner(Path directory, String... arguments) throws Exception {
        // 显式定位生产类与三份 Jackson 依赖，兼容 Gradle worker 的短类路径启动方式。
        Set<String> locations = new LinkedHashSet<>();
        List<Class<?>> types = List.of(NxnMain.class, ObjectMapper.class, JsonFactory.class,
                JsonProperty.class, StringUtils.class);
        for (Class<?> type : types) {
            URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
            locations.add(Path.of(location).toString());
        }
        List<String> command = new ArrayList<>();
        // Java 启动器、类路径和 CLI 参数保持固定位置关系。
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(String.join(File.pathSeparator, locations));
        command.add(NxnMain.class.getName());
        command.add("run");
        command.addAll(Arrays.asList(arguments));
        command.add("--runtime");
        command.add(directory.resolve("runtime.json").toString());
        command.add("--output");
        command.add(directory.resolve("build/groups").toString());
        command.add("--build-directory");
        command.add(directory.resolve("build").toString());
        command.add("--weights");
        command.add(directory.resolve("missing-weights.json").toString());
        command.add("--groups");
        command.add("2");
        command.add("--run-id");
        command.add("run");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(directory.toFile());
        builder.environment().put("PATH", directory.resolve("bin").toString());
        builder.redirectErrorStream(true);
        builder.redirectOutput(directory.resolve("runner-cli.log").toFile());
        return builder.start();
    }

    private static void awaitWorkers(Path directory) throws Exception {
        for (int index = 1; index <= 2; index++) {
            awaitFile(directory.resolve("build/groups/g" + index + "/process/attach.pid"));
        }
    }

    private static void requestRunCancellation(Path directory, String runId) throws Exception {
        Map<String, Object> control = Map.of("schemaVersion", 1, "runId", runId);
        NxnPlan.writeJson(directory.resolve("build/groups.cancel.json"), control);
    }

    private static Map<String, Object> finishedState(Path directory) throws Exception {
        Path stateFile = directory.resolve("build/groups.runner-state.json");
        assertTrue("Runner state is missing", Files.exists(stateFile));
        Map<String, Object> state = NxnPlan.readObject(stateFile);
        assertEquals(1, ((Number) state.get("schemaVersion")).intValue());
        assertEquals("run", state.get("runId"));
        assertEquals("finished", state.get("state"));
        assertEquals(Boolean.TRUE, state.get("cleanupComplete"));
        return state;
    }

    private static void assertWorkersReleased(Path directory) throws Exception {
        for (int index = 1; index <= 2; index++) {
            Path process = directory.resolve("build/groups/g" + index + "/process");
            long pid = Long.parseLong(Files.readString(process.resolve("attach.pid")));
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            Map<String, Object> group = NxnPlan.readObject(process.getParent().resolve("group.json"));
            Map<String, Object> remote = object(group.get("remoteCleanup"));
            assertEquals(Boolean.TRUE, remote.get("successful"));
        }
    }

    private static void closeRunner(Process process, Path directory) throws Exception {
        try {
            if (process.isAlive()) {
                requestRunCancellation(directory, "run");
                if (!process.waitFor(20, TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroy);
                    process.destroy();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                        assertTrue(process.waitFor(5, TimeUnit.SECONDS));
                    }
                }
            }
        } finally {
            // 断言失败时仍释放夹具拥有的阻塞 shell，不依赖被测取消逻辑成功。
            for (int index = 1; index <= 2; index++) {
                Path processDirectory = directory.resolve("build/groups/g" + index + "/process");
                if (Files.isDirectory(processDirectory)) {
                    Files.writeString(processDirectory.resolve("released"), "");
                }
            }
        }
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Docker exec fixture did not start: " + path);
            }
            Thread.sleep(25);
        }
    }

    private static Map<String, Object> testProgress(String groupId) {
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("runId", "run");
        progress.put("groupId", groupId);
        progress.put("completed", 0);
        progress.put("total", 1);
        progress.put("failed", 0);
        progress.put("skipped", 0);
        progress.put("phase", "testing");
        progress.put("current", "suite.Test#running");
        progress.put("elapsedMillis", 0);
        progress.put("testElapsedMillis", 0);
        return progress;
    }

    private static String jsonLine(Map<String, Object> progress) throws Exception {
        return NxnPlan.JSON.writeValueAsString(progress) + "\n";
    }

    private static void awaitText(Path file, String expected, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!Files.readString(file).contains(expected)) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Missing console progress: " + expected + "\n" + Files.readString(file));
            }
            Thread.sleep(25);
        }
    }

    private static void writeNativeStatus(Path path, Map<String, Object> status) throws Exception {
        // 按执行器的 NUL 分隔格式写入真实输入，不伪造 Java 物化后的 JSON。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        appendToken(bytes, "meteor-status-v1");
        for (Map.Entry<String, Object> entry : status.entrySet()) {
            appendToken(bytes, entry.getKey());
            Object value = entry.getValue();
            String encoded;
            if (value instanceof Boolean) {
                encoded = Boolean.TRUE.equals(value) ? "1" : "0";
            } else if (value instanceof List<?>) {
                List<?> items = (List<?>) value;
                StringBuilder values = new StringBuilder();
                for (Object item : items) {
                    if (values.length() > 0) {
                        values.append(',');
                    }
                    values.append(item);
                }
                encoded = values.toString();
            } else {
                encoded = value == null ? "" : value.toString();
            }
            appendToken(bytes, encoded);
        }
        Files.createDirectories(path.getParent());
        Files.write(path, bytes.toByteArray());
    }

    private static void appendToken(ByteArrayOutputStream bytes, String value) throws Exception {
        bytes.write(value.getBytes(StandardCharsets.UTF_8));
        bytes.write(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }
}
