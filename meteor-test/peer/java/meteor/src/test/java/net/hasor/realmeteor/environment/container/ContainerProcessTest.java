/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.environment.container;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.function.EFunction;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** 只替身本地 exec 和共享状态文件，不启动 Docker 或业务服务。 */
public class ContainerProcessTest {
    @Test(timeout = 10000)
    public void completedPeerReturnsRealExitWhenExecCliStaysAlive() throws Exception {
        // 业务已经退出，Docker exec CLI 尚未完成收尾。
        try (Fixture fixture = new Fixture()) {
            assertTrue(fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // CLI 被本对象清理产生的 137 不覆盖业务零退出。
            assertEquals(0, fixture.process.exitValue());
            assertFalse(fixture.process.isAlive());
            assertFalse(fixture.attached.isAlive());
            assertSame(fixture.process, fixture.process.onExit().get(1, TimeUnit.SECONDS));
            assertEquals(0, fixture.control.calls.get());
        }
    }

    @Test(timeout = 10000)
    public void nonzeroPeerExitIsNotReplacedByCliCleanup() throws Exception {
        // 包装器持久化业务失败码，服务容器仍然存活。
        try (Fixture fixture = new Fixture()) {
            fixture.publish("exited", 19);
            assertTrue(fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // 调用者继续通过退出码观察业务失败。
            assertEquals(19, fixture.process.exitValue());
            assertEquals(137, fixture.attached.exitValue());
        }
    }

    @Test(timeout = 10000)
    public void runningPeerStillTimesOut() throws Exception {
        // 真实进程仍在运行，不能把常驻容器的存活或 CLI 默认值当作完成。
        try (Fixture fixture = new Fixture()) {
            fixture.publish("running", 0);
            assertFalse(fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // 不放宽等待预算，所有者仍可显式终止本场景。
            assertTrue(fixture.process.isAlive());
            assertThrows(IllegalThreadStateException.class, fixture.process::exitValue);
            fixture.process.destroyForcibly();
            assertEquals(1, fixture.control.calls.get());
            assertEquals(137, fixture.process.exitValue());
        }
    }

    @Test(timeout = 10000)
    public void missingStatusAfterCliExitCancelsTheOwnedPeer() throws Exception {
        // exec 提前退出时，状态尚未落盘。
        try (Fixture fixture = new Fixture()) {
            Files.delete(fixture.directory.resolve("status.native"));
            fixture.attached.complete(0);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                fixture.process.waitFor(5, TimeUnit.MILLISECONDS);
            });

            // 原控制失败保留，同时取消自己的远端进程，不能只清理本地 CLI。
            assertTrue(failure.getCause().getMessage().contains("before the peer process exited"));
            assertEquals(1, fixture.control.calls.get());
            assertEquals("exited", fixture.readStatus().path("state").asText());
        }
    }

    @Test(timeout = 10000)
    public void malformedStatusDoesNotReturnSuccess() throws Exception {
        // 无法解析状态时，不能采用常驻服务容器的零退出码。
        try (Fixture fixture = new Fixture()) {
            Files.writeString(fixture.directory.resolve("status.native"), "truncated native state");
            assertThrows(IllegalStateException.class, () -> fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // 取消请求仍使用独占 launch，而不是依靠已损坏的状态猜测 PID。
            assertEquals(1, fixture.control.calls.get());
            assertFalse(fixture.attached.isAlive());
        }
    }

    @Test(timeout = 10000)
    public void execCliExitDoesNotHideARunningPeer() throws Exception {
        // CLI 自己零退出，但持久状态表明业务仍在执行。
        try (Fixture fixture = new Fixture()) {
            fixture.publish("running", 0);
            fixture.attached.complete(0);
            assertThrows(IllegalStateException.class, () -> fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // 对端被有界取消，提前断开的控制通道仍报告失败。
            assertEquals(1, fixture.control.calls.get());
            assertEquals(137, fixture.readStatus().path("exitCode").asInt());
        }
    }

    @Test(timeout = 10000)
    public void execFailureDoesNotHideASuccessfulPeer() throws Exception {
        // 业务退出零不能掩盖 exec 输出传输错误。
        try (Fixture fixture = new Fixture()) {
            fixture.attached.complete(125);
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                fixture.process.waitFor(5, TimeUnit.MILLISECONDS);
            });

            // 不是本对象主动清理产生的 CLI 错误必须向调用者报告。
            assertTrue(failure.getCause().getMessage().contains("exec failed with exit 125"));
            assertEquals(0, fixture.control.calls.get());
        }
    }

    @Test(timeout = 10000)
    public void completedPeerPreservesTheCompleteOutputFiles() throws Exception {
        // 包装器已排空并保存标准输出和错误输出。
        try (Fixture fixture = new Fixture()) {
            Files.writeString(fixture.directory.resolve("stdout.log"), "RESULT accepted\nshutdown completed\n");
            Files.writeString(fixture.directory.resolve("stderr.log"), "diagnostic tail\n");
            assertTrue(fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // 证据引用完整文件，不往调用者的管道重复注入 RESULT。
            JsonNode evidence = fixture.readEvidence();
            assertEquals("RESULT accepted\nshutdown completed\n",
                    Files.readString(Path.of(evidence.path("stdoutLog").asText())));
            assertEquals("diagnostic tail\n", Files.readString(Path.of(evidence.path("stderrLog").asText())));
            assertTrue(evidence.path("cleaned").asBoolean());
        }
    }

    @Test(timeout = 10000)
    public void missingOutputLogDoesNotReturnSuccess() throws Exception {
        // 零退出状态没有配套的完整日志，不是完整的可审计结果。
        try (Fixture fixture = new Fixture()) {
            Files.delete(fixture.directory.resolve("stdout.log"));
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                fixture.process.waitFor(5, TimeUnit.MILLISECONDS);
            });

            // 输出证据缺失明确失败，不能把它当成普通业务零退出。
            assertTrue(failure.getCause().getMessage().contains("output logs are missing"));
            assertThrows(IllegalStateException.class, fixture.process::exitValue);
        }
    }

