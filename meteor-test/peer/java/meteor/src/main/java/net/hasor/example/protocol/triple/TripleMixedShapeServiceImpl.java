/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.rpc.MetCaller;

/** 用普通 Java 重载演示 unary 与 Flow 可以共享方法名。 */
public class TripleMixedShapeServiceImpl implements TripleMixedShapeService {
    public String source(int value) {
        return "unary:" + value;
    }

    public Flow.Publisher<String> source(String prefix) {
        return MetCaller.fromIterable(List.of(prefix + "-0", prefix + "-1", prefix + "-2"));
    }

    public String sink(int value) {
        return "unary:" + value;
    }

    public String sink(Flow.Publisher<String> values) {
        // 请求流结束以后再返回一次汇总，不要求事先知道消息数量。
        StringBuilder joined = new StringBuilder();
        try {
            MetCaller.consume(values, value -> {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(value);
            }).get(5, TimeUnit.SECONDS);
            return joined.toString();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
