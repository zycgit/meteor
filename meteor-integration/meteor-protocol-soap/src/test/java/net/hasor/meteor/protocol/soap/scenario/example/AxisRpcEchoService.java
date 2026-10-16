/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;

/** 由 Apache Axis RPC provider 调用的外部业务服务。 */
public class AxisRpcEchoService {
    public String echo(String message) {
        return "Axis: " + message;
    }
}
