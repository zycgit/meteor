package net.hasor.meteor.test.nxn;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Gradle 启动测试的独立 Docker CLI 夹具，不连接 Docker daemon。 */
public class FakeDockerMain {
    public static void main(String[] arguments) throws Exception {
        // 先记录精确参数和当前命令的环境，所有调用均留在当前 testcase 的私有目录。
        List<String> command = Arrays.asList(arguments);
        Map<String, String> environment = new LinkedHashMap<>();
        System.getenv().forEach((name, value) -> {
            if (name.startsWith("METEOR_TEST_")) {
                environment.put(name, value);
            }
        });
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("arguments", command);
        record.put("pid", ProcessHandle.current().pid());
        record.put("parentPid", ProcessHandle.current().parent().orElseThrow().pid());
        record.put("environment", environment);
        Path log = Path.of(System.getenv("MOCK_DOCKER_LOG"));
        Files.writeString(log, json(record) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        // 机器输出始终写 stdout；诊断仅写 stderr，用于验证两个流没有互相污染。
        if (System.getenv("MOCK_DIAGNOSTIC_STDERR") != null) {
            System.err.println("Docker diagnostic on stderr");
        }
        if (System.getenv("MOCK_VERSION_DIAGNOSTIC") != null && starts(command, "version")) {
            System.err.println("Docker version diagnostic on stderr");
        }
        boolean startup = starts(command, "compose") && command.contains("start");
        if (startup && System.getenv("MOCK_LARGE_STDERR") != null) {
            System.err.print("diagnostic-line\n".repeat(100_000));
        }
        if (starts(command, "info")) {
            String variable = command.get(command.size() - 1).contains("OSType")
                    ? "MOCK_DOCKER_OS" : "MOCK_DOCKER_ARCH";
            String fallback = variable.endsWith("OS") ? "linux" : "amd64";
            System.out.println(value(variable, fallback));
        } else if (starts(command, "image", "inspect")) {
            System.out.println(value("MOCK_IMAGE_PLATFORM", "linux/amd64") + "\t{}");
        } else if (starts(command, "container", "inspect") && command.contains("fixture-container")) {
            inspectResource("container", "MOCK_EXISTING_CONTAINER_RUN", "fixture-container");
        } else if (starts(command, "container", "inspect") && command.contains("leaked-container")) {
            inspectResource("container", "MOCK_EXISTING_CONTAINER_RUN", "leaked-container");
        } else if (starts(command, "network", "inspect")) {
            inspectResource("network", "MOCK_EXISTING_NETWORK_RUN", "fixture-network");
        } else if (starts(command, "volume", "inspect")) {
            inspectVolume(command);
        } else if (starts(command, "container", "inspect")) {
            String target = command.get(command.size() - 1);
            System.exit(status(target.endsWith("-nacos") ? "MOCK_NO_NACOS" : "MOCK_NO_RUNNER"));
        } else if (starts(command, "cp")) {
            // 取消请求上传到 runner；普通 cp 则把最终证据归档到宿主。
            String destination = command.get(command.size() - 1);
            if (destination.endsWith("groups.cancel.upload")) {
                return;
            }
            if (command.get(1).endsWith(":/home/nacos/logs/.")) {
                // 注册中心诊断与 runner 报告分别模拟，失败码互不影响。
                int failure = status("MOCK_NACOS_LOG_EXIT");
                if (failure != 0) {
                    System.err.println("Nacos server log copy failed");
                    System.exit(failure);
                }
                Path directory = Files.createDirectories(Path.of(destination));
                Files.writeString(directory.resolve("config-server.log"), "Nacos configuration diagnostic\n");
                return;
            }
            if (System.getenv("MOCK_ARCHIVE_FAIL") != null) {
                System.err.println("archive copy failed");
                System.exit(9);
            }
            Path archive = Path.of(destination, "archived.txt");
            Files.writeString(archive, "archived", StandardCharsets.UTF_8);
            String xml = System.getenv("MOCK_JUNIT_XML");
            if (xml == null && Files.exists(runnerState()) && System.getenv("MOCK_NO_JUNIT_XML") == null) {
                // 只有实际启动过的夹具执行才产出通过记录；环境启动失败保持无结果。
                xml = "<testsuite><testcase classname=\"example.FixtureTest\" name=\"passes\"/></testsuite>";
            }
            if (xml != null) {
                Path report = Path.of(destination, "test-results/test/TEST-fixture.xml");
                Files.createDirectories(report.getParent());
                Files.writeString(report, xml, StandardCharsets.UTF_8);
            }
        } else if (starts(command, "ps", "-aq")) {
            if (System.getenv("MOCK_EXISTING_CONTAINER_RUN") != null || resourcesCreated()) {
                System.out.println("fixture-container");
            } else if (cleanupStarted() && System.getenv("MOCK_REMAINING_CONTAINER") != null) {
                System.out.println("leaked-container");
            }
        } else if (starts(command, "network", "ls")) {
            if (System.getenv("MOCK_EXISTING_NETWORK_RUN") != null || resourcesCreated()) {
                System.out.println("fixture-network");
            } else if (cleanupStarted() && System.getenv("MOCK_REMAINING_NETWORK") != null) {
                System.out.println("leaked-network");
            }
        } else if (starts(command, "volume", "ls")) {
            if (System.getenv("MOCK_EXISTING_VOLUME_RUN") != null) {
                System.out.println("fixture-volume");
            }
            if (System.getenv("MOCK_UNLABELLED_VOLUME") != null && command.stream().anyMatch(value -> {
                return value.startsWith("name=");
            })) {
                System.out.println("fixture-volume");
            }
            for (String suffix : List.of("peer", "maven", "workspace")) {
                if (Files.exists(resourceState(suffix))) {
                    System.out.println("meteor-test-" + suffix);
                }
            }
        } else if (starts(command, "volume", "rm")) {
            int failure = status("MOCK_VOLUME_RM_EXIT");
            if (failure != 0) {
                System.err.println("volume removal failed");
            } else {
                String volume = command.get(command.size() - 1);
                for (String suffix : List.of("peer", "maven", "workspace")) {
                    if (volume.endsWith("-" + suffix)) {
                        Files.deleteIfExists(resourceState(suffix));
                    }
                }
            }
            System.exit(failure);
        } else if (starts(command, "compose")) {
            compose(command);
        } else if (starts(command, "exec")) {
            runnerControl(command);
        }
    }

    private static void compose(List<String> command) throws Exception {
        // 配置列表与生命周期命令可分别失败，避免将所有失败路径折叠为同一场景。
        if (command.contains("config") && command.contains("--images")) {
            System.out.println(value("MOCK_COMPOSE_IMAGES", "fixture/java:latest\nfixture/runner:latest"));
            System.exit(status("MOCK_IMAGES_EXIT"));
        }
        if (command.contains("logs") && command.contains("--follow")) {
            // 独立日志订阅在准备期间持续输出，必须由创建它的启动任务主动回收。
            System.out.println("prepare | workspace preparation stdout");
            System.err.println("java-meteor | Java build stderr");
            System.out.flush();
            System.err.flush();
            Path marker = Path.of(System.getenv("MOCK_DOCKER_LOG") + ".follower.pid");
            Files.writeString(marker, Long.toString(ProcessHandle.current().pid()));
            if (System.getenv("MOCK_LOGS_EXIT") != null) {
                System.exit(status("MOCK_LOGS_EXIT"));
            }
            Thread.sleep(120_000);
            return;
        }
        for (String verb : List.of("build", "pull", "create", "start", "exec", "down")) {
            if (!command.contains(verb)) {
                continue;
            }
            if (verb.startsWith("create")) {
                // 新容器没有上一轮 runner 状态，启动失败也不能生成旧轮的通过 XML。
                Files.deleteIfExists(runnerState());
                Files.deleteIfExists(Path.of(runnerState() + ".pending"));
                Files.writeString(resourceState("containers"), System.getenv("METEOR_TEST_RUN_ID"));
                for (String suffix : List.of("peer", "maven", "workspace")) {
                    Files.writeString(resourceState(suffix), System.getenv("METEOR_TEST_RUN_ID"));
                }
            } else if (verb.startsWith("down")) {
                Files.writeString(resourceState("cleanup"), "started");
                if (status("MOCK_DOWN_EXIT") == 0) {
                    Files.deleteIfExists(resourceState("containers"));
                }
            }
            String marker = System.getenv("MOCK_BLOCK_" + verb.toUpperCase());
            if (verb.startsWith("exec")) {
                String state = marker == null ? "finished" : "running";
                writeRunnerState(value("MOCK_RUNNER_STATE", state), false);
            }
            boolean cancelled = false;
            if (marker != null) {
                Files.writeString(Path.of(marker), Long.toString(ProcessHandle.current().pid()));
                // runner 收到取消后自然结束，原 attach CLI 留到最后一段输出已送达才退出。
                Path release = Path.of(marker + ".release");
                long deadline = System.nanoTime() + 120_000_000_000L;
                while (!Files.exists(release) && System.nanoTime() < deadline) {
                    if (verb.startsWith("exec") && Files.exists(runnerState())) {
                        advancePendingCancellation();
                        String state = Files.readString(runnerState());
                        if (state.contains("\"state\":\"finished\"")) {
                            cancelled = state.contains("\"interrupted\":true");
                            break;
                        }
                    }
                    Thread.sleep(50);
                }
            }
            if (cancelled) {
                System.out.println("runner | cancelled execution stdout tail");
                System.err.println("runner | cancelled execution stderr tail");
            }
            System.out.println(verb + " stdout");
            System.err.println(verb + " stderr");
            System.exit(cancelled ? 130 : status("MOCK_" + verb.toUpperCase() + "_EXIT"));
        }
    }

    private static void runnerControl(List<String> command) throws Exception {
        // 状态查询和取消请求均使用真实命令边界，不将取消伪装成 Compose down。
        if (command.contains("cat")) {
            advancePendingCancellation();
            System.out.println(Files.readString(runnerState()));
        } else if (command.contains("mv")) {
            int failure = status("MOCK_CANCEL_EXIT");
            if (failure != 0) {
                System.err.println("runner cancellation control failed");
                System.exit(failure);
            }
            String marker = System.getenv("MOCK_BLOCK_EXEC");
            if (marker != null && Files.exists(Path.of(marker))) {
                // 取消必须先送到仍然连着控制台的 attach CLI，不能先杀 CLI 再补控制请求。
                long attach = Long.parseLong(Files.readString(Path.of(marker)).trim());
                boolean alive = ProcessHandle.of(attach).map(ProcessHandle::isAlive).orElse(false);
                Files.writeString(Path.of(System.getenv("MOCK_DOCKER_LOG") + ".cancel-attach-alive"),
                        Boolean.toString(alive));
            }
            String pending = System.getenv("MOCK_CANCEL_PENDING_READS");
            if (pending == null) {
                writeRunnerState("finished", true);
            } else {
                writeAtomicState(Path.of(runnerState() + ".pending"), pending);
            }
        }
    }

    private static void advancePendingCancellation() throws Exception {
        // cat 与仍附着的 exec 均能观察异步完成；独立进程通过私有文件锁串行推进状态。
        Path pending = Path.of(runnerState() + ".pending");
        if (!Files.exists(pending)) {
            return;
        }
        Path lock = Path.of(pending + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()) {
            if (!Files.exists(pending)) {
                return;
            }
            int remaining = Integer.parseInt(Files.readString(pending));
            if (remaining == 0) {
                writeRunnerState("finished", true);
                Files.delete(pending);
            } else {
                writeAtomicState(pending, Integer.toString(remaining - 1));
            }
        }
    }

    private static void writeRunnerState(String state, boolean interrupted) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", 1);
        record.put("runId", value("MOCK_RUNNER_RUN_ID", System.getenv("METEOR_TEST_RUN_ID")));
        record.put("state", state);
        boolean running = state.startsWith("running");
        record.put("exitCode", running ? null : interrupted ? 130 : status("MOCK_EXEC_EXIT"));
        record.put("interrupted", interrupted);
        record.put("cleanupComplete", !running);
        if (interrupted) {
            record.put("error", "Run cancelled");
        }
        writeAtomicState(runnerState(), json(record));
    }