    @Test(timeout = 10000)
    public void repeatedCloseStopsOnlyTheOwnedProcessOnce() throws Exception {
        // 业务仍在运行，关闭通过独占 launch 定位进程树。
        try (Fixture fixture = new Fixture()) {
            fixture.publish("running", 0);
            fixture.process.destroy();
            fixture.process.destroyForcibly();

            // 不停止或删除借入的服务容器，重复关闭不会发起第二次控制请求。
            assertEquals(1, fixture.control.calls.get());
            assertEquals("exec", fixture.control.arguments.get(0));
            assertTrue(fixture.control.arguments.contains("--stop"));
            assertEquals("persistent-node", fixture.control.arguments.get(1));
            assertEquals(143, fixture.process.exitValue());
        }
    }

    @Test(timeout = 10000)
    public void stopControlFailureRemainsVisible() throws Exception {
        // Docker 无法执行进程树取消命令。
        try (Fixture fixture = new Fixture()) {
            fixture.publish("running", 0);
            fixture.control.failure = new IOException("exec control denied");
            IllegalStateException failure = assertThrows(IllegalStateException.class, fixture.process::destroyForcibly);

            // 不靠重复停止掩盖控制错误，也不关闭其他场景所在的服务。
            assertEquals("exec control denied", failure.getCause().getMessage());
            assertEquals(1, fixture.control.calls.get());
            assertFalse(fixture.attached.isAlive());
        }
    }

    @Test(timeout = 10000)
    public void closingAnAlreadyExitedPeerNeedsNoDockerControl() throws Exception {
        // 业务已自然结束，destroy 只负责完成本对象的控制通道清理。
        try (Fixture fixture = new Fixture()) {
            fixture.process.destroyForcibly();

            // 不向已退出进程或常驻服务发送额外信号。
            assertEquals(0, fixture.control.calls.get());
            assertEquals(0, fixture.process.exitValue());
            assertTrue(fixture.readEvidence().path("execReaped").asBoolean());
        }
    }

