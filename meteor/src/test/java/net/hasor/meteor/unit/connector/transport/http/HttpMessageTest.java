/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport.http;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import net.hasor.meteor.connector.transport.http.HttpBody;
import net.hasor.meteor.connector.transport.http.HttpExchange;
import net.hasor.meteor.connector.transport.http.HttpRequest;
import net.hasor.meteor.connector.transport.http.HttpResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class HttpMessageTest {
    @Test
    public void requestFieldsAreCaseInsensitiveImmutableCopiesWithoutCombiningValues() {
        // 同名字段使用不同大小写，逗号仍属于原字段值。
        List<String> values = new ArrayList<>(List.of("first", "second,third"));
        Map<String, List<String>> fields = this.repeatedFields(values);
        HttpRequest request = HttpRequest.newBuffered("POST", "/", fields, new byte[0], 1, fields);

        // 发起方继续修改原集合，不得改变已经创建的请求。
        values.clear();
        fields.clear();
        List<String> expected = List.of("first", "second,third", "fourth");
        assertEquals(expected, request.headers().get("X-TAG"));
        assertEquals(expected, request.trailers().get("X-Tag"));
        assertEquals("first", request.header("x-tag"));
        assertThrows(UnsupportedOperationException.class, () -> request.headers().clear());
    }

    @Test
    public void responseFieldsAreCaseInsensitiveImmutableCopiesWithoutCombiningValues() {
        // 为响应准备包含同名不同大小写字段的可变输入。
        List<String> values = new ArrayList<>(List.of("first", "second,third"));
        Map<String, List<String>> fields = this.repeatedFields(values);
        HttpResponse response = HttpResponse.newBuffered(200, fields, new byte[0], fields);

        // 原集合和返回集合都不能间接修改响应字段。
        values.clear();
        fields.clear();
        List<String> expected = List.of("first", "second,third", "fourth");
        assertEquals(expected, response.headers().get("x-tag"));
        assertEquals(expected, response.trailers().get("X-TaG"));
        assertNull(response.header("missing"));
        assertThrows(UnsupportedOperationException.class, () -> response.headers().get("x-tag").clear());
    }

    @Test
    public void bodyTrailersRetainCaseInsensitiveCopiesWithoutCombiningValues() {
        // 正文结束时保存尾部字段，不继续引用生产者的集合。
        List<String> values = new ArrayList<>(List.of("first", "second,third"));
        Map<String, List<String>> fields = this.repeatedFields(values);
        try (HttpBody body = new HttpBody(16)) {
            body.finish(fields);

            // 后续集合修改不会影响大小写无关的尾部查询。
            values.clear();
            fields.clear();
            List<String> expected = List.of("first", "second,third", "fourth");
            assertEquals(expected, body.trailers().get("x-TAG"));
        }
    }

    @Test(timeout = 5000)
    public void boundedBodyBlocksItsProducerAndCloseReleasesTheWaiter() throws Exception {
        // 有界正文故意小于待写入数据，生产者必须等待消费。
        ExecutorService producer = Executors.newSingleThreadExecutor();
        try (HttpBody body = new HttpBody(4)) {
            CountDownLatch started = new CountDownLatch(1);
            var writing = producer.submit(() -> {
                started.countDown();
                body.output().write(new byte[16]);
                return null;
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> writing.get(100, TimeUnit.MILLISECONDS));

            // 消费允许生产继续；提前关闭让仍然受限的生产者得到异常。
            assertEquals(8, body.readNBytes(8).length);
            body.close();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> writing.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
        } finally {
            producer.shutdownNow();
        }
    }

    @Test
    public void failureBeforeAdmissionAlsoClosesTheRequestBody() {
        // 交换进入传输层之前失败，同样必须释放请求正文。
        try (HttpBody body = new HttpBody(16)) {
            HttpRequest request = HttpRequest.newStreaming("POST", "/", Map.of(), body, 1);
            HttpExchange exchange = new HttpExchange(request);
            IOException failure = new IOException("connection unavailable");

            // 一次失败同时传播给响应、交换完成信号和正文生产者。
            exchange.complete(failure);
            assertSame(failure, exchange.completion().getCause());
            assertSame(failure, exchange.response().getCause());
            assertSame(failure, body.completion().getCause());
            assertThrows(IOException.class, () -> body.output().write(1));
        }
    }

    private Map<String, List<String>> repeatedFields(List<String> values) {
        Map<String, List<String>> fields = new LinkedHashMap<>();
        fields.put("X-Tag", values);
        fields.put("x-tag", List.of("fourth"));
        return fields;
    }
}
