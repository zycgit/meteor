/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.matrix.standard;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 控制通道必须同时确认事件和进程终态，不能将真实失败变成未匹配。 */
public class StandardPeerProcessTest {
    @TempDir
    public Path directory;

    @Test
    public void acceptsOneResultOnlyAfterSuccessfulExit() throws Exception {
        // 独立 JVM 返回一个事件并正常退出，读取的业务内容仍属于原始事件。
        try (StandardPeerProcess peer = launch("success")) {
            JsonNode event = peer.awaitResult();
            assertEquals(101, event.required("order").required("id").intValue());
        }
    }

    @Test
    public void rejectsFailureAfterResultWasPrinted() throws Exception {
        // 进程打印结果后异常退出，结果存在不能覆盖失败终态。
        try (StandardPeerProcess peer = launch("exit-failure")) {
            AssertionError failure = assertThrows(AssertionError.class, peer::awaitResult);
            assertTrue(failure.getMessage().contains("unsuccessfully"));
            assertTrue(failure.getMessage().contains("stderr.log"));
        }
    }

    @Test
    public void rejectsSuccessfulExitWithoutResult() throws Exception {
        // 退出码为零但缺失业务结果，同样没有完成标准场景。
        try (StandardPeerProcess peer = launch("no-result")) {
            AssertionError failure = assertThrows(AssertionError.class, peer::awaitResult);
            assertTrue(failure.getMessage().contains("did not emit a result"));
        }
    }

    @Test
    public void rejectsDuplicateResultEvents() throws Exception {
        // 一次调用对应一个结果，不能在多个事件中选择看起来正确的一条。
        try (StandardPeerProcess peer = launch("duplicate")) {
            AssertionError failure = assertThrows(AssertionError.class, peer::awaitResult);
            assertTrue(failure.getMessage().contains("Duplicate result"));
        }
    }

    @Test
    public void rejectsProviderThatExitedAfterReady() throws Exception {
        // 就绪日志不能证明服务仍存活；先确认退出，再读取残留事件。
        try (StandardPeerProcess peer = launch("ready-exit")) {
            AssertionError failure = assertThrows(AssertionError.class, peer::awaitReady);
            assertTrue(failure.getMessage().contains("Provider exited"));
        }
    }

    private StandardPeerProcess launch(String mode) throws Exception {
        Path stdout = this.directory.resolve("stdout.log");
        Path stderr = this.directory.resolve("stderr.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                EventProcessMain.class.getName(), mode) //
                .redirectOutput(stdout.toFile()) //
                .redirectError(stderr.toFile()) //
                .start();
        StandardPeerProcess peer = new StandardPeerProcess(process, "control-probe", stdout, stderr);
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            peer.close();
            fail("Control probe did not exit");
        }
        return peer;
    }

    /** 只产生控制事件，不实现 RPC 或任何被测业务。 */
    public static final class EventProcessMain {
        private EventProcessMain() {
        }

        public static void main(String[] arguments) {
            String result = "{\"event\":\"result\",\"saved\":{\"id\":101},\"order\":{\"id\":101}}";
            switch (arguments[0]) {
                case "success" -> System.out.println(result);
                case "exit-failure" -> {
                    System.out.println(result);
                    System.err.println("Simulated process failure");
                    System.exit(7);
                }
                case "duplicate" -> {
                    System.out.println(result);
                    System.out.println(result);
                }
                case "ready-exit" -> System.out.println("{\"event\":\"ready\",\"target\":\"rsf://peer:28761\"}");
                case "no-result" -> System.out.println("ordinary SDK log");
                default -> throw new IllegalArgumentException("Unknown control probe: " + arguments[0]);
            }
        }
    }
}