    @Test(timeout = 10000)
    public void concurrentCloseObservesTheSameControlFailure() throws Exception {
        // 一个调用拥有取消，另一个调用必须等待相同清理结果。
        try (Fixture fixture = new Fixture()) {
            fixture.publish("running", 0);
            fixture.control.failure = new IOException("cancel denied");
            fixture.control.started = new CountDownLatch(1);
            fixture.control.release = new CountDownLatch(1);
            ExecutorService callers = Executors.newFixedThreadPool(2);
            try {
                Future<?> first = callers.submit(fixture.process::destroyForcibly);
                assertTrue(fixture.control.started.await(1, TimeUnit.SECONDS));
                Future<?> second = callers.submit(fixture.process::destroy);
                fixture.control.release.countDown();

                // 两个入口都看到原始失败，只执行一次所属进程的取消请求。
                ExecutionException firstFailure = assertThrows(ExecutionException.class, () -> {
                    first.get(1, TimeUnit.SECONDS);
                });
                ExecutionException secondFailure = assertThrows(ExecutionException.class, () -> {
                    second.get(1, TimeUnit.SECONDS);
                });
                assertEquals("cancel denied", firstFailure.getCause().getCause().getMessage());
                assertEquals("cancel denied", secondFailure.getCause().getCause().getMessage());
                assertEquals(1, fixture.control.calls.get());
            } finally {
                fixture.control.release.countDown();
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 10000)
    public void terminalQueriesWaitForTheSameCleanupFailure() throws Exception {
        // 业务已经零退出，但 exec CLI 的回收被信号阻塞，稍后会明确失败。
        try (Fixture fixture = new Fixture()) {
            fixture.attached.failReap = true;
            fixture.attached.reapStarted = new CountDownLatch(1);
            fixture.attached.releaseReap = new CountDownLatch(1);
            ExecutorService callers = Executors.newFixedThreadPool(3);
            try {
                Future<Boolean> finishing = callers.submit(() -> fixture.process.waitFor(5, TimeUnit.MILLISECONDS));
                assertTrue(fixture.attached.reapStarted.await(1, TimeUnit.SECONDS));
                CountDownLatch querying = new CountDownLatch(2);
                Future<Integer> exitValue = callers.submit(() -> {
                    querying.countDown();
                    return fixture.process.exitValue();
                });
                Future<Boolean> alive = callers.submit(() -> {
                    querying.countDown();
                    return fixture.process.isAlive();
                });
                CompletableFuture<Process> exited = fixture.process.onExit();
                assertTrue(querying.await(1, TimeUnit.SECONDS));

                // 收尾尚未完成，三个入口都不能提前宣布零退出或已经结束。
                assertThrows(TimeoutException.class, () -> exitValue.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> alive.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> exited.get(100, TimeUnit.MILLISECONDS));
                fixture.attached.releaseReap.countDown();

                // 释放收尾后，等待者与三个终态观察者共同看到同一真实回收失败。
                ExecutionException failure = assertThrows(ExecutionException.class, () -> {
                    finishing.get(1, TimeUnit.SECONDS);
                });
                Throwable cleanup = failure.getCause().getCause();
                assertEquals("Cannot reap Docker exec CLI", cleanup.getMessage());
                assertTrue(cleanup.getCause() instanceof IOException);
                assertEquals("Docker exec CLI did not stop", cleanup.getCause().getMessage());
                assertThrows(ExecutionException.class, () -> exitValue.get(1, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> alive.get(1, TimeUnit.SECONDS));
                assertThrows(ExecutionException.class, () -> exited.get(1, TimeUnit.SECONDS));
                assertFalse(fixture.readEvidence().path("cleaned").asBoolean());
            } finally {
                fixture.attached.releaseReap.countDown();
                callers.shutdownNow();
                assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test(timeout = 10000)
    public void remainingDescendantsPreventSuccessfulCleanup() throws Exception {
        // 主进程零退出，但包装器明确记录还有未回收的子孙。
        try (Fixture fixture = new Fixture()) {
            fixture.status.put("processGroupEmpty", false);
            fixture.status.put("remainingPids", List.of(812));
            fixture.writeStatus();
            assertThrows(IllegalStateException.class, () -> fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // 不用主进程退出码替代进程树清理结果。
            assertFalse(fixture.readEvidence().path("cleaned").asBoolean());
        }
    }

    @Test(timeout = 10000)
    public void leftoverTemporaryDirectoryPreventsSuccessfulCleanup() throws Exception {
        // 包装器无法删除本场景独占临时目录。
        try (Fixture fixture = new Fixture()) {
            fixture.status.put("temporaryDirectoryRemoved", false);
            fixture.writeStatus();
            assertThrows(IllegalStateException.class, () -> fixture.process.waitFor(5, TimeUnit.MILLISECONDS));

            // 常驻服务不能在上一个场景资源未清理时被记为完整成功。
            assertFalse(fixture.readEvidence().path("cleaned").asBoolean());
        }
    }

    @Test(timeout = 10000)
    public void anotherProcessStatusCannotCompleteThisPeer() throws Exception {
        // 共享服务里的另一份状态不属于当前 launch。
        try (Fixture fixture = new Fixture()) {
            fixture.status.put("processId", "another-peer");
            fixture.writeStatus();
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
                fixture.process.waitFor(5, TimeUnit.MILLISECONDS);
            });

            // 身份冲突明确失败，取消仍绑定本对象原始 launch。
            assertTrue(failure.getCause().getMessage().contains("does not identify the owned process"));
            assertEquals(1, fixture.control.calls.get());
        }
    }

    @Test(timeout = 10000)
    public void commandOutputReadHasABoundedDeadline() throws Exception {
        // CLI 已退出，但模拟继承 stdout 的后代仍持有输出管道。
        AttachedProcess command = new AttachedProcess();
        command.complete(0);
        CountDownLatch closed = new CountDownLatch(1);
        command.output = new InputStream() {
            @Override
            public int read() throws IOException {
                try {
                    closed.await();
                    return -1;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }

            @Override
            public void close() {
                closed.countDown();
            }
        };

        // waitFor 与 EOF 共用预算，超时后关闭所属管道并保留真实 TimeoutException。
        IOException failure = assertThrows(IOException.class, () -> {
            ContainerProcess.commandResult(command, List.of("exec", "service", "--stop"), 20, TimeUnit.MILLISECONDS);
        });
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertTrue(closed.await(1, TimeUnit.SECONDS));
    }

    private static final class Fixture implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Path root = Files.createTempDirectory("meteor-exec-process-");
        private final Path directory = Files.createDirectory(this.root.resolve("test-peer"));
        private final AttachedProcess attached = new AttachedProcess();
        private final Map<String, Object> status = new LinkedHashMap<>();
        private final ProcessControl control = new ProcessControl(this);
        private final ContainerProcess process;

        private Fixture() throws IOException {
            this.publish("exited", 0);
            Files.writeString(this.directory.resolve("launch.json"), "{\"processId\":\"test-peer\"}");
            Files.writeString(this.directory.resolve("stdout.log"), "RESULT accepted\n");
            Files.writeString(this.directory.resolve("stderr.log"), "");
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("processId", "test-peer");
            this.process = new ContainerProcess(this.attached, "persistent-node", "node.meteor.test",
                    this.directory, evidence, this.control);
        }

        private void publish(String state, int exitCode) throws IOException {
            this.status.put("processId", "test-peer");
            this.status.put("state", state);
            this.status.put("exitCode", exitCode);
            this.status.put("processGroupEmpty", StringUtils.equals("exited", state));
            this.status.put("remainingPids", List.of());
            this.status.put("temporaryDirectoryRemoved", StringUtils.equals("exited", state));
            this.writeStatus();
        }

        private void writeStatus() throws IOException {
            // Native state is the authority; JSON is published by the framework reader.
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            encoded.write("meteor-status-v1\0".getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> entry : this.status.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean) {
                    value = Boolean.TRUE.equals(value) ? "1" : "0";
                } else if (value instanceof List<?>) {
                    value = ((List<?>) value).stream().map(String::valueOf).collect(Collectors.joining(","));
                }
                encoded.write((entry.getKey() + "\0" + value + "\0").getBytes(StandardCharsets.UTF_8));
            }
            Files.write(this.directory.resolve("status.native"), encoded.toByteArray());
        }

        private JsonNode readStatus() throws IOException {
            return JSON.readTree(Files.readAllBytes(this.directory.resolve("status.json")));
        }

        private JsonNode readEvidence() throws IOException {
            return JSON.readTree(Files.readAllBytes(this.root.resolve("test-peer.json")));
        }

        @Override
        public void close() throws Exception {
            // 单测只持有假进程，证据目录不加入真实运行的审计数据。
            this.attached.complete(137);
            try (Stream<Path> files = Files.walk(this.root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(file);
                }
            }
        }
    }

    private static final class ProcessControl implements EFunction<List<String>, String, IOException> {
        private final Fixture fixture;
        private final AtomicInteger calls = new AtomicInteger();
        private List<String> arguments = new ArrayList<>();
        private IOException failure;
        private CountDownLatch started;
        private CountDownLatch release;

        private ProcessControl(Fixture fixture) {
            this.fixture = fixture;
        }

        @Override
        public String eApply(List<String> arguments) throws IOException {
            this.arguments = List.copyOf(arguments);
            this.calls.incrementAndGet();
            if (!StringUtils.equals("exec", arguments.get(0)) || !arguments.contains("--stop")) {
                throw new IOException("Unexpected service lifecycle command: " + arguments);
            }
            if (this.started != null) {
                this.started.countDown();
                try {
                    this.release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            }
            if (this.failure != null) {
                throw this.failure;
            }
            this.fixture.publish("exited", arguments.contains("--force") ? 137 : 143);
            return "";
        }
    }

    private static final class AttachedProcess extends Process {
        private final CompletableFuture<Process> exited = new CompletableFuture<>();
        private final OutputStream input = new ByteArrayOutputStream();
        private InputStream output = InputStream.nullInputStream();
        private volatile boolean alive = true;
        private int exitCode;
        private boolean failReap;
        private boolean destroyRequested;
        private CountDownLatch reapStarted;
        private CountDownLatch releaseReap;

        private void complete(int exitCode) {
            this.exitCode = exitCode;
            this.alive = false;
            this.exited.complete(this);
        }

        @Override
        public OutputStream getOutputStream() {
            return this.input;
        }

        @Override
        public InputStream getInputStream() {
            return this.output;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                this.exited.get();
                return this.exitCode;
            } catch (ExecutionException impossible) {
                throw new AssertionError(impossible);
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (this.failReap && this.destroyRequested) {
                this.reapStarted.countDown();
                this.releaseReap.await();
                return false;
            }
            return !this.alive;
        }

        @Override
        public int exitValue() {
            if (this.alive) {
                throw new IllegalThreadStateException("exec is still running");
            }
            return this.exitCode;
        }

        @Override
        public boolean isAlive() {
            return this.alive;
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return this.exited;
        }

        @Override
        public void destroy() {
            this.destroyForcibly();
        }

        @Override
        public Process destroyForcibly() {
            if (this.failReap) {
                this.destroyRequested = true;
            } else if (this.alive) {
                this.complete(137);
            }
            return this;
        }
    }
}
