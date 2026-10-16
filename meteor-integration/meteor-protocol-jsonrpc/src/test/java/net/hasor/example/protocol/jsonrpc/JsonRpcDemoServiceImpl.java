/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.jsonrpc;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** 同一个业务实现同时用于 Java 代理、外部调用和 notification 示例。 */
public class JsonRpcDemoServiceImpl implements JsonRpcDemoService {
    private final BlockingQueue<String> notifications = new LinkedBlockingQueue<>();

    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int add(int left, int right) {
        return left + right;
    }

    @Override
    public int subtract(int left, int right) {
        return left - right;
    }

    @Override
    public List<JsonRpcPerson> people(List<JsonRpcPerson> values) {
        return new ArrayList<>(values);
    }

    @Override
    public String fail(String message) {
        throw new IllegalArgumentException(message);
    }

    @Override
    public String current() {
        return "ready";
    }

    @Override
    public void notify(String value) {
        this.notifications.add(value);
    }

    public BlockingQueue<String> getNotifications() {
        return this.notifications;
    }
}
