/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import net.hasor.meteor.protocol.connect.ConnectStatusException;
import net.hasor.meteor.protocol.connect.codec.ConnectEnvelope;
import net.hasor.meteor.protocol.connect.codec.ConnectFrameCodec;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectStreamingCallTest {
    @Test
    public void oversizedErrorEnvelopeFailsTheExchange() throws Exception {
        // 故意让错误描述超过真实编解码上限，覆盖回送错误本身也失败的链路。
        try (ConnectFlowTestContext test = ConnectFlowTestContext.newServer(Runnable::run, 64)) {
            String description = "error".repeat(100);
            ConnectStatusException serviceFailure = new ConnectStatusException("internal", description, null, null);
            test.call.sendFailure(serviceFailure);

            // 无法编码错误帧时必须结束调用和正文，不能留下已开始但永不结束的响应。
            Throwable failure = test.call.completion().getCause();
            assertTrue(failure instanceof ConnectStatusException);
            assertEquals("resource_exhausted", ((ConnectStatusException) failure).getCode());
            assertSame(failure, test.exchange.completion().getCause());
            assertSame(failure, test.request.completion().getCause());
            assertSame(failure, test.call.output().completion().getCause().getCause());
        }
    }

    @Test
    public void failureEnvelopeWaitsUntilTheCurrentMessageFrameFinishes() throws Exception {
        // 大消息超过正文缓冲容量，第二个工作线程并发准备结束帧。
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            AtomicReference<Future<?>> ending = new AtomicReference<>();
            Executor endingExecutor = task -> ending.set(workers.submit(task));
            try (ConnectFlowTestContext test = ConnectFlowTestContext.newServer(endingExecutor, 200000)) {
                String message = "x".repeat(100000);
                Future<?> writing = workers.submit(() -> {
                    test.call.writeMessage(message);
                    return null;
                });
                Future<byte[]> readingPrefix = workers.submit(() -> test.call.output().readNBytes(5));
                byte[] prefix = readingPrefix.get(2, TimeUnit.SECONDS);

                // 只消费帧头，尚未释放首个分块；结束帧不能越过正在写入的数据帧。
                ConnectStatusException failure = new ConnectStatusException("internal", "source failed", null, null);
                test.call.sendFailure(failure);
                Future<?> endWriting = ending.get();
                assertNotNull(endWriting);
                assertThrows(TimeoutException.class, () -> endWriting.get(150, TimeUnit.MILLISECONDS));

                // 恢复消费后，正文必须严格为完整消息帧、错误结束帧和 EOF。
                Future<byte[]> readingBody = workers.submit(() -> test.call.output().readAllBytes());
                byte[] remainder = readingBody.get(5, TimeUnit.SECONDS);
                writing.get(2, TimeUnit.SECONDS);
                endWriting.get(2, TimeUnit.SECONDS);
                ByteArrayOutputStream body = new ByteArrayOutputStream();
                body.write(prefix);
                body.write(remainder);
                ByteArrayInputStream input = new ByteArrayInputStream(body.toByteArray());
                ConnectFrameCodec codec = new ConnectFrameCodec(200000);
                ConnectEnvelope data = codec.read(input, "identity");
                assertFalse(data.end());
                byte[] expected = test.codec.encodeValue(test.request, message, false);
                assertArrayEquals(expected, data.message());
                ConnectEnvelope end = codec.read(input, "identity");
                assertTrue(end.end());
                assertEquals("internal", end.failure().getCode());
                assertEquals("source failed", end.failure().getDescription());
                assertNull(codec.read(input, "identity"));
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
