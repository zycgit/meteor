/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple.interop;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import net.hasor.meteor.rpc.MetCaller;

/** 普通 Java 方法和 Flow 使用统一公开发布入口。 */
public class TripleInteropProtoServiceImpl implements TripleInteropProtoService {
    public String echo(String text) {
        return text;
    }

    public String fail(String text) {
        throw new IllegalArgumentException("triple failure " + text);
    }

    public String metadata(String text) {
        return text;
    }

    public String slow(String text) {
        // 阻塞仅存在于示例业务中，调用端期限不等待此方法完成。
        try {
            Thread.sleep(500);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return text;
    }

    public Flow.Publisher<String> count(String count) {
        return MetCaller.fromIterator(() -> IntStream.range(0, Integer.parseInt(count)) //
                .mapToObj(Integer::toString).iterator());
    }

    public String collect(Flow.Publisher<String> values) {
        // 等待请求流结束后，返回一个普通汇总结果。
        List<String> received = new ArrayList<>();
        try {
            MetCaller.consume(values, received::add).get(5, TimeUnit.SECONDS);
            return String.join(",", received);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public Flow.Publisher<String> chat(Flow.Publisher<String> values) {
        return values;
    }
}
