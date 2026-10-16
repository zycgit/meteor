/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.support.example;

public class EchoService implements Echo {
    public String echo(String value) {
        return value;
    }

    public int add(int left, int right) {
        return left + right;
    }

    public String fail() {
        throw new IllegalStateException("service failure");
    }

    public String slow(int millis) throws InterruptedException {
        // 按请求延迟返回，用于验证调用方的超时和取消。
        Thread.sleep(millis);
        return "done";
    }

    public int[] array(int[] values) {
        return values;
    }
}
