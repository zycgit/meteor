/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.jsonrpc;
import java.util.List;
import net.hasor.meteor.MetMessage;

/** 普通 Java 接口通过协议别名公开为 JSON-RPC 方法。 */
public interface JsonRpcDemoService {
    String echo(String value);

    int add(int left, int right);

    int subtract(int left, int right);

    List<JsonRpcPerson> people(List<JsonRpcPerson> values);

    String fail(String message);

    String current();

    @MetMessage
    void notify(String value);
}
