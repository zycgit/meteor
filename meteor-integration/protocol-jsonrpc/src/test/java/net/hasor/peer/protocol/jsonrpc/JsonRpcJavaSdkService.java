/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.jsonrpc;

/** 独立 SDK 服务的普通 Java 实现，无 Meteor 注解或运行类型。 */
public final class JsonRpcJavaSdkService {
    public String echo(String value) {
        return "sdk-server:" + value;
    }
}
