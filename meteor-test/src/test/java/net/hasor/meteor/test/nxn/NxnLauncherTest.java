package net.hasor.meteor.test.nxn;
import java.io.File;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.cobble.StringUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** 用真实 Gradle 执行生产启动脚本，仅以独立 JDK 程序替换 Docker CLI 边界。 */
public class NxnLauncherTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = findRepository();
    private static final Path LAUNCHER = REPOSITORY.resolve("meteor-test/nxn.gradle");
    private static Path gradle;
    private static Path gradleCache;
    private Path temporary;
    private Path repository;
    private Path log;
    private Path output;
    private Path error;
    private Map<String, String> environment;
    private List<JsonNode> calls;
    private List<List<String>> commands;
    private Process activeProcess;

    @BeforeClass
    public static void prepareGradle() throws Exception {
        // 生产宿主入口只依赖 JDK 和 Docker；本夹具在 Linux 容器中验证真实进程组取消。
        if (!Files.isDirectory(Path.of("/proc/self"))) {
            throw new IllegalStateException("Run launcher lifecycle tests in the Linux test container.");
        }
        gradle = findGradle();
        gradleCache = Files.createTempDirectory("meteor-gradle-launcher-cache-");
    }

    @AfterClass
    public static void releaseGradle() throws Exception {
        if (gradleCache == null) {
            return;
        }
        // 仅关闭夹具拥有的 Gradle 用户目录，不能影响其它构建的 daemon。
        ProcessBuilder stop = new ProcessBuilder(gradle.toString(), "--gradle-user-home",
                gradleCache.toString(), "--stop");
        stop.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        stop.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = stop.start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
        }
        deleteTree(gradleCache);
    }

    @Before
    public void prepareFixture() throws Exception {
        // 最小工程不声明 Java 任务；生产脚本负责创建和执行完整宿主任务图。
        this.temporary = Files.createTempDirectory("meteor-gradle-launcher-");
        this.repository = Files.createDirectory(this.temporary.resolve("meteor checkout"));
        Files.writeString(this.repository.resolve("settings.gradle"), "rootProject.name = 'launcher-fixture'\n");
        Files.writeString(this.repository.resolve("build.gradle"), applyLauncher());
        Path compose = this.repository.resolve("meteor-test/docker/compose.yml");
        Files.createDirectories(compose.getParent());
        Files.writeString(compose, "services: {}\n");

        // PATH 只包含假 Docker 和 Gradle 启动所需工具，不可能回退到真实 Docker。
        Path bin = Files.createDirectory(this.temporary.resolve("bin"));
        Path fixtureClasses = Files.createDirectory(this.temporary.resolve("fixture-classes"));
        String resource = FakeDockerMain.class.getName().replace('.', '/') + ".class";
        Path fixtureClass = fixtureClasses.resolve(resource);
        Files.createDirectories(fixtureClass.getParent());
        try (InputStream input = FakeDockerMain.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(resource, input);
            Files.copy(input, fixtureClass, StandardCopyOption.REPLACE_EXISTING);
        }
        Path java = Path.of(System.getProperty("java.home"), "bin/java").toRealPath();
        String fake = "#!/bin/sh\nexec " + shellQuote(java.toString()) + " -cp ";
        fake += shellQuote(fixtureClasses.toString()) + " " + FakeDockerMain.class.getName() + " \"$@\"\n";
        Path docker = bin.resolve("docker");
        Files.writeString(docker, fake);
        assertTrue(docker.toFile().setExecutable(true));
        for (String name : List.of("uname", "ls", "xargs", "sed", "tr")) {
            Files.createSymbolicLink(bin.resolve(name), findExecutable(name));
        }
        this.log = this.temporary.resolve("docker.jsonl");
        this.environment = new LinkedHashMap<>();
        System.getenv().forEach((name, value) -> {
            if (!name.startsWith("METEOR_TEST_") && !name.startsWith("MOCK_")) {
                this.environment.put(name, value);
            }
        });
        this.environment.put("PATH", bin.toString());
        this.environment.put("MOCK_DOCKER_LOG", this.log.toString());
        this.environment.put("JAVA_HOME", java.getParent().getParent().toString());
        this.calls = new ArrayList<>();
        this.commands = new ArrayList<>();
    }

    @After
    public void releaseFixture() throws Exception {
        // 失败断言也必须回收本 testcase 拥有的启动进程与私有目录。
        if (this.activeProcess != null && this.activeProcess.isAlive()) {
            signalGroup(this.activeProcess.pid(), "KILL");
            this.activeProcess.waitFor(15, TimeUnit.SECONDS);
        }
        deleteTree(this.temporary);
    }

    @Test
    public void defaultSixteenScenariosShareOneComposeStart() throws Exception {
        // 默认十六组在同一套容器中执行，不额外创建逐场景容器。
        assertSuccess(runLauncher("--no-build"));
        assertEquals(1, composeCommands("create").size());
        assertEquals(1, composeCommands("start").size());
        assertTrue(composeCommands("start").get(0).contains("--wait"));
        List<String> execution = composeCommands("exec").get(0);
        assertEquals("16", valueAfter(execution, "--groups"));
        assertEquals("/workspace/meteor/meteor-test/build/groups", valueAfter(execution, "--output"));
        assertTrue(execution.stream().anyMatch(argument -> argument.endsWith("docker/compose.yml")));
        for (List<String> command : this.commands) {
            assertFalse(List.of("create", "run", "start").contains(command.get(0)));
        }
    }

    @Test
    public void launcherOwnerStateStaysInsideTestModuleBuild() throws Exception {
        // 直接运行 Gradle 同样将所有者锁和控制目录登记在测试工程中。
        assertSuccess(runLauncher("--no-build"));

        // 本轮退出释放 owner 记录，不在用户缓存下创建框架状态。
        Path control = this.repository.resolve("meteor-test/build/meteor-test-control");
        assertTrue(Files.isRegularFile(control.resolve("docker.lock")));
        assertFalse(Files.exists(control.resolve("docker.owner.json")));
        assertFalse(Files.exists(this.repository.resolve("build/meteor-test-control")));
        assertFalse(Files.exists(this.temporary.resolve("home/.cache/meteor-test")));
    }

    @Test
    public void defaultEnvironmentUsesStableProjectAndContainerNames() throws Exception {
        // 测试环境和报告目录使用固定名称，不随内部批次标识变化。
        assertSuccess(runLauncher("--no-build"));
        JsonNode environment = lastEnvironment();
        assertEquals("meteor-test", environment.path("METEOR_TEST_PROJECT").asText());
        List<String> execution = composeCommands("exec").get(0);
        assertEquals("meteor-test-java-meteor", valueAfter(execution, "--container"));

        // 运行编号仅用于 runner 身份校验，不能反向作为目录或资源名。
        String runId = environment.path("METEOR_TEST_RUN_ID").asText();
        assertEquals(runId, valueAfter(execution, "--run-id"));
        assertEquals(this.repository.resolve("meteor-test/build/docker-runs"), outputDirectory());
        assertFalse(Files.exists(this.repository.resolve("build/docker-runs")));
        String configuration = Files.readString(outputDirectory().resolve("compose.env"));
        assertTrue(configuration.contains("METEOR_TEST_PROJECT=meteor-test\n"));
        String report = Files.readString(outputDirectory().resolve("report.md"));
        assertFalse(report.contains(runId));
        assertTrue(report.contains("[本轮归档](build/docker-runs)"));
        assertFalse(report.contains(this.repository.toString()));
    }

    @Test
    public void consecutiveRunsReuseEnvironmentNamesAndReplaceTheFixedArchive() throws Exception {
        // 第一轮完成并回收后，下一轮继续使用同一套可手动识别的环境名。
        assertSuccess(runLauncher("--no-build"));
        String previousRun = lastEnvironment().path("METEOR_TEST_RUN_ID").asText();
        Path archive = outputDirectory();
        Files.writeString(archive.resolve("previous-run-only.txt"), "old evidence");
        Files.writeString(this.log, "");
        assertSuccess(runLauncher("--no-build"));
        JsonNode environment = lastEnvironment();
        assertEquals("meteor-test", environment.path("METEOR_TEST_PROJECT").asText());
        assertEquals("meteor-test-java-meteor", valueAfter(composeCommands("exec").get(0), "--container"));

        // 内部身份仍按轮区分，固定报告目录只保留当前轮产物。
        String currentRun = environment.path("METEOR_TEST_RUN_ID").asText();
        assertNotEquals(previousRun, currentRun);
        assertEquals(archive, outputDirectory());
        assertFalse(Files.exists(archive.resolve("previous-run-only.txt")));
        assertFalse(Files.exists(archive.resolve(previousRun)));
        assertFalse(Files.exists(archive.resolve(currentRun)));
        assertVolumeRemovals(true);
    }

    @Test
    public void nextRunClearsPreviousJUnitFailureBeforeWritingItsOwnResult() throws Exception {
        // 第一轮失败记录必须清除，后续成功执行不能混入旧 XML 或错误摘要。
        String previousXml = "<testsuite><testcase classname=\"example.PreviousTest\" name=\"fails\">"
                + "<failure message=\"previous failure\"/></testcase></testsuite>";
        assertFailure(runTask("dockerTest", Map.of("MOCK_JUNIT_XML", previousXml), "--no-build"), 1);
        Path archive = outputDirectory();
        Path oldXml = archive.resolve("meteor-test/build/test-results/test/TEST-previous-only.xml");
        Files.writeString(oldXml, previousXml);
        Files.writeString(this.log, "");

        // 第二轮在同一路径重建报告，结果只包含当前成功用例。
        assertSuccess(runLauncher("--no-build"));
        assertEquals(archive, outputDirectory());
        assertFalse(Files.exists(oldXml));
        String report = Files.readString(archive.resolve("report.md"));
        assertTrue(report.contains("通过 1 项，失败 0 项，跳过 0 项"));
        assertFalse(report.contains("example.PreviousTest"));
        assertFalse(report.contains("previous failure"));
        assertEquals("0", Files.readString(archive.resolve("exit-code.txt")).trim());
    }

    @Test
    public void nextStartupFailureCannotBorrowPreviousPassingJUnitResult() throws Exception {
        // 上一轮已有通过 XML；新轮尚未启动测试时，不能用它补足当前结果。
        assertSuccess(runLauncher("--no-build"));
        Path results = outputDirectory().resolve("meteor-test/build/test-results/test");
        assertTrue(Files.isRegularFile(results.resolve("TEST-fixture.xml")));
        Files.writeString(this.log, "");

        // 新轮启动失败后重新生成空结果报告，旧 XML 必须已经清除。
        assertFailure(runTask("dockerTest", Map.of("MOCK_START_EXIT", "4"), "--no-build"), 4);
        assertFalse(Files.exists(results.resolve("TEST-fixture.xml")));
        String report = Files.readString(outputDirectory().resolve("report.md"));
        assertTrue(report.contains("通过 0 项，失败 0 项，跳过 0 项"));
        assertTrue(report.contains("结果：失败"));
    }

    @Test
    public void restartWaitsForActiveRunToArchiveAndCleanBeforeCreatingEnvironment() throws Exception {
        // 旧轮与新轮使用同一固定环境和私有 Gradle 缓存，旧轮在 runner 中保持执行。
        Path control = Files.createDirectory(this.temporary.resolve("previous-entry-control"));
        Path marker = this.temporary.resolve("previous-execution");
        List<String> previousCommand = new ArrayList<>();
        previousCommand.add(findExecutable("setsid").toString());
        previousCommand.addAll(command("dockerTest", false, "--no-build"));
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_EXEC", marker.toString(), "MOCK_CANCEL_PENDING_READS", "1");
        Process previous = processBuilder(previousCommand, overrides).start();
        try {
            awaitFile(marker, Duration.ofSeconds(60));
            readCalls();
            String previousRun = lastEnvironment().path("METEOR_TEST_RUN_ID").asText();
            Path owner = this.repository.resolve("meteor-test/build/meteor-test-control/docker.owner.json");
            JsonNode ownerRecord = JSON.readTree(Files.readString(owner));
            long ownerPid = ownerRecord.path("pid").asLong();
            ProcessHandle ownerProcess = ProcessHandle.of(ownerPid).orElseThrow();
            String observedStart = ownerProcess.info().startInstant().orElseThrow().toString();

            // 新入口先请求 RESTART，等待旧轮取消、归档和清理，不终止共享 Gradle daemon。
            int currentExit = runLauncher("--no-build");
            String ownerDiagnostic = "Owner: " + ownerRecord + "; observed start: " + observedStart + "\n";
            assertEquals(ownerDiagnostic + diagnostics(), 0, currentExit);
            assertTrue(previous.waitFor(30, TimeUnit.SECONDS));
            assertNotEquals(0, previous.exitValue());
            assertEquals("RESTART", Files.readString(control.resolve("cancel.request")).trim());

            // 旧轮先完成归档和清理，新轮才能清空并复用报告目录。
            assertEquals(2, composeCommands("create").size());
            assertEquals(2, composeCommands("down").size());
            int previousCopy = this.commands.indexOf(archiveCopies().get(0));
            int previousDown = this.commands.indexOf(composeCommands("down").get(0));
            int currentCreate = this.commands.lastIndexOf(composeCommands("create").get(1));
            assertTrue(this.commands.toString(), previousCopy < previousDown && previousDown < currentCreate);
            String currentRun = lastEnvironment().path("METEOR_TEST_RUN_ID").asText();
            assertNotEquals(previousRun, currentRun);
            assertEquals("0", Files.readString(outputDirectory().resolve("exit-code.txt")).trim());
            String configuration = Files.readString(outputDirectory().resolve("compose.env"));
            assertTrue(configuration.contains("METEOR_TEST_RUN_ID=" + currentRun + "\n"));
            assertFalse(configuration.contains("METEOR_TEST_RUN_ID=" + previousRun + "\n"));
        } finally {
            // 断言失败时仍只回收此 testcase 自己创建的进程组。
            if (previous.isAlive()) {
                signalGroup(previous.pid(), "KILL");
                previous.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void restartCleansRecordedRetainedWorkspaceBeforeCreatingEnvironment() throws Exception {
        // 用户保留的旧工作区带有可验证的批次归属，下一轮先按旧归档完成回收。
        assertSuccess(runLauncher("--no-build", "--keep-workspace"));
        String previousRun = lastEnvironment().path("METEOR_TEST_RUN_ID").asText();
        Path archive = outputDirectory();
        Files.writeString(archive.resolve("previous-run-only.txt"), "old evidence");
        Files.writeString(this.log, "");

        // 新轮先用旧记录回收工作区，再清空固定目录并创建新环境。
        assertSuccess(runLauncher("--no-build"));
        assertEquals(2, composeCommands("down").size());
        int previousDown = this.commands.indexOf(composeCommands("down").get(0));
        int previousVolume = this.commands.indexOf(commandsStarting("volume", "rm").get(0));
        int currentCreate = this.commands.indexOf(composeCommands("create").get(0));
        assertTrue(this.commands.toString(), previousDown < previousVolume && previousVolume < currentCreate);
        assertEquals("meteor-test-workspace", commandsStarting("volume", "rm").get(0).get(2));
        assertEquals("0", Files.readString(archive.resolve("exit-code.txt")).trim());
        assertFalse(Files.exists(archive.resolve("previous-run-only.txt")));
        assertNotEquals(previousRun, lastEnvironment().path("METEOR_TEST_RUN_ID").asText());
    }

    @Test
    public void restartRestoresMissingOwnedControlDirectoryBeforeStoppingActiveRun() throws Exception {
        // 入口异常退出删除控制目录，但旧 Gradle 任务仍持有固定环境。
        Path cache = this.repository.resolve("meteor-test/build/meteor-test-control");
        Files.createDirectories(cache);
        Path control = Files.createDirectory(cache.resolve("control.fixture-previous"));
        Path marker = this.temporary.resolve("previous-execution");
        List<String> previousCommand = new ArrayList<>();
        previousCommand.add(findExecutable("setsid").toString());
        previousCommand.addAll(command("dockerTest", false, "--no-build"));
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_EXEC", marker.toString());
        Process previous = processBuilder(previousCommand, overrides).start();
        try {
            awaitFile(marker, Duration.ofSeconds(60));
            Files.delete(control);

            // 仅恢复仓库控制目录内已记录的目录，随后沿原任务的取消和清理链路重启。
            assertSuccess(runLauncher("--no-build"));
            assertTrue(previous.waitFor(30, TimeUnit.SECONDS));
            assertNotEquals(0, previous.exitValue());
            assertEquals("RESTART", Files.readString(control.resolve("cancel.request")).trim());
            assertEquals(2, composeCommands("create").size());
            int previousDown = this.commands.indexOf(composeCommands("down").get(0));
            int currentCreate = this.commands.lastIndexOf(composeCommands("create").get(1));
            assertTrue(this.commands.toString(), previousDown < currentCreate);
        } finally {
            // 断言失败时只回收当前 testcase 自己创建的旧入口。
            if (previous.isAlive()) {
                signalGroup(previous.pid(), "KILL");
                previous.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void restartCannotCreateEnvironmentWhenPreviousRunnerCleanupIsUnconfirmed() throws Exception {
        // 旧 runner 的取消请求持续失败，新入口必须保留证据，不能直接覆盖固定环境。
        this.environment.put("MOCK_CANCEL_EXIT", "124");
        Path control = Files.createDirectory(this.temporary.resolve("previous-entry-control"));
        Path marker = this.temporary.resolve("previous-execution");
        List<String> previousCommand = new ArrayList<>();
        previousCommand.add(findExecutable("setsid").toString());
        previousCommand.addAll(command("dockerTest", false, "--no-build"));
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_EXEC", marker.toString());
        Process previous = processBuilder(previousCommand, overrides).start();
        try {
            awaitFile(marker, Duration.ofSeconds(60));

            // 接管失败仍让旧入口完成归档，整个过程只出现旧轮的一次 create。
            assertFailure(runLauncher("--no-build"));
            assertTrue(previous.waitFor(30, TimeUnit.SECONDS));
            assertNotEquals(0, previous.exitValue());
            assertEquals(1, composeCommands("create").size());
            assertFalse(cancellationCopies().isEmpty());
            assertTrue(Files.exists(Path.of(this.log + ".resource-workspace")));
            assertTrue(diagnostics().contains("workspace retained"));
        } finally {
            // 回归失败不能留下夹具拥有的旧启动进程。
            if (previous.isAlive()) {
                signalGroup(previous.pid(), "KILL");
                previous.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void restartRejectsAlteredOwnerWithoutCancellingItsRun() throws Exception {
        // 旧入口仍持有锁时，所有者记录必须同时匹配仓库和进程身份。
        Path control = Files.createDirectory(this.temporary.resolve("previous-entry-control"));
        Path marker = this.temporary.resolve("previous-execution");
        List<String> previousCommand = new ArrayList<>();
        previousCommand.add(findExecutable("setsid").toString());
        previousCommand.addAll(command("dockerTest", false, "--no-build"));
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_EXEC", marker.toString());
        Process previous = processBuilder(previousCommand, overrides).start();
        Path owner = this.repository.resolve("meteor-test/build/meteor-test-control/docker.owner.json");
        String recorded = null;
        try {
            awaitFile(marker, Duration.ofSeconds(60));
            recorded = Files.readString(owner);
            ObjectNode changed = (ObjectNode) JSON.readTree(recorded);
            changed.put("repository", this.temporary.resolve("foreign-checkout").toString());
            Files.writeString(owner, JSON.writeValueAsString(changed));

            // 无法确认归属时拒绝接管，不能向另一个入口发布取消或创建同名环境。
            assertFailure(runLauncher("--no-build"));
            assertEquals(1, composeCommands("create").size());
            assertTrue(composeCommands("down").isEmpty());
            assertFalse(Files.exists(control.resolve("cancel.request")));
            assertTrue(previous.isAlive());
        } finally {
            // 恢复夹具的原始身份，通过正常取消链路回收旧轮，不影响其它进程。
            if (recorded != null) {
                Files.writeString(owner, recorded);
            }
            Files.writeString(control.resolve("cancel.request"), "INT\n");
            if (!previous.waitFor(45, TimeUnit.SECONDS)) {
                signalGroup(previous.pid(), "KILL");
                previous.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void occupiedEnvironmentLockRejectsStartupWithoutDockerCleanup() throws Exception {
        // 同一用户的固定环境已经被持有时，第二个入口不能启动或清理已有环境。
        Path lockFile = this.repository.resolve("meteor-test/build/meteor-test-control/docker.lock");
        Files.createDirectories(lockFile.getParent());
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            assertFailure(runLauncher("--no-build"));
            assertTrue(this.commands.isEmpty());
            assertTrue(diagnostics().contains("meteor-test"));
        }
    }

    @Test
    public void restartRejectsReusedProcessIdentityWithoutPublishingCancellation() throws Exception {
        // 锁记录中的 PID 可被系统复用，启动身份不匹配时不能向对应控制目录发请求。
        Path cache = this.repository.resolve("meteor-test/build/meteor-test-control");
        Path control = Files.createDirectory(this.temporary.resolve("foreign-entry-control"));
        Files.createDirectories(cache);
        Map<String, Object> recorded = new LinkedHashMap<>();
        recorded.put("schemaVersion", 1);
        recorded.put("ownerId", "00000000-0000-0000-0000-000000000001");
        recorded.put("repository", this.repository.toRealPath().toString());
        recorded.put("pid", ProcessHandle.current().pid());
        recorded.put("startedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        String boot = Files.readString(Path.of("/proc/sys/kernel/random/boot_id")).trim();
        recorded.put("processIdentity", boot + ":0");
        recorded.put("controlDirectory", control.toString());
        Files.writeString(cache.resolve("docker.owner.json"), JSON.writeValueAsString(recorded));
        Path lockFile = cache.resolve("docker.lock");

        // 归属验证失败必须在任何 Docker 操作之前退出，当前测试 JVM 仍然存活。
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            assertFailure(runLauncher("--no-build"));
            assertTrue(this.commands.isEmpty());
            assertFalse(Files.exists(control.resolve("cancel.request")));
            assertTrue(ProcessHandle.current().isAlive());
        }
    }

    @Test
    public void existingEnvironmentContainerRejectsStartupWithoutCleanup() throws Exception {
        // 固定项目中已有容器时拒绝覆盖，不能将其它批次的测试环境收尾。
        Map<String, String> overrides = Map.of("MOCK_EXISTING_CONTAINER_RUN", "previous-run");
        assertFailure(runTask("dockerTest", overrides, "--no-build"));
        assertNoEnvironmentChanges();
        assertTrue(diagnostics().contains("meteor-test"));
    }

    @Test
    public void existingEnvironmentNetworkRejectsStartupWithoutCleanup() throws Exception {
        // 手动保留的网络也属于唯一测试环境，启动不能忽略它再创建另一套网络。
        Map<String, String> overrides = Map.of("MOCK_EXISTING_NETWORK_RUN", "previous-run");
        assertFailure(runTask("dockerTest", overrides, "--no-build"));
        assertNoEnvironmentChanges();
        assertTrue(diagnostics().contains("meteor-test"));
    }

    @Test
    public void existingEnvironmentVolumeRejectsStartupWithoutCleanup() throws Exception {
        // 上轮保留的工作区不能被新批次覆盖或顺手删除。
        Map<String, String> overrides = Map.of("MOCK_EXISTING_VOLUME_RUN", "previous-run");
        assertFailure(runTask("dockerTest", overrides, "--no-build"));
        assertNoEnvironmentChanges();
        assertTrue(diagnostics().contains("meteor-test"));
    }

    @Test
    public void sameNamedVolumeWithoutComposeLabelsRejectsStartup() throws Exception {
        // 用户手建的同名卷没有 Compose 标签，也不能在启动时被覆盖。
        Map<String, String> overrides = Map.of("MOCK_UNLABELLED_VOLUME", "1");
        assertFailure(runTask("dockerTest", overrides, "--no-build"));
        assertNoEnvironmentChanges();
        assertTrue(diagnostics().contains("meteor-test"));
    }

    @Test
    public void defaultStartupLetsComposeBuildOnlyMissingImages() throws Exception {
        // 默认由 Compose 复用已有镜像，并保留缺失工具链镜像的构建入口。
        assertSuccess(runLauncher());
        assertTrue(composeCommands("build").isEmpty());
        List<String> startup = composeCommands("create").get(0);
        assertFalse(startup.contains("--build"));
        assertFalse(startup.contains("--no-build"));
        assertFalse(startup.contains("--pull"));
        assertTrue(composeCommands("pull").isEmpty());
    }

    @Test
    public void explicitBuildRebuildsImagesBeforeStartup() throws Exception {
        // 工具链变更由用户显式触发重建，随后仍通过同一套 Compose 启动。
        assertSuccess(runLauncher("--rebuild"));
        assertEquals(1, composeCommands("build").size());
        assertEquals(1, composeCommands("create").size());
        assertEquals(1, composeCommands("start").size());
        assertTrue(this.commands.indexOf(composeCommands("build").get(0))
                < this.commands.indexOf(composeCommands("create").get(0)));
    }

    @Test
    public void conflictingBuildOptionsAreRejectedBeforeDocker() throws Exception {
        // 相互冲突的构建选项不能创建 Docker 资源。
        assertFailure(runLauncher("--rebuild", "--no-build"));
        assertTrue(this.commands.isEmpty());
    }

    @Test
    public void noBuildNeverRebuildsOrPulls() throws Exception {
        // 显式复用镜像时不触发构建或拉取。
        assertSuccess(runLauncher("--no-build"));
        assertTrue(composeCommands("build").isEmpty());
        assertTrue(composeCommands("pull").isEmpty());
        assertTrue(composeCommands("create").get(0).contains("--no-build"));
        assertTrue(composeCommands("create").get(0).contains("never"));
    }

    @Test
    public void manualSingleScenarioGroupIsForwarded() throws Exception {
        // 手动并发值传递给容器内调度器。
        assertSuccess(runLauncher("--no-build", "--groups", "1"));
        assertEquals("1", valueAfter(composeCommands("exec").get(0), "--groups"));
    }

    @Test
    public void scriptErrorsContinueByDefaultAndRecordSelection() throws Exception {
        // 默认继续执行异常用例之后的测试，容器调度器接收明确的布尔参数。
        assertSuccess(runLauncher("--no-build"));
        List<String> execution = composeCommands("exec").get(0);
        assertEquals("true", valueAfter(execution, "--skipError"));

        // 本轮归档保留相同选项，便于在最终报告中解释执行方式。
        JsonNode launcher = JSON.readTree(Files.readString(outputDirectory().resolve("launcher.json")));
        assertTrue(launcher.path("skipError").asBoolean());
    }

    @Test
    public void explicitStopAfterErrorIsForwardedAndRecorded() throws Exception {
        // 用户可关闭继续执行行为，选项只改变错误后的调度策略。
        assertSuccess(runLauncher("--no-build", "--skipError", "false"));
        List<String> execution = composeCommands("exec").get(0);
        assertEquals("false", valueAfter(execution, "--skipError"));

        // 归档显式保存 false，不能退回默认值或省略此参数。
        JsonNode launcher = JSON.readTree(Files.readString(outputDirectory().resolve("launcher.json")));
        assertTrue(launcher.has("skipError"));
        assertFalse(launcher.path("skipError").asBoolean());
    }

    @Test
    public void invalidErrorContinuationOptionIsRejectedBeforeDocker() throws Exception {
        // 非布尔参数必须在任何 Docker 命令之前被拒绝。
        assertFailure(runLauncher("--skipError", "sometimes"));
        assertTrue(this.commands.isEmpty());
    }

    @Test
    public void clockOwnerReceivesThisRunsAbsoluteArtifactDirectory() throws Exception {
        // 外层脚本与 Gradle 通过本轮独有目录关联，不能扫描并选中旧归档。
        Path state = Files.createDirectory(this.temporary.resolve("clock-owner-state"));
        Map<String, String> overrides = Map.of("METEOR_TEST_SCRIPT_STATE_DIR", state.toString());
        assertSuccess(runTask("dockerTest", overrides, "--no-build"));

        // 指针必须直接定位本轮真实绝对路径，供恢复脚本追加异常结果。
        Path artifacts = outputDirectory();
        assertEquals(artifacts.toAbsolutePath().toString(), Files.readString(state.resolve("artifacts")).trim());
    }

    @Test
    public void preparationScriptErrorContinuesTestsButFailsFinalMarkdown() throws Exception {
        // 模拟外层校时脚本已记录准备异常，不执行任何实际特权命令。
        Path state = Files.createDirectory(this.temporary.resolve("clock-owner-state"));
        Files.writeString(state.resolve("errors.tsv"), "clock.pause\tWindows administrator approval denied.\n");
        Map<String, String> overrides = Map.of("METEOR_TEST_SCRIPT_STATE_DIR", state.toString());
        assertFailure(runTask("dockerTest", overrides, "--no-build"), 1);

        // 默认策略仍启动测试并完成清理，最终报告单独列出准备异常。
        assertEquals(1, composeCommands("exec").size());
        assertEquals(1, composeCommands("down").size());
        String markdown = Files.readString(outputDirectory().resolve("report.md"));
        assertTrue(markdown.contains("clock.pause"));
        assertTrue(markdown.contains("Windows administrator approval denied."));
    }

    @Test
    public void repeatedSelectorsPreserveArgumentBoundaries() throws Exception {
        // 空格和通配符属于参数值，不能被外层 shell 再次拆分。
        assertSuccess(runLauncher("--no-build", "--tests", "test.Example.some case", "--tests", "test.Other*"));
        List<String> execution = composeCommands("exec").get(0);
        List<String> expected = List.of("--tests", "test.Example.some case", "--tests", "test.Other*");
        assertEquals(expected, execution.subList(execution.size() - 4, execution.size()));
    }

    @Test
    public void dryRunNeverBuildsJavaOrStartsDocker() throws Exception {
        // 宿主任务没有 Java 或 SDK 构建依赖。
        assertSuccess(runLauncher("--dry-run"));
        String stdout = Files.readString(this.output);
        assertTrue(stdout.contains(":dockerTest SKIPPED"));
        assertFalse(stdout.contains("testClasses"));
        assertFalse(stdout.contains("compileJava"));
        assertTrue(this.commands.isEmpty());
    }

    @Test
    public void invalidParallelismIsRejectedBeforeDocker() throws Exception {
        // 参数错误不能创建任何 Docker 资源。
        assertFailure(runLauncher("--groups", "17"));
        assertTrue(this.commands.isEmpty());
    }

    @Test
    public void explicitMissingMavenSeedIsRejectedBeforeDocker() throws Exception {
        // 显式种子路径缺失时报告配置错误。
        String missing = this.temporary.resolve("missing").toString();
        assertFailure(runLauncher("--maven-seed", missing));
        assertTrue(this.commands.isEmpty());
    }

    @Test
    public void missingMavenSeedReplacesPreviousSuccessfulReportWithCurrentFailure() throws Exception {
        // 先生成旧轮通过结果，并保存下一轮资源归属校验需要的环境记录。
        assertSuccess(runLauncher("--no-build"));
        Path archive = outputDirectory();
        Path configuration = archive.resolve("compose.env");
        String recorded = Files.readString(configuration);
        Path previousXml = archive.resolve("meteor-test/build/test-results/test/TEST-fixture.xml");
        assertTrue(Files.isRegularFile(previousXml));
        Files.writeString(this.log, "");

        // 显式种子缺失发生在 Docker 调用前，也必须清除旧 XML 并生成本次失败报告。
        String missing = this.temporary.resolve("missing").toString();
        assertFailure(runLauncher("--no-build", "--maven-seed", missing), 1);
        assertTrue(this.commands.isEmpty());
        assertFalse(Files.exists(previousXml));
        assertEquals(recorded, Files.readString(configuration));
        String report = Files.readString(archive.resolve("report.md"));
        assertTrue(report.contains("结果：失败"));
        assertTrue(report.contains("通过 0 项，失败 0 项，跳过 0 项"));
        assertEquals(report, Files.readString(this.repository.resolve("meteor-test/report.md")));
        assertTrue(Files.isRegularFile(archive.resolve("launcher-error.txt")));
    }

    @Test
    public void invalidPlatformReplacesPreviousSuccessfulReportWithCurrentFailure() throws Exception {
        // 旧轮通过报告和环境记录已经存在，平台检查失败不能继续展示它的测试结果。
        assertSuccess(runLauncher("--no-build"));
        Path archive = outputDirectory();
        Path configuration = archive.resolve("compose.env");
        String recorded = Files.readString(configuration);
        Path previousXml = archive.resolve("meteor-test/build/test-results/test/TEST-fixture.xml");
        assertTrue(Files.isRegularFile(previousXml));
        Files.writeString(this.log, "");

        // 平台不受支持时记录本次失败，同时保留旧环境身份供后续安全清理。
        assertFailure(runLauncher("--no-build", "--platform", "linux/unsupported"), 1);
        assertNoEnvironmentChanges();
        assertFalse(Files.exists(previousXml));
        assertEquals(recorded, Files.readString(configuration));
        String report = Files.readString(archive.resolve("report.md"));
        assertTrue(report.contains("结果：失败"));
        assertTrue(report.contains("通过 0 项，失败 0 项，跳过 0 项"));
        assertEquals(report, Files.readString(this.repository.resolve("meteor-test/report.md")));
        assertTrue(Files.isRegularFile(archive.resolve("launcher-error.txt")));
    }

    @Test
    public void relativeMavenSeedIsResolvedFromRepositoryRoot() throws Exception {
        // 子工程映射到深层目录时，相对种子仍属于仓库根目录。
        Path seed = Files.createDirectories(this.repository.resolve("local-maven-seed/net/hasor"));
        Files.createDirectories(seed.resolve("cobble-all/4.0.0"));
        String settings = "rootProject.name = 'launcher-fixture'\ninclude 'meteor-test'\n";
        settings += "project(':meteor-test').projectDir = file('meteor-test/peer/java/meteor')\n";
        Files.writeString(this.repository.resolve("settings.gradle"), settings);
        Files.writeString(this.repository.resolve("build.gradle"), "");
        Path project = Files.createDirectories(this.repository.resolve("meteor-test/peer/java/meteor"));
        Files.writeString(project.resolve("build.gradle"), applyLauncher());

        // 通过用户面对的限定任务入口执行。
        int status = runTask(":meteor-test:dockerTest", Map.of(), "--no-build", "--maven-seed", "local-maven-seed");
        assertSuccess(status);
        assertEquals(seed.toString(), lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
    }

    @Test
    public void populatedDefaultMavenRepositorySeedsHasorArtifacts() throws Exception {
        // 宿主 install 或 publishToMavenLocal 后的 Cobble、Neta 位于默认 Maven 仓库。
        Path localRepository = this.temporary.resolve("home/.m2/repository");
        Path seed = Files.createDirectories(localRepository.resolve("net/hasor"));
        Path cobble = Files.createDirectories(seed.resolve("cobble-all/4.0.0"));
        Files.writeString(cobble.resolve("cobble-all-4.0.0.pom"), "<project/>\n");
        Path neta = Files.createDirectories(seed.resolve("neta-core/4.0.0"));
        Files.writeString(neta.resolve("neta-core-4.0.0.pom"), "<project/>\n");
        Files.createDirectories(localRepository.resolve("org/example/unrelated/1.0"));

        // 默认入口只把 Hasor 命名空间传给 Compose，不挂载整个宿主缓存。
        assertSuccess(runLauncher("--no-build"));
        assertEquals(seed.toString(), lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
    }

    @Test
    public void configuredMavenRepositorySeedsHasorArtifacts() throws Exception {
        // maven.repo.local 指向 Maven 仓库根，空格路径也不经过 shell 拆分。
        Path localRepository = this.temporary.resolve("custom maven repository");
        Path seed = Files.createDirectories(localRepository.resolve("net/hasor"));
        Files.createDirectories(seed.resolve("neta-core/4.0.0"));

        // 标准 Maven 系统属性覆盖默认 user.home 仓库。
        assertSuccess(runLauncher("--no-build", "-Dmaven.repo.local=" + localRepository));
        assertEquals(seed.toString(), lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
    }

    @Test
    public void explicitMavenRepositoryRootSeedsHasorArtifacts() throws Exception {
        // 显式种子允许直接使用 mvn install 的仓库根目录。
        Path localRepository = this.temporary.resolve("installed artifacts");
        Path seed = Files.createDirectories(localRepository.resolve("net/hasor"));
        Files.createDirectories(seed.resolve("cobble-all/4.0.0"));
        Files.createDirectories(localRepository.resolve("org/example/unrelated/1.0"));

        // 选择仓库根后仍只导入 net/hasor。
        assertSuccess(runLauncher("--no-build", "--maven-seed", localRepository.toString()));
        assertEquals(seed.toString(), lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
    }

    @Test
    public void explicitLegacyHasorMavenDirectoryIsPreserved() throws Exception {
        // 已有命令可以继续直接指定 Maven 仓库中的 net/hasor 子目录。
        Path seed = Files.createDirectories(this.temporary.resolve("local-maven/net/hasor"));
        Files.createDirectories(seed.resolve("cobble-all/4.0.0"));

        assertSuccess(runLauncher("--no-build", "--maven-seed", seed.toString()));
        assertEquals(seed.toString(), lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
    }

    @Test
    public void explicitFlatHasorArtifactDirectoryIsPreserved() throws Exception {
        // 手工导出的 Hasor 制品目录也可以没有 net/hasor 外层目录。
        Path seed = Files.createDirectories(this.temporary.resolve("exported-artifacts"));
        Files.createDirectories(seed.resolve("neta-codec-http/4.0.0"));

        assertSuccess(runLauncher("--no-build", "--maven-seed", seed.toString()));
        assertEquals(seed.toString(), lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
    }

    @Test
    public void explicitMavenSeedOverridesConfiguredMavenRepository() throws Exception {
        // 显式种子优先于系统属性，即使属性仍指向已不存在的旧仓库。
        Path seed = Files.createDirectories(this.temporary.resolve("installed/net/hasor"));
        Files.createDirectories(seed.resolve("neta-core/4.0.0"));
        String configured = "-Dmaven.repo.local=" + this.temporary.resolve("missing-old-repository");

        assertSuccess(runLauncher("--no-build", configured, "--maven-seed", seed.toString()));
        assertEquals(seed.toString(), lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
    }

    @Test
    public void explicitEmptyMavenSeedIsRejectedBeforeDocker() throws Exception {
        // 任意空目录无法确定 Hasor 仓库布局，显式设置时必须报告配置错误。
        Path empty = Files.createDirectory(this.temporary.resolve("empty-seed"));

        assertFailure(runLauncher("--maven-seed", empty.toString()));
        assertTrue(this.commands.isEmpty());
        assertTrue(diagnostics().contains("Maven seed must contain net/hasor"));
    }

    @Test
    public void explicitUnrelatedMavenRepositoryIsRejectedBeforeDocker() throws Exception {
        // 只有其它 group 的仓库不能误当成平铺的 Hasor 制品目录。
        Path localRepository = Files.createDirectory(this.temporary.resolve("unrelated-repository"));
        Files.createDirectories(localRepository.resolve("org/example/library/1.0"));

        assertFailure(runLauncher("--maven-seed", localRepository.toString()));
        assertTrue(this.commands.isEmpty());
        assertTrue(diagnostics().contains("Maven seed must contain net/hasor"));
    }

    @Test
    public void explicitMavenSeedFileIsRejectedBeforeDocker() throws Exception {
        // 存在的普通文件同样不能作为 Docker 目录挂载源。
        Path seed = Files.writeString(this.temporary.resolve("seed.pom"), "<project/>\n");

        assertFailure(runLauncher("--maven-seed", seed.toString()));
        assertTrue(this.commands.isEmpty());
        assertTrue(diagnostics().contains("Maven seed directory does not exist"));
    }

    @Test
    public void configuredMissingMavenRepositoryIsRejectedBeforeDocker() throws Exception {
        // 标准系统属性显式指定的缓存缺失时，不静默回退到默认仓库。
        String configured = "-Dmaven.repo.local=" + this.temporary.resolve("missing-repository");

        assertFailure(runLauncher(configured));
        assertTrue(this.commands.isEmpty());
        assertTrue(diagnostics().contains("Maven repository directory does not exist"));
    }

    @Test
    public void missingDefaultMavenSeedUsesExistingEmptyDirectory() throws Exception {
        // 没有宿主 Maven 缓存仍可从容器内独立解析依赖。
        assertSuccess(runLauncher("--no-build"));
        Path seed = Path.of(lastEnvironment().path("METEOR_TEST_MAVEN_SEED").asText());
        assertTrue(Files.isDirectory(seed));
        try (Stream<Path> entries = Files.list(seed)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    public void imageOverrideAndArchitectureCacheNamesArePreserved() throws Exception {
        // 镜像覆盖与 SDK 缓存命名独立于调度任务实现。
        Map<String, String> overrides = Map.of("METEOR_TEST_IMAGE_JAVA", "example/java:isolated",
                "METEOR_TEST_CACHE_KEY", "local-sdk", "MOCK_COMPOSE_IMAGES", "example/java:isolated");
        assertSuccess(runTask("dockerTest", overrides, "--no-build"));
        JsonNode environment = lastEnvironment();
        assertEquals("example/java:isolated", environment.path("METEOR_TEST_IMAGE_JAVA").asText());
        assertEquals("local-sdk", environment.path("METEOR_TEST_CACHE_KEY").asText());
        assertEquals("amd64", environment.path("METEOR_TEST_ARCH").asText());
        List<String> cache = commandsStarting("volume", "create").get(0);
        assertEquals("meteor-test-gradle-local-sdk", cache.get(cache.size() - 1));
        assertTrue(this.commands.indexOf(cache) < this.commands.indexOf(composeCommands("create").get(0)));
        List<List<String>> inspected = commandsStarting("image", "inspect");
        assertEquals(1, inspected.size());
        List<String> image = inspected.get(0);
        assertEquals("example/java:isolated", image.get(image.size() - 1));
    }

    @Test
    public void prepareOnlyStopsServicesAndRetainsOnlyWorkspace() throws Exception {
        // 准备模式释放容器和运行缓存，保留已构建工作区。
        assertSuccess(runLauncher("--no-build", "--prepare-only"));
        assertTrue(composeCommands("exec").isEmpty());
        assertEquals(1, composeCommands("start").size());
        assertEquals(1, composeCommands("down").size());
        assertVolumeRemovals(false);
    }

    @Test
    public void failedTestIsArchivedBeforeServicesStop() throws Exception {
        // 测试失败先保留构建产物，再关闭共享环境。
        assertFailure(runTask("dockerTest", Map.of("MOCK_EXEC_EXIT", "7"), "--no-build"), 7);
        assertArchiveBeforeStop();
        assertTrue(Files.isRegularFile(outputDirectory().resolve("meteor-test/build/archived.txt")));
    }

    @Test
    public void nacosServerLogsAreArchivedBeforeServicesStop() throws Exception {
        // 从已创建的 Nacos 容器提取服务端日志，不能等 Compose 删除容器后再复制。
        assertSuccess(runLauncher("--no-build"));
        List<String> copy = commandsStarting("cp").stream() //
                .filter(command -> command.get(1).endsWith(":/home/nacos/logs/.")) //
                .findFirst().orElseThrow();

        // 归档保存在本轮目录中，复制完成后才允许关闭共享服务。
        assertTrue(this.commands.indexOf(copy) < this.commands.indexOf(composeCommands("down").get(0)));
        Path diagnostic = outputDirectory().resolve("nacos-logs/config-server.log");
        assertEquals("Nacos configuration diagnostic\n", Files.readString(diagnostic));
    }

    @Test
    public void nacosLogCopyFailureKeepsOriginalTestExitCode() throws Exception {
        // 次级诊断复制失败直接显示原因，不覆盖原始测试退出码。
        Map<String, String> overrides = Map.of("MOCK_EXEC_EXIT", "7", "MOCK_NACOS_LOG_EXIT", "9");
        assertFailure(runTask("dockerTest", overrides, "--no-build"), 7);

        // 诊断异常也必须继续完成正式报告归档和资源清理。
        assertTrue(diagnostics().contains("Nacos server log copy failed"));
        assertTrue(Files.isRegularFile(outputDirectory().resolve("meteor-test/build/archived.txt")));
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void missingNacosContainerDoesNotAttemptLogCopy() throws Exception {
        // 容器尚未创建时不向不存在的 Nacos 复制日志。
        assertSuccess(runTask("dockerTest", Map.of("MOCK_NO_NACOS", "1"), "--no-build"));

        // 其它报告仍正常归档，诊断缺失不改变正式测试结果。
        boolean copied = commandsStarting("cp").stream() //
                .anyMatch(command -> command.get(1).endsWith(":/home/nacos/logs/."));
        assertFalse(copied);
        assertEquals(1, archiveCopies().size());
    }

    @Test
    public void failedTestSummaryRemainsVisibleAfterResourceCleanup() throws Exception {
        // 模拟含失败、错误和跳过的本轮归档，测试退出码必须保留。
        String xml = """
                <testsuite>
                  <testcase classname="example.OrderTest" name="saved"/>
                  <testcase classname="example.OrderTest" name="missing">
                    <failure message="Order did not become visible."/>
                  </testcase>
                  <testcase classname="example.OrderTest" name="disconnected">
                    <error type="java.io.IOException" message="Connection closed."/>
                  </testcase>
                  <testcase classname="example.OrderTest" name="ignored"><skipped/></testcase>
                </testsuite>
                """;
        Map<String, String> overrides = Map.of("MOCK_EXEC_EXIT", "7", "MOCK_JUNIT_XML", xml);
        assertFailure(runTask("dockerTest", overrides, "--no-build"), 7);

        // 清理输出之后再次给出完整失败身份和原因，用户无需向前翻找。
        String console = diagnostics();
        String summary = "[result] Tests 4, passed 1, failed 2, skipped 1; exit code 7.";
        assertTrue(console.contains(summary));
        assertTrue(console.indexOf(summary) > console.indexOf("down stdout"));
        assertTrue(console.contains("[FAIL] example.OrderTest#missing: Order did not become visible."));
        assertTrue(console.contains("[FAIL] example.OrderTest#disconnected: Connection closed."));
        assertTrue(console.contains("Docker test artifacts: " + outputDirectory()));
    }

    @Test
    public void archivedTestFailureCannotBeReportedAsSuccess() throws Exception {
        // 即使执行器错误地返回成功，归档中的失败也必须使本轮失败。
        String xml = "<testsuite><testcase classname=\"example.OrderTest\" name=\"missing\">";
        xml += "<failure message=\"Order missing.\"/></testcase></testsuite>";
        assertFailure(runTask("dockerTest", Map.of("MOCK_JUNIT_XML", xml), "--no-build"), 1);

        // 最终摘要同时显示真实用例统计与纠正后的退出码。
        assertTrue(diagnostics().contains("[result] Tests 1, passed 0, failed 1, skipped 0; exit code 1."));
    }

    @Test
    public void failedCaseIsListedInMarkdownAfterArchivalAndCleanup() throws Exception {
        // 先提供真实 JUnit 失败记录，模拟执行器没有正确传播错误退出码。
        String xml = "<testsuite><testcase classname=\"example.OrderTest\" name=\"missing\">";
        xml += "<failure message=\"Order did not become visible.\"/></testcase></testsuite>";
        assertFailure(runTask("dockerTest", Map.of("MOCK_JUNIT_XML", xml), "--no-build"), 1);

        // 继续执行策略不能将失败吞掉，报告必须在完成归档和清理后指出具体用例。
        assertArchiveBeforeStop();
        assertVolumeRemovals(true);
        Path report = outputDirectory().resolve("report.md");
        String markdown = Files.readString(report);
        assertTrue(markdown.contains("example.OrderTest#missing"));
        assertTrue(markdown.contains("Order did not become visible."));
        assertTrue(markdown.contains("[本轮归档](build/docker-runs)"));
        assertFalse(markdown.contains(this.repository.toString()));
        assertEquals(markdown, Files.readString(this.repository.resolve("meteor-test/report.md")));
    }

    @Test
    public void failedTeardownTakesPrecedenceOverSkippedTestInSummary() throws Exception {
        // assumption 跳过后 @After 仍可能失败，同一用例只能计为一次失败。
        String xml = "<testsuite><testcase classname=\"example.OrderTest\" name=\"teardown\"><skipped/>";
        xml += "<failure message=\"Teardown failed.\"/></testcase></testsuite>";
        assertFailure(runTask("dockerTest", Map.of("MOCK_JUNIT_XML", xml), "--no-build"), 1);

        // 最终统计与正式报告采用同一优先级，失败身份也只打印一次。
        String console = diagnostics();
        assertTrue(console.contains("[result] Tests 1, passed 0, failed 1, skipped 0; exit code 1."));
        String failure = "[FAIL] example.OrderTest#teardown: Teardown failed.";
        assertTrue(console.contains(failure));
        assertEquals(console.indexOf(failure), console.lastIndexOf(failure));
    }

    @Test
    public void missingJUnitResultsFailEvenWhenRunnerReturnsSuccess() throws Exception {
        // 执行器退出成功却没有保存任何 XML 时，本轮仍不能被判定通过。
        assertFailure(runTask("dockerTest", Map.of("MOCK_NO_JUNIT_XML", "1"), "--no-build"), 1);

        // 先归档和回收本轮资源，再留下失败报告和可见原因。
        assertArchiveBeforeStop();
        assertVolumeRemovals(true);
        String markdown = Files.readString(outputDirectory().resolve("report.md"));
        assertTrue(markdown.contains("结果：失败"));
        assertTrue(markdown.contains("JUnit 结果不可用"));
        assertTrue(diagnostics().contains("JUnit results unavailable"));
    }

    @Test
    public void emptyJUnitSuiteCannotBeReportedAsSuccessfulRun() throws Exception {
        // 空 testsuite 不表示任何已执行的用例。
        assertFailure(runTask("dockerTest", Map.of("MOCK_JUNIT_XML", "<testsuite/>"), "--no-build"), 1);

        // 零用例结果明确报告失败，不补写通过或跳过。
        String markdown = Files.readString(outputDirectory().resolve("report.md"));
        assertTrue(markdown.contains("结果：失败"));
        assertTrue(markdown.contains("通过 0 项，失败 0 项，跳过 0 项"));
        assertTrue(diagnostics().contains("no executed testcase"));
    }

    @Test
    public void unreadableArchivedReportCannotProduceSuccessfulSummary() throws Exception {
        // 执行器返回成功但 XML 已损坏时，不能显示完整结果或宣告成功。
        assertFailure(runTask("dockerTest", Map.of("MOCK_JUNIT_XML", "<invalid"), "--no-build"), 1);

        // 明确标记只取得部分结果，原始解析诊断仍显示在控制台。
        String console = diagnostics();
        assertTrue(console.contains("[result] Partial JUnit results: tests 0"));
        assertTrue(console.contains("Cannot read archived JUnit result:"));
    }

    @Test
    public void unreadableArchivedReportKeepsOriginalTestExitCode() throws Exception {
        // 损坏的报告只增加可见诊断，不能覆盖原始执行失败码。
        Map<String, String> overrides = Map.of("MOCK_EXEC_EXIT", "7", "MOCK_JUNIT_XML", "<invalid");
        assertFailure(runTask("dockerTest", overrides, "--no-build"), 7);

        // XML 解析异常必须打印到控制台，归档和清理仍照常完成。
        String console = diagnostics();
        assertTrue(console.contains("Cannot read archived JUnit result:"));
        assertTrue(console.contains("XMLStreamException"));
        assertTrue(console.contains("Docker test artifacts: " + outputDirectory()));
        assertArchiveBeforeStop();
    }

    @Test
    public void failedArchiveFailsRunAndRetainsWorkspace() throws Exception {
        // 归档失败不能宣告成功，工作区必须留给人工恢复。
        assertFailure(runTask("dockerTest", Map.of("MOCK_ARCHIVE_FAIL", "1"), "--no-build"), 1);
        assertVolumeRemovals(false);
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void disappearedRunnerCannotSucceedWithoutArchive() throws Exception {
        // runner 消失时仍尝试关闭环境并明确报告缺失归档。
        assertFailure(runTask("dockerTest", Map.of("MOCK_NO_RUNNER", "1"), "--no-build"), 1);
        assertTrue(diagnostics().contains("Runner disappeared before archival"));
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void failedStartupStillArchivesAndStopsServices() throws Exception {
        // 部分启动失败的 Compose 项目也归当前调用负责清理。
        assertFailure(runTask("dockerTest", Map.of("MOCK_START_EXIT", "4"), "--no-build"), 4);
        assertTrue(composeCommands("exec").isEmpty());
        assertEquals(1, composeCommands("down").size());
        assertFalse(commandsStarting("cp").isEmpty());
        assertTrue(diagnostics().contains("start stderr"));
        assertFollowerStopped();

        // 测试尚未启动也必须产出当前轮的失败报告，不能残留上一轮成功摘要。
        Path report = outputDirectory().resolve("report.md");
        String markdown = Files.readString(report);
        assertTrue(markdown.contains(outputDirectory().getFileName().toString()));
        assertEquals(markdown, Files.readString(this.repository.resolve("meteor-test/report.md")));
    }

    @Test
    public void failedBuildStillRequestsComposeCleanup() throws Exception {
        // 镜像构建失败不得遗留本轮 Compose 项目。
        Map<String, String> overrides = Map.of("MOCK_BUILD_EXIT", "5", "MOCK_NO_RUNNER", "1");
        assertFailure(runTask("dockerTest", overrides, "--rebuild"), 5);
        assertTrue(composeCommands("create").isEmpty());
        assertTrue(composeCommands("start").isEmpty());
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void remainingContainerFailsCleanupValidation() throws Exception {
        // Docker 返回成功但仍有项目容器时，清理结果必须失败。
        assertFailure(runTask("dockerTest", Map.of("MOCK_REMAINING_CONTAINER", "1"), "--no-build"), 1);
        assertTrue(diagnostics().contains("Resource cleanup failed"));
    }

    @Test
    public void failedComposeDownFailsRun() throws Exception {
        // Compose 关闭失败仍保留成功完成的归档。
        assertFailure(runTask("dockerTest", Map.of("MOCK_DOWN_EXIT", "6"), "--no-build"), 1);
        assertTrue(Files.isRegularFile(outputDirectory().resolve("meteor-test/build/archived.txt")));
    }

    @Test
    public void remainingNetworkFailsCleanupValidation() throws Exception {
        // 本轮网络残留属于清理失败。
        assertFailure(runTask("dockerTest", Map.of("MOCK_REMAINING_NETWORK", "1"), "--no-build"), 1);
        assertTrue(diagnostics().contains("Resource cleanup failed"));
    }

    @Test
    public void wrongImageArchitectureStopsBeforeScenarios() throws Exception {
        // 错误平台镜像不能进入测试执行阶段。
        assertFailure(runTask("dockerTest", Map.of("MOCK_IMAGE_PLATFORM", "linux/arm64"), "--no-build"), 2);
        assertTrue(composeCommands("exec").isEmpty());
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void unavailableComposeImageListFailsAndCleansUp() throws Exception {
        // 无法取得实际镜像清单时不能继续运行未验证的环境。
        assertFailure(runTask("dockerTest", Map.of("MOCK_IMAGES_EXIT", "8"), "--no-build"), 8);
        assertTrue(composeCommands("exec").isEmpty());
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void failedTestKeepsExitCodeWhenArchivalAlsoFails() throws Exception {
        // 次级归档失败不能覆盖原始测试错误。
        Map<String, String> overrides = Map.of("MOCK_EXEC_EXIT", "7", "MOCK_ARCHIVE_FAIL", "1");
        assertFailure(runTask("dockerTest", overrides, "--no-build"), 7);
        assertVolumeRemovals(false);
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void terminalTerminationArchivesAndCleansStartedProject() throws Exception {
        // 真实终端进程组 TERM 经过 Gradle 取消与独立收尾链路。
        assertCancelledRun(false);
    }

    @Test
    public void startupControlCancellationKeepsConsoleConnectedThroughCleanup() throws Exception {
        // 准备阶段的取消通过控制文件送达，Gradle 客户端保持连接直至归档与清理结束。
        Path control = Files.createDirectory(this.temporary.resolve("entry-control"));
        Path marker = this.temporary.resolve("controlled-startup");
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_START", marker.toString());
        ProcessBuilder builder = processBuilder(command("dockerTest", false, "--no-build"), overrides);
        this.activeProcess = builder.start();
        awaitFile(marker, Duration.ofSeconds(60));
        awaitConsole("prepare | workspace preparation stdout", Duration.ofSeconds(10));

        // 中断后继续等待原启动进程，收尾输出仍送到同一份控制台日志。
        Files.writeString(control.resolve("cancel.request"), "INT\n");
        assertTrue(diagnostics(), this.activeProcess.waitFor(60, TimeUnit.SECONDS));
        readCalls();
        assertFailure(this.activeProcess.exitValue(), 130);
        assertArchiveBeforeStop();
        assertTrue(composeCommands("exec").isEmpty());
        assertTrue(diagnostics().contains("down stderr"));
        assertTrue(diagnostics().contains("Docker test artifacts:"));
        assertFollowerStopped();
        long blocked = Long.parseLong(Files.readString(marker).trim());
        assertFalse(diagnostics(), ProcessHandle.of(blocked).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    public void runningControlCancellationKeepsConsoleConnectedThroughCleanup() throws Exception {
        // 用例执行阶段同样先取消本轮 runner，不能用断开控制台代替完整生命周期。
        Path control = Files.createDirectory(this.temporary.resolve("entry-control"));
        Path marker = this.temporary.resolve("controlled-execution");
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_EXEC", marker.toString(), "MOCK_CANCEL_PENDING_READS", "1");
        ProcessBuilder builder = processBuilder(command("dockerTest", false, "--no-build"), overrides);
        this.activeProcess = builder.start();
        awaitFile(marker, Duration.ofSeconds(60));

        // 控制请求不杀共享 daemon；runner 确认取消、归档、清理和日志输出依次完成。
        Files.writeString(control.resolve("cancel.request"), "INT\n");
        assertTrue(diagnostics(), this.activeProcess.waitFor(60, TimeUnit.SECONDS));
        readCalls();
        assertFailure(this.activeProcess.exitValue(), 130);
        assertArchiveBeforeStop();
        assertRunnerCancelledBeforeArchive();
        String execution = Files.readString(outputDirectory().resolve("execution.log"));
        String stdout = "runner | cancelled execution stdout tail";
        String stderr = "runner | cancelled execution stderr tail";
        assertTrue(diagnostics().contains(stdout));
        assertTrue(diagnostics().contains(stderr));
        assertTrue(execution.contains(stdout));
        assertTrue(execution.contains(stderr));
        assertTrue(diagnostics().contains("down stderr"));
        assertTrue(diagnostics().contains("Docker test artifacts:"));
        assertFollowerStopped();
        long blocked = Long.parseLong(Files.readString(marker).trim());
        assertFalse(diagnostics(), ProcessHandle.of(blocked).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    public void runningControlCancellationDrainsAttachedRunnerOutputBeforeArchival() throws Exception {
        // 保持执行中的 attach CLI 连接，取消通过 runner 自己的控制入口传入。
        Path control = Files.createDirectory(this.temporary.resolve("entry-control"));
        Path marker = this.temporary.resolve("attached-execution");
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_EXEC", marker.toString());
        ProcessBuilder builder = processBuilder(command("dockerTest", false, "--no-build"), overrides);
        this.activeProcess = builder.start();
        awaitFile(marker, Duration.ofSeconds(60));

        // runner 先收到取消，再自然退出，最后一段 stdout 和 stderr 均不能丢失。
        Files.writeString(control.resolve("cancel.request"), "INT\n");
        assertTrue(diagnostics(), this.activeProcess.waitFor(60, TimeUnit.SECONDS));
        readCalls();
        assertFailure(this.activeProcess.exitValue(), 130);
        assertEquals("true", Files.readString(Path.of(this.log + ".cancel-attach-alive")));
        String stdout = "runner | cancelled execution stdout tail";
        String stderr = "runner | cancelled execution stderr tail";
        assertTrue(diagnostics().contains(stdout));
        assertTrue(diagnostics().contains(stderr));
        String execution = Files.readString(outputDirectory().resolve("execution.log"));
        assertTrue(execution.contains(stdout));
        assertTrue(execution.contains(stderr));

        // 完整清理确认与归档顺序保持严格，不能仅凭 attach 命令退出判断已完成。
        JsonNode handshake = JSON.readTree(Files.readString(outputDirectory().resolve("runner-handshake.json")));
        assertTrue(handshake.path("cleanupComplete").asBoolean());
        assertEquals("finished", handshake.path("state").path("state").asText());
        assertTrue(handshake.path("state").path("interrupted").asBoolean());
        int publication = this.commands.indexOf(runnerControls("mv").get(0));
        int archive = this.commands.indexOf(archiveCopies().get(0));
        assertTrue(this.commands.toString(), publication < archive);
        assertArchiveBeforeStop();
        assertVolumeRemovals(true);
        assertFollowerStopped();
    }

    @Test
    public void cleanupControlCancellationKeepsCleanupAndReportsInterruptedRun() throws Exception {
        // 正常测试已结束，利用 down 屏障将取消请求精确放在资源清理期间。
        Path control = Files.createDirectory(this.temporary.resolve("entry-control"));
        Path marker = this.temporary.resolve("controlled-cleanup");
        Map<String, String> overrides = Map.of("METEOR_TEST_CONTROL_DIRECTORY", control.toString(),
                "MOCK_BLOCK_DOWN", marker.toString());
        ProcessBuilder builder = processBuilder(command("dockerTest", false, "--no-build"), overrides);
        this.activeProcess = builder.start();
        awaitFile(marker, Duration.ofSeconds(60));

        // 取消改变整轮结论，已开始的清理继续执行，不能将原来的零退出码报告为通过。
        Files.writeString(control.resolve("cancel.request"), "INT\n");
        Files.writeString(Path.of(marker + ".release"), "continue");
        assertTrue(diagnostics(), this.activeProcess.waitFor(60, TimeUnit.SECONDS));
        readCalls();
        assertFailure(this.activeProcess.exitValue(), 130);
        JsonNode launcher = JSON.readTree(Files.readString(outputDirectory().resolve("launcher.json")));
        assertEquals(130, launcher.path("exitCode").asInt());
        String report = Files.readString(outputDirectory().resolve("report.md"));
        assertTrue(report.contains("结果：失败"));

        // 后半段卷清理与日志收尾均完成，控制台仍显示最终归档位置。
        assertVolumeRemovals(true);
        assertTrue(Files.readString(outputDirectory().resolve("remaining-containers.txt")).isBlank());
        assertTrue(Files.readString(outputDirectory().resolve("remaining-networks.txt")).isBlank());
        assertTrue(diagnostics().contains("down stdout"));
        assertTrue(diagnostics().contains("Docker test artifacts:"));
        assertFollowerStopped();
    }

    @Test
    public void daemonShutdownHookArchivesAndCleansStartedProject() throws Exception {
        // Gradle daemon JVM 直接退出时由同一所有权的 shutdown hook 完成收尾。
        assertCancelledRun(true);
    }

    @Test
    public void successRemovesRunVolumesButKeepsGradleCache() throws Exception {
        // 正常结束移除本轮三个卷，不删除共享 Gradle 缓存。
        assertSuccess(runLauncher("--no-build"));
        assertVolumeRemovals(true);
    }

    @Test
    public void keepWorkspacePreservesOnlyRequestedWorkspace() throws Exception {
        // 调试选项只保留工作区，不保留运行缓存和容器。
        assertSuccess(runLauncher("--no-build", "--keep-workspace"));
        assertVolumeRemovals(false);
    }

    @Test
    public void invalidCacheKeyIsRejectedBeforeCreatingResources() throws Exception {
        // 非法缓存名不能影响其它目录或 Docker 资源。
        assertFailure(runTask("dockerTest", Map.of("METEOR_TEST_CACHE_KEY", "../unsafe"), "--no-build"));
        assertTrue(composeCommands("start").isEmpty());
    }

    @Test
    public void explicitPlatformOverridesDockerArchitecture() throws Exception {
        // 显式目标平台同时确定镜像校验和缓存架构。
        Map<String, String> overrides = Map.of("MOCK_IMAGE_PLATFORM", "linux/arm64");
        assertSuccess(runTask("dockerTest", overrides, "--no-build", "--platform", "linux/arm64"));
        JsonNode environment = lastEnvironment();
        assertEquals("linux/arm64", environment.path("METEOR_TEST_PLATFORM").asText());
        assertEquals("arm64", environment.path("METEOR_TEST_ARCH").asText());
    }

    @Test
    public void failedDefaultStartupPreservesOriginalExitCode() throws Exception {
        // 默认启动未创建 runner 时，保留 Compose 原始失败码并回收本轮资源。
        Map<String, String> overrides = Map.of("MOCK_START_EXIT", "11", "MOCK_NO_RUNNER", "1");
        assertFailure(runTask("dockerTest", overrides), 11);
        assertTrue(composeCommands("exec").isEmpty());
        assertEquals(1, composeCommands("down").size());
    }

    @Test
    public void dockerStdoutAndStderrAreArchivedInCommandLog() throws Exception {
        // 人类诊断日志同时保留命令的两个输出流。
        assertSuccess(runLauncher("--no-build"));
        String startup = Files.readString(outputDirectory().resolve("startup.log"));
        assertTrue(startup.contains("start stdout"));
        assertTrue(startup.contains("start stderr"));
    }

    @Test
    public void startupServiceLogsReachConsoleBeforeStartupCompletes() throws Exception {
        // 环境尚在等待启动时，准备和构建容器的两个输出流就应显示在控制台。
        Path marker = this.temporary.resolve("startup-blocked");
        Map<String, String> overrides = Map.of("MOCK_BLOCK_START", marker.toString());
        ProcessBuilder builder = processBuilder(command("dockerTest", false, "--no-build"), overrides);
        this.activeProcess = builder.start();
        awaitFile(marker, Duration.ofSeconds(60));
        awaitConsole("prepare | workspace preparation stdout", Duration.ofSeconds(10));
        awaitConsole("java-meteor | Java build stderr", Duration.ofSeconds(10));
        assertTrue(diagnostics(), this.activeProcess.isAlive());

        // 继续启动后，运行日志仍完整归档，不能仅在收尾阶段补打印。
        Files.writeString(Path.of(marker + ".release"), "continue");
        assertTrue(this.activeProcess.waitFor(60, TimeUnit.SECONDS));
        readCalls();
        assertSuccess(this.activeProcess.exitValue());
        String services = Files.readString(outputDirectory().resolve("startup-services.log"));
        String errors = Files.readString(outputDirectory().resolve("startup-services.stderr.log"));
        assertTrue(services.contains("prepare | workspace preparation stdout"));
        assertTrue(errors.contains("java-meteor | Java build stderr"));
    }

    @Test
    public void successfulRunStopsOwnedServiceLogFollower() throws Exception {
        // 测试完成时关闭本轮日志订阅，不能留下持有 Docker 日志连接的子进程。
        assertSuccess(runLauncher("--no-build"));
        assertFollowerStopped();
    }

    @Test
    public void unexpectedLogFollowerExitCannotReportSuccess() throws Exception {
        // 日志订阅自行退出后，即便返回零也不能将缺失运行日志的批次记作成功。
        assertFailure(runTask("dockerTest", Map.of("MOCK_LOGS_EXIT", "0"), "--no-build"), 1);
        assertFollowerStopped();
    }

    @Test
    public void stderrDiagnosticsDoNotCorruptMachineReadableStdout() throws Exception {
        // Docker 的 stderr 不得混入平台或 JSON 机器输出。
        assertSuccess(runTask("dockerTest", Map.of("MOCK_DIAGNOSTIC_STDERR", "1"), "--no-build"));
        assertEquals("linux/amd64", lastEnvironment().path("METEOR_TEST_PLATFORM").asText());
        List<String> images = Files.readAllLines(outputDirectory().resolve("images.jsonl"));
        assertEquals(2, images.size());
        for (String image : images) {
            assertEquals(JSON.createObjectNode(), JSON.readTree(image));
        }
    }

    @Test
    public void namedCommandStderrReachesConsoleWhenStdoutIsNotForwarded() throws Exception {
        // 版本命令的 stdout 只供归档，stderr 仍需立即显示，不能被命名日志静默吞掉。
        Map<String, String> overrides = Map.of("MOCK_VERSION_DIAGNOSTIC", "1");
        assertSuccess(runTask("dockerTest", overrides, "--no-build"));

        // 控制台与对应命令归档同时保留该次诊断。
        String diagnostic = "Docker version diagnostic on stderr";
        assertTrue(diagnostics().contains(diagnostic));
        String version = Files.readString(outputDirectory().resolve("docker-version.txt"));
        assertTrue(version.contains(diagnostic));
    }

    @Test
    public void failedVolumeRemovalCannotReportSuccess() throws Exception {
        // 卷移除命令失败不能被成功的容器关闭掩盖。
        assertFailure(runTask("dockerTest", Map.of("MOCK_VOLUME_RM_EXIT", "12"), "--no-build"), 1);
        assertTrue(diagnostics().contains("Resource cleanup failed"));
        assertTrue(diagnostics().contains("volume removal failed"));
    }

    @Test
    public void recoveryCleansRecordedRunWithoutRewritingFailure() throws Exception {
        // 恢复只使用已归档的项目身份，不改写原始测试结论。
        assertFailure(runTask("dockerTest", Map.of("MOCK_EXEC_EXIT", "7"), "--no-build"), 7);
        Path artifacts = outputDirectory();
        Files.writeString(this.log, "");

        // 用户通过独立恢复任务重新收尾。
        assertSuccess(runTask("dockerCleanup", Map.of()));
        assertEquals("7", Files.readString(artifacts.resolve("exit-code.txt")).trim());
        assertEquals(1, composeCommands("down").size());
        assertTrue(composeCommands("start").isEmpty());
        assertTrue(composeCommands("exec").isEmpty());
    }

    @Test
    public void recoveryOfCancelledRunnerPreservesFailureAndSucceeds() throws Exception {
        // 已取消的 runner 终态为 130，资源恢复成功与原测试失败分别记录。
        assertFailure(runTask("dockerTest", Map.of("MOCK_EXEC_EXIT", "130"), "--no-build"), 130);
        Path artifacts = outputDirectory();
        Files.writeString(this.log, "");

        // 恢复只确认资源清理，不把旧 runner 的退出码当作本次清理错误。
        assertSuccess(runTask("dockerCleanup", Map.of()));
        assertEquals("130", Files.readString(artifacts.resolve("exit-code.txt")).trim());
        assertEquals(1, composeCommands("down").size());
        assertTrue(cancellationCopies().isEmpty());
        assertTrue(composeCommands("exec").isEmpty());
    }

    @Test
    public void recoveryRejectsMissingRecordedEnvironmentBeforeDocker() throws Exception {
        // 固定目录没有本仓库的环境记录时，恢复任务不能猜测资源归属。
        assertFailure(runTask("dockerCleanup", Map.of()));
        assertTrue(this.commands.isEmpty());
    }

    @Test
    public void recoveryRejectsMalformedRecordedRunBeforeDocker() throws Exception {
        // 内部批次只用于身份校验，篡改为路径不能影响固定归档边界。
        assertSuccess(runLauncher("--no-build"));
        String runId = lastEnvironment().path("METEOR_TEST_RUN_ID").asText();
        Path configuration = outputDirectory().resolve("compose.env");
        String recorded = Files.readString(configuration);
        String changed = recorded.replace("METEOR_TEST_RUN_ID=" + runId + "\n",
                "METEOR_TEST_RUN_ID=../foreign-run\n");
        Files.writeString(configuration, changed);
        Files.writeString(this.log, "");

        // 读取归档失败时不能执行 Docker 或重写原测试结论。
        assertFailure(runTask("dockerCleanup", Map.of()));
        assertTrue(this.commands.isEmpty());
        assertEquals("0", Files.readString(outputDirectory().resolve("exit-code.txt")).trim());
    }

    @Test
    public void recoveryRejectsProjectOwnedByAnotherRun() throws Exception {
        // 归档中的项目身份被替换时拒绝触碰任何 Docker 资源。
        assertSuccess(runLauncher("--no-build"));
        Path artifacts = outputDirectory();
        Path configuration = artifacts.resolve("compose.env");
        String recorded = Files.readString(configuration);
        String foreign = recorded.replace("METEOR_TEST_PROJECT=meteor-test\n", "METEOR_TEST_PROJECT=foreign\n");
        Files.writeString(configuration, foreign);
        Files.writeString(this.log, "");

        // 原有测试结果保持为成功，恢复任务另行失败。
        assertFailure(runTask("dockerCleanup", Map.of()));
        assertTrue(this.commands.isEmpty());
        assertEquals("0", Files.readString(artifacts.resolve("exit-code.txt")).trim());
    }

    @Test
    public void recoveryOfOldRunCannotStopCurrentEnvironmentContainer() throws Exception {
        // 旧归档可以读取，但固定环境的容器已归新批次所有。
        assertSuccess(runLauncher("--no-build"));
        Path artifacts = outputDirectory();
        Files.writeString(this.log, "");
        Map<String, String> overrides = Map.of("MOCK_EXISTING_CONTAINER_RUN", "newer-run");

        // 恢复不能对新容器发取消、归档或 Compose down，也不能删固定卷。
        assertFailure(runTask("dockerCleanup", overrides));
        assertNoEnvironmentChanges();
        assertTrue(cancellationCopies().isEmpty());
        assertTrue(archiveCopies().isEmpty());
        assertEquals("0", Files.readString(artifacts.resolve("exit-code.txt")).trim());
    }

    @Test
    public void recoveryOfOldRunCannotRemoveCurrentEnvironmentNetwork() throws Exception {
        // 固定网络属于新批次时，旧归档的恢复同样不得触碰当前环境。
        assertSuccess(runLauncher("--no-build"));
        Path artifacts = outputDirectory();
        Files.writeString(this.log, "");
        Map<String, String> overrides = Map.of("MOCK_EXISTING_NETWORK_RUN", "newer-run");
        assertFailure(runTask("dockerCleanup", overrides));
        assertNoEnvironmentChanges();
        assertTrue(archiveCopies().isEmpty());
    }

    @Test
    public void recoveryOfOldRunCannotRemoveCurrentEnvironmentVolume() throws Exception {
        // 卷也保留批次所有权；无运行容器不代表可以删其它批次的工作区。
        assertSuccess(runLauncher("--no-build"));
        Path artifacts = outputDirectory();
        Files.writeString(this.log, "");
        Map<String, String> overrides = Map.of("MOCK_EXISTING_VOLUME_RUN", "newer-run");
        assertFailure(runTask("dockerCleanup", overrides));
        assertNoEnvironmentChanges();
        assertTrue(archiveCopies().isEmpty());
    }

    @Test
    public void recoveryOfFixedEnvironmentRejectsMissingRunLabel() throws Exception {
        // 同名固定环境缺少批次标签时不能假定由旧归档拥有。
        assertSuccess(runLauncher("--no-build"));
        Path artifacts = outputDirectory();
        Files.writeString(this.log, "");
        Map<String, String> overrides = Map.of("MOCK_EXISTING_CONTAINER_RUN", "");
        assertFailure(runTask("dockerCleanup", overrides));
        assertNoEnvironmentChanges();
    }

    @Test
    public void occupiedEnvironmentLockRejectsRecoveryWithoutTouchingDocker() throws Exception {
        // 先保存一份可恢复归档，再模拟固定环境被另一个入口持有。
        assertSuccess(runLauncher("--no-build"));
        Path artifacts = outputDirectory();
        Files.writeString(this.log, "");
        Path lockFile = this.repository.resolve("meteor-test/build/meteor-test-control/docker.lock");
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            assertFailure(runTask("dockerCleanup", Map.of()));
            assertTrue(this.commands.isEmpty());
        }
    }

    @Test
    public void resourceOwnershipChangedDuringRunCannotBeCleanedByOldOwner() throws Exception {
        // 即便执行期间外部替换了同名资源，收尾也只能管理本轮实际拥有的环境。
        Map<String, String> overrides = Map.of("MOCK_REPLACED_RESOURCE_RUN", "newer-run");
        assertFailure(runTask("dockerTest", overrides, "--no-build"));
        assertEquals(1, composeCommands("exec").size());

        // 归属不符必须失败，不能取消、归档或销毁新批次的环境。
        assertTrue(composeCommands("down").isEmpty());
        assertTrue(commandsStarting("volume", "rm").isEmpty());
        assertTrue(cancellationCopies().isEmpty());
        assertTrue(archiveCopies().isEmpty());
    }

    @Test
    public void recoveryFailureRecordsStatusWithoutReplacingOriginalResult() throws Exception {
        // 重复清理失败在独立目录记录，原始错误码仍保留。
        assertFailure(runTask("dockerTest", Map.of("MOCK_EXEC_EXIT", "7"), "--no-build"), 7);
        Path artifacts = outputDirectory();
        Map<String, String> overrides = Map.of("MOCK_DOWN_EXIT", "6");
        assertFailure(runTask("dockerCleanup", overrides));
        assertEquals("7", Files.readString(artifacts.resolve("exit-code.txt")).trim());
        try (Stream<Path> paths = Files.walk(artifacts)) {
            Path recovery = paths.filter(path -> path.endsWith("recovery-exit-code.txt")).findFirst().orElseThrow();
            assertEquals("1", Files.readString(recovery).trim());
        }
    }

    @Test
    public void largeDiagnosticsStreamToArchiveWithoutBlocking() throws Exception {
        // 超过管道容量的诊断输出持续落盘，不能阻塞宿主命令。
        assertSuccess(runTask("dockerTest", Map.of("MOCK_LARGE_STDERR", "1"), "--no-build"));
        assertTrue(Files.size(outputDirectory().resolve("startup.log")) > 1_000_000);
    }

    @Test
    public void finishedRunnerIsArchivedWithoutCancellation() throws Exception {
        // 已完成且确认清理的 runner 无需接收额外取消请求。
        assertSuccess(runLauncher("--no-build"));
        assertTrue(cancellationCopies().isEmpty());
        assertTrue(runnerControls("mv").isEmpty());
        assertEquals(1, archiveCopies().size());
    }

    @Test
    public void cancellationTimeoutRetainsWorkspaceAndFailsRun() throws Exception {
        // 无法确认运行终态时保留工作区，控制命令超时不能报告测试成功。
        Map<String, String> overrides = Map.of("MOCK_RUNNER_STATE", "running", "MOCK_CANCEL_EXIT", "124");
        assertFailure(runTask("dockerTest", overrides, "--no-build"), 1);
        assertEquals(1, cancellationCopies().size());
        assertEquals(1, runnerControls("mv").size());
        assertVolumeRemovals(false);
    }

    @Test
    public void runnerStateOwnedByAnotherRunCannotBeCancelled() throws Exception {
        // 身份不匹配时拒绝取消其它运行，同时保留当前工作区以便诊断。
        Map<String, String> overrides = Map.of("MOCK_RUNNER_RUN_ID", "foreign-run");
        assertFailure(runTask("dockerTest", overrides, "--no-build"), 1);
        assertTrue(cancellationCopies().isEmpty());
        assertTrue(runnerControls("mv").isEmpty());
        assertVolumeRemovals(false);
    }

    @Test
    public void reportAggregationFailureWritesMarkdownBeforeGradleFails() throws Exception {
        // 使用真实聚合器与已解析的运行依赖，不启动 Docker 或引入额外编译域。
        List<String> classpath = new ArrayList<>();
        for (Class<?> type : List.of(NxnMain.class, ObjectMapper.class, JsonFactory.class,
                JsonProperty.class, StringUtils.class)) {
            Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            classpath.add(location.toString());
        }
        String configuration = "plugins { id 'application' }\n";
        configuration += "application { mainClass = '" + NxnMain.class.getName() + "' }\n";
        configuration += applyLauncher();
        configuration += "tasks.named('nxnReport') { classpath = files(";
        configuration += JSON.writeValueAsString(classpath) + ") }\n";
        Files.writeString(this.repository.resolve("build.gradle"), configuration);

        // 本轮已选择测试但没有工作组证据，聚合器必须保留失败原因并非零退出。
        String runId = "20261028t000000z-00000000";
        Path archive = outputDirectory();
        Path manifest = archive.resolve("meteor-test/build/groups/manifest.json");
        Files.createDirectories(manifest.getParent());
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("schemaVersion", 3);
        selection.put("runId", runId);
        selection.put("userIncludes", List.of("example.MissingTest"));
        selection.put("selectedClasses", List.of("example.MissingTest"));
        selection.put("discoveredClasses", List.of("example.MissingTest"));
        selection.put("selectedSelections", List.of("example.MissingTest"));
        selection.put("groups", List.of());
        Files.writeString(manifest, JSON.writeValueAsString(selection));
        Files.writeString(archive.resolve("compose.env"), "METEOR_TEST_RUN_ID=" + runId + "\n");
        assertFailure(runTask("nxnReport", Map.of()));

        // JavaExec 失败以后仍生成 Markdown，报告明确列出缺失分组的原因。
        String markdown = Files.readString(this.repository.resolve("meteor-test/report.md"));
        assertFalse(markdown.contains(runId));
        assertTrue(markdown.contains("Group IDs must be present and unique"));
        String summaryPath = "build/docker-runs/meteor-test/build/interoperability/summary.json";
        assertTrue(markdown.contains("[summary.json](" + summaryPath + ")"));
        assertTrue(markdown.contains("[本轮归档](build/docker-runs)"));
        assertFalse(markdown.contains(this.repository.toString()));
        assertFalse(Files.exists(archive.resolve("reports")));
        assertTrue(Files.isRegularFile(archive.resolve("meteor-test/build/interoperability/report.md")));
        assertTrue(this.commands.isEmpty());
    }

    private int runLauncher(String... arguments) throws Exception {
        return runTask("dockerTest", Map.of(), arguments);
    }

    private int runTask(String task, Map<String, String> overrides, String... arguments) throws Exception {
        ProcessBuilder builder = processBuilder(command(task, false, arguments), overrides);
        this.activeProcess = builder.start();
        boolean completed = this.activeProcess.waitFor(90, TimeUnit.SECONDS);
        assertTrue("Gradle timed out: " + diagnostics(), completed);
        readCalls();
        return this.activeProcess.exitValue();
    }

    private List<String> command(String task, boolean isolated, String... arguments) {
        Path cache = isolated ? this.temporary.resolve("cancel-gradle") : gradleCache;
        List<String> command = new ArrayList<>();
        command.add(gradle.toString());
        command.addAll(List.of("--project-dir", this.repository.toString(), "--gradle-user-home", cache.toString()));
        command.addAll(List.of("--no-daemon", "--console=plain", "--stacktrace"));
        command.add("-Duser.home=" + this.temporary.resolve("home"));
        command.add(task);
        command.addAll(List.of(arguments));
        return command;
    }

    private ProcessBuilder processBuilder(List<String> command, Map<String, String> overrides) throws Exception {
        this.output = Files.createTempFile(this.temporary, "stdout-", ".log");
        this.error = Files.createTempFile(this.temporary, "stderr-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().clear();
        builder.environment().putAll(this.environment);
        builder.environment().putAll(overrides);
        builder.redirectOutput(this.output.toFile());
        builder.redirectError(this.error.toFile());
        return builder;
    }

    private void assertCancelledRun(boolean terminateDaemon) throws Exception {
        // 独立进程组与 Gradle 用户目录确保信号不能波及其它测试构建。
        Path marker = this.temporary.resolve("runner-execution-started");
        List<String> command = new ArrayList<>();
        command.add(findExecutable("setsid").toString());
        command.addAll(command("dockerTest", true, "--no-build"));
        Map<String, String> overrides = Map.of("MOCK_BLOCK_EXEC", marker.toString(), "MOCK_CANCEL_PENDING_READS", "1");
        ProcessBuilder builder = processBuilder(command, overrides);
        this.activeProcess = builder.start();
        awaitFile(marker, Duration.ofSeconds(60));

        // 分别覆盖终端取消和 daemon 自身退出，两者都必须先归档再关闭项目。
        if (terminateDaemon) {
            readCalls();
            JsonNode startup = this.calls.stream().filter(call -> strings(call.path("arguments")).contains("run"))
                    .findFirst().orElseThrow();
            long owner = startup.path("parentPid").asLong();
            String ownerCommand = Files.readString(Path.of("/proc", Long.toString(owner), "cmdline"));
            assertTrue(ownerCommand.contains("GradleDaemon"));
            assertTrue(ProcessHandle.of(owner).orElseThrow().destroy());
        } else {
            signalGroup(this.activeProcess.pid(), "TERM");
        }
        assertTrue(this.activeProcess.waitFor(45, TimeUnit.SECONDS));
        assertNotEquals(diagnostics(), 0, this.activeProcess.exitValue());
        Path exit = outputDirectory().resolve("exit-code.txt");
        awaitFile(exit, Duration.ofSeconds(30));
        readCalls();
        assertArchiveBeforeStop();
        assertRunnerCancelledBeforeArchive();
        assertEquals("143", Files.readString(exit).trim());
        assertTrue(Files.isRegularFile(outputDirectory().resolve("meteor-test/build/archived.txt")));
        long blocked = Long.parseLong(Files.readString(marker).trim());
        assertFalse(diagnostics(), ProcessHandle.of(blocked).map(ProcessHandle::isAlive).orElse(false));
        assertFollowerStopped();
    }

    private void assertFollowerStopped() throws Exception {
        List<JsonNode> followers = this.calls.stream().filter(call -> {
            List<String> arguments = strings(call.path("arguments"));
            return arguments.contains("logs") && arguments.contains("--follow");
        }).toList();
        assertEquals(diagnostics(), 1, followers.size());
        long pid = followers.get(0).path("pid").asLong();
        assertFalse("Service log follower is still running: " + pid + "\n" + diagnostics(),
                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private void awaitConsole(String text, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!diagnostics().contains(text) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("Missing console output: " + text + "\n" + diagnostics(), diagnostics().contains(text));
    }

    private void awaitFile(Path file, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue("Missing " + file + "\n" + diagnostics(), Files.exists(file));
    }

    private void readCalls() throws Exception {
        this.calls.clear();
        this.commands.clear();
        if (!Files.exists(this.log)) {
            return;
        }
        for (String line : Files.readAllLines(this.log)) {
            JsonNode call = JSON.readTree(line);
            this.calls.add(call);
            this.commands.add(strings(call.path("arguments")));
        }
    }

    private List<List<String>> composeCommands(String verb) {
        List<List<String>> selected = new ArrayList<>();
        for (List<String> command : this.commands) {
            if (StringUtils.equals(command.get(0), "compose") && command.contains(verb)) {
                selected.add(command);
            }
        }
        return selected;
    }

    private List<List<String>> commandsStarting(String... prefix) {
        List<List<String>> selected = new ArrayList<>();
        for (List<String> command : this.commands) {
            if (command.size() >= prefix.length && command.subList(0, prefix.length).equals(List.of(prefix))) {
                selected.add(command);
            }
        }
        return selected;
    }

    private void assertNoEnvironmentChanges() {
        assertTrue(composeCommands("create").isEmpty());
        assertTrue(composeCommands("start").isEmpty());
        assertTrue(composeCommands("exec").isEmpty());
        assertTrue(composeCommands("down").isEmpty());
        assertTrue(commandsStarting("volume", "rm").isEmpty());
    }

    private JsonNode lastEnvironment() {
        return this.calls.get(this.calls.size() - 1).path("environment");
    }

    private Path outputDirectory() {
        return this.repository.resolve("meteor-test/build/docker-runs");
    }

    private void assertArchiveBeforeStop() {
        int copy = this.commands.indexOf(archiveCopies().get(0));
        int stop = this.commands.indexOf(composeCommands("down").get(0));
        assertTrue(this.commands.toString(), copy < stop);
    }

    private void assertRunnerCancelledBeforeArchive() throws Exception {
        int cancelCopy = this.commands.indexOf(cancellationCopies().get(0));
        int cancelPublish = this.commands.indexOf(runnerControls("mv").get(0));
        int archive = this.commands.indexOf(archiveCopies().get(0));
        int stop = this.commands.indexOf(composeCommands("down").get(0));
        int terminalCheck = -1;
        for (int index = cancelPublish + 1; index < archive; index++) {
            List<String> query = this.commands.get(index);
            if (StringUtils.equals(query.get(0), "exec") && query.contains("cat")) {
                terminalCheck = index;
            }
        }
        assertTrue(this.commands.toString(), cancelCopy < cancelPublish && cancelPublish < terminalCheck);
        assertTrue(this.commands.toString(), terminalCheck < archive && archive < stop);

        // 最终握手证明 runner 已完成全部清理，轮询次数不代表生命周期完成。
        Path handshake = outputDirectory().resolve("runner-handshake.json");
        JsonNode terminal = JSON.readTree(Files.readString(handshake));
        assertTrue(terminal.path("cleanupComplete").asBoolean());
        assertEquals("finished", terminal.path("state").path("state").asText());
        assertTrue(terminal.path("state").path("cleanupComplete").asBoolean());
    }

    private List<List<String>> cancellationCopies() {
        List<List<String>> result = new ArrayList<>();
        for (List<String> command : commandsStarting("cp")) {
            String destination = command.get(command.size() - 1);
            if (destination.endsWith("groups.cancel.upload")) {
                result.add(command);
            }
        }
        return result;
    }

    private List<List<String>> archiveCopies() {
        List<List<String>> result = new ArrayList<>();
        for (List<String> command : commandsStarting("cp")) {
            if (command.get(1).endsWith(":/workspace/meteor/meteor-test/build/.")) {
                result.add(command);
            }
        }
        return result;
    }

    private List<List<String>> runnerControls(String verb) {
        List<List<String>> result = new ArrayList<>();
        for (List<String> command : commandsStarting("exec")) {
            if (command.contains(verb)) {
                result.add(command);
            }
        }
        return result;
    }

    private void assertVolumeRemovals(boolean workspace) {
        List<String> expected = new ArrayList<>();
        expected.add("meteor-test-peer");
        expected.add("meteor-test-maven");
        if (workspace) {
            expected.add("meteor-test-workspace");
        }
        List<String> actual = new ArrayList<>();
        for (List<String> command : commandsStarting("volume", "rm")) {
            actual.add(command.get(command.size() - 1));
        }
        assertEquals(expected, actual);
    }

    private void assertSuccess(int exit) throws Exception {
        assertEquals(diagnostics(), 0, exit);
    }

    private void assertFailure(int exit) throws Exception {
        assertNotEquals(diagnostics(), 0, exit);
    }

    private void assertFailure(int exit, int original) throws Exception {
        assertFailure(exit);
        assertEquals(Integer.toString(original), Files.readString(outputDirectory().resolve("exit-code.txt")).trim());
    }

    private String diagnostics() throws Exception {
        String stdout = this.output == null ? "" : Files.readString(this.output);
        String stderr = this.error == null ? "" : Files.readString(this.error);
        return stdout + stderr;
    }

    private static String valueAfter(List<String> command, String option) {
        return command.get(command.indexOf(option) + 1);
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(value -> result.add(value.asText()));
        return result;
    }

    private static String applyLauncher() {
        String script = LAUNCHER.toString().replace("\\", "\\\\").replace("'", "\\'");
        return "apply from: '" + script + "'\n";
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static Path findRepository() {
        String explicit = System.getProperty("meteor.test.repository");
        if (explicit != null) {
            return Path.of(explicit).toAbsolutePath();
        }
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("meteor-test/nxn.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate the Meteor repository.");
    }

    private static Path findGradle() throws Exception {
        String explicit = System.getProperty("meteor.test.gradle.executable");
        if (explicit == null) {
            explicit = System.getenv("METEOR_TEST_GRADLE_EXECUTABLE");
        }
        if (explicit != null) {
            return Path.of(explicit).toRealPath();
        }
        Path properties = REPOSITORY.resolve("gradle/wrapper/gradle-wrapper.properties");
        Matcher version = Pattern.compile("gradle-([\\d.]+)-bin").matcher(Files.readString(properties));
        if (!version.find()) {
            throw new IllegalStateException("Missing Gradle wrapper distribution version.");
        }
        String cache = System.getenv("GRADLE_USER_HOME");
        Path gradleHome = cache == null ? Path.of(System.getProperty("user.home"), ".gradle") : Path.of(cache);
        Path home = gradleHome.resolve("wrapper/dists");
        Path distributions = home.resolve("gradle-" + version.group(1) + "-bin");
        try (Stream<Path> paths = Files.walk(distributions)) {
            return paths.filter(path -> path.endsWith("bin/gradle")).findFirst().orElseThrow();
        }
    }

    private static Path findExecutable(String name) {
        for (String directory : System.getenv("PATH").split(Pattern.quote(File.pathSeparator))) {
            Path executable = Path.of(directory, name);
            if (Files.isExecutable(executable)) {
                return executable.toAbsolutePath();
            }
        }
        throw new IllegalStateException("Missing Linux test tool: " + name);
    }

    private static void signalGroup(long pid, String signal) throws Exception {
        Process command = new ProcessBuilder(findExecutable("kill").toString(), "-" + signal,
                "--", "-" + pid).start();
        assertTrue(command.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, command.exitValue());
    }

    private static void deleteTree(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
