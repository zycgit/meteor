/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.jsonrpc;
import net.hasor.meteor.MetMessage;

/** 与独立 JSON-RPC 实现互通的普通 Java 服务接口。 */
public interface JsonRpcInteropService {
    String echo(String value);

    int add(int left, int right);

    @MetMessage
    void notification(String value);

    String lastNotification();

    String fail();
}
