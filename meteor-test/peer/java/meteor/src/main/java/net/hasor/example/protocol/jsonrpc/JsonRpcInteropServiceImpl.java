/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.jsonrpc;

/** 在 Meteor 中发布接口，以相同业务方法供 Jayson 客户端调用。 */
public class JsonRpcInteropServiceImpl implements JsonRpcInteropService {
    private volatile String notification;

    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int add(int left, int right) {
        return left + right;
    }

    @Override
    public void notification(String value) {
        this.notification = value;
    }

    @Override
    public String lastNotification() {
        return this.notification;
    }

    @Override
    public String fail() {
        throw new IllegalStateException("Published service rejected request");
    }
}
