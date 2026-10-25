package net.hasor.meteor.test.nxn;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** 真实 Gradle 准备任务的文件隔离测试，不启动 Docker 或解析 SDK。 */
public class NxnPreparationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path REPOSITORY = findRepository();
    private static Path gradle;
    private static Path gradleCache;
    private Path directory;
    private Path source;
    private Path preparationBuild;
    private Path workspace;
    private Path peers;
    private Path maven;
    private Path seed;

    @BeforeClass
    public static void prepareGradle() throws Exception {
        // 同一测试类借用已安装的 Wrapper 分发，缓存归本类所有。
        gradle = findGradle();
        gradleCache = Files.createTempDirectory("meteor-gradle-preparation-cache-");
    }

    @AfterClass
    public static void releaseGradle() throws Exception {
        if (gradleCache == null) {
            return;
        }
        // 只停止本测试用户目录的 daemon，不干扰仓库或其它测试。
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
        // 独立使用生产准备脚本，输入源码的构建配置不参与准备过程。
        this.directory = Files.createTempDirectory("meteor-gradle-preparation-");
        this.source = Files.createDirectory(this.directory.resolve("source"));
        Files.copy(REPOSITORY.resolve("meteor-test/build.gradle"), this.source.resolve("build.gradle"));
        Files.copy(REPOSITORY.resolve("meteor-test/nxn.gradle"), this.source.resolve("nxn.gradle"));
        Files.writeString(this.source.resolve("settings.gradle"), "rootProject.name = 'preparation-fixture'\n");
        Files.writeString(this.source.resolve("gradle.properties"), "lib.jackson=2.18.4\nlib.cobble=5.0.5-SNAPSHOT\n");
        this.preparationBuild = Files.createDirectory(this.directory.resolve("preparation-build"));
        // 临时工程位于仓库 build 内，显式 settings 阻止 Gradle 向上采用真实仓库的构建。
        Files.writeString(this.preparationBuild.resolve("settings.gradle"),
                "rootProject.name = 'isolated-preparation-build'\n");
        Files.copy(REPOSITORY.resolve("meteor-test/docker/prepare.gradle"),
                this.preparationBuild.resolve("build.gradle"));
        this.workspace = this.directory.resolve("workspace");
        this.peers = this.directory.resolve("peers");
        this.maven = this.directory.resolve("maven");
        this.seed = this.directory.resolve("seed");
    }

    @After
    public void releaseFixture() throws Exception {
        deleteTree(this.directory);
    }

    @Test
    public void snapshotExcludesGeneratedOutputsAndIndependentExamples() throws Exception {
        // 只保留源码输入；构建目录、独立示例、秘密配置和依赖缓存都在排除范围。
        String peer = "meteor-test/peer/java/Peer.java";
        for (String name : List.of(peer, "meteor/build/old.jar", "met-example/Example.java",
                ".env.local", "meteor-test/peer/node/node_modules/old.js")) {
            Path input = this.source.resolve(name);
            Files.createDirectories(input.getParent());
            Files.writeString(input, name);
        }
        Map<String, Object> result = prepare();

        // 准备证据只包含被复制的源文件，不混入本轮生成文件。
        assertSuccess(result);
        JsonNode manifest = JSON.readTree(preparation().resolve("source-sha256.json").toFile());
        List<String> expected = List.of("build.gradle", "gradle.properties", "nxn.gradle", peer, "settings.gradle");
        List<String> actual = new ArrayList<>();
        manifest.fieldNames().forEachRemaining(actual::add);
        assertEquals(expected.stream().sorted().toList(), actual);
        assertEquals(5, JSON.readTree(preparation().resolve("preparation.json").toFile()).path("sourceFiles").asInt());
    }

    @Test
    public void sourceAndMavenSeedStayIsolatedFromWritableCopies() throws Exception {
        // 只把种子仓库借入，所有构建写入都必须落到私有缓存。
        Files.writeString(this.source.resolve("settings.gradle"), "rootProject.name = 'original'\n");
        Path artifact = this.seed.resolve("module/artifact.jar");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, new byte[] { 1, 2, 3 });
        Map<String, Object> result = prepare();

        // 修改复制后的文件不会改变只读输入。
        assertSuccess(result);
        Path copied = this.maven.resolve("repository/net/hasor/module/artifact.jar");
        Files.write(copied, new byte[] { 4, 5, 6 });
        Files.writeString(this.workspace.resolve("settings.gradle"), "changed");
        assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(artifact));
        assertEquals("rootProject.name = 'original'\n", Files.readString(this.source.resolve("settings.gradle")));
    }

    @Test
    public void existingWorkspaceCannotBecomeTestInput() throws Exception {
        // 遗留测试结果使目标目录不再是新的快照。
        Files.createDirectories(this.workspace);
        Files.writeString(this.workspace.resolve("stale-result.xml"), "old");
        Map<String, Object> result = prepare();

        // 在复制之前失败，不覆盖旧证据。
        assertNotEquals(0, result.get("exitCode"));
        String diagnostics = result.get("output").toString();
        assertTrue(diagnostics, diagnostics.contains("A test workspace must be fresh:"));
        assertEquals("old", Files.readString(this.workspace.resolve("stale-result.xml")));
    }

    @Test
    public void dockerCreatedEmptyWorkingDirectoriesAreAccepted() throws Exception {
        // Docker 预创建 WORKDIR 的空父目录不属于遗留输入。
        Files.createDirectories(this.workspace.resolve("meteor-test"));
        Map<String, Object> result = prepare();

        // 新快照仍由当前只读源码建立。
        assertSuccess(result);
        String copied = Files.readString(this.workspace.resolve("settings.gradle"));
        assertEquals("rootProject.name = 'preparation-fixture'\n", copied);
    }

    private Map<String, Object> prepare() throws Exception {
        // 只执行准备任务，不触发 Java、SDK、Docker 或依赖解析任务。
        List<String> command = new ArrayList<>();
        command.add(gradle.toString());
        command.add("--no-daemon");
        command.add("--console=plain");
        command.add("--offline");
        command.add("--gradle-user-home");
        command.add(gradleCache.toString());
        command.add("prepareWorkspace");
        command.add("-Pnxn.source=" + this.source);
        command.add("-Pnxn.workspace=" + this.workspace);
        command.add("-Pnxn.peers=" + this.peers);
        command.add("-Pnxn.maven=" + this.maven);
        command.add("-Pnxn.mavenSeed=" + this.seed);
        Path output = this.directory.resolve("gradle.log");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(this.preparationBuild.toFile());
        builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
        builder.redirectErrorStream(true);
        builder.redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertTrue("Preparation task timed out", process.waitFor(90, TimeUnit.SECONDS));
            return Map.of("exitCode", process.exitValue(), "output", Files.readString(output));
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
            }
        }
    }

    private Path preparation() {
        return this.workspace.resolve("meteor-test/build/preparation");
    }

    private static void assertSuccess(Map<String, Object> result) {
        assertEquals(result.get("output").toString(), 0, result.get("exitCode"));
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
        Path distributions = gradleHome.resolve("wrapper/dists/gradle-" + version.group(1) + "-bin");
        try (Stream<Path> paths = Files.walk(distributions)) {
            return paths.filter(path -> path.endsWith("bin/gradle")).findFirst().orElseThrow();
        }
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