    private static void writeAtomicState(Path target, String content) throws Exception {
        // attach 与查询命令并发读取，只发布完整状态，避免把文件截断的瞬间当作异常。
        Path temporary = Files.createTempFile(target.getParent(), "runner-state-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path runnerState() {
        return Path.of(System.getenv("MOCK_DOCKER_LOG") + ".runner-state.json");
    }

    private static boolean resourcesCreated() {
        return Files.exists(resourceState("containers"));
    }

    private static boolean cleanupStarted() {
        return Files.exists(resourceState("cleanup"));
    }

    private static Path resourceState(String resource) {
        return Path.of(System.getenv("MOCK_DOCKER_LOG") + ".resource-" + resource);
    }

    private static void inspectVolume(List<String> command) throws Exception {
        // 私有运行卷归当前批次；共享 Gradle 缓存不参与固定环境所有权判断。
        if (command.contains("fixture-volume")) {
            inspectResource("volume", "MOCK_EXISTING_VOLUME_RUN", "fixture-volume");
            return;
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (String volume : command.subList(2, command.size())) {
            for (String suffix : List.of("peer", "maven", "workspace")) {
                if (volume.endsWith("-" + suffix)) {
                    if (!Files.exists(resourceState(suffix))) {
                        System.exit(1);
                    }
                    String runId = Files.readString(resourceState(suffix));
                    records.add(resourceRecord("volume", volume, runId));
                }
            }
        }
        System.out.println(json(records));
    }

    private static void inspectResource(String kind, String override, String resource) throws Exception {
        // 资源枚举与 inspect 共用同一批次标签，跨轮恢复不得借用当前环境。
        String fallback = value("METEOR_TEST_RUN_ID", "");
        if (resourcesCreated()) {
            fallback = Files.readString(resourceState("containers"));
        }
        String runId = value(override, fallback);
        printResource(kind, resource, runId);
    }

    private static void printResource(String kind, String resource, String runId) {
        System.out.println(json(List.of(resourceRecord(kind, resource, runId))));
    }

    private static Map<String, Object> resourceRecord(String kind, String resource, String runId) {
        Map<String, String> labels = new LinkedHashMap<>();
        String project = value("METEOR_TEST_PROJECT", "meteor-test");
        labels.put("com.docker.compose.project", value("MOCK_RESOURCE_PROJECT", project));
        if (System.getenv("MOCK_REPLACED_RESOURCE_RUN") != null && Files.exists(runnerState())) {
            runId = System.getenv("MOCK_REPLACED_RESOURCE_RUN");
        }
        if (!runId.isEmpty()) {
            labels.put("net.hasor.meteor.test.run", runId);
        }
        if (resource.startsWith("fixture-volume") && System.getenv("MOCK_UNLABELLED_VOLUME") != null) {
            labels.clear();
        }
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("Id", resource);
        record.put("Name", resource);
        if (kind.startsWith("container")) {
            record.put("Config", Map.of("Labels", labels));
        } else {
            record.put("Labels", labels);
        }
        return record;
    }

    private static boolean starts(List<String> arguments, String... prefix) {
        return arguments.size() >= prefix.length && arguments.subList(0, prefix.length).equals(Arrays.asList(prefix));
    }

    private static int status(String name) {
        return Integer.parseInt(value(name, "0"));
    }

    private static String value(String name, String fallback) {
        return System.getenv().getOrDefault(name, fallback);
    }

    private static String json(Object value) {
        // 夹具只依赖 JDK，避免把测试工程或 SDK 运行图带入假的 Docker 命令进程。
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?>) {
            StringBuilder result = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (result.length() > 1) {
                    result.append(',');
                }
                result.append(json(entry.getKey())).append(':').append(json(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if (value instanceof Iterable<?>) {
            StringBuilder result = new StringBuilder("[");
            for (Object item : (Iterable<?>) value) {
                if (result.length() > 1) {
                    result.append(',');
                }
                result.append(json(item));
            }
            return result.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = value.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        text = text.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return '"' + text + '"';
    }
}
