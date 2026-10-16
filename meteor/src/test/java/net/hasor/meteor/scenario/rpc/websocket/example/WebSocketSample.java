/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.rpc.websocket.example;
import net.hasor.meteor.MetMessage;

/** 普通业务接口；不感知 WebSocket 握手或帧。 */
public interface WebSocketSample {
    String echo(String value);

    byte[] binary(byte[] value);

    String block() throws InterruptedException;

    @MetMessage
    void notifyBlocked() throws InterruptedException;
}
