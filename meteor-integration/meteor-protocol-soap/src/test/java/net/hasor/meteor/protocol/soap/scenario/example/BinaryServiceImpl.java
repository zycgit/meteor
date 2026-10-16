/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;

/** 回传收到的业务数据，由协议负责请求与响应的附件传输。 */
public class BinaryServiceImpl implements BinaryService {
    @Override
    public byte[] echo(byte[] value) {
        return value;
    }

    @Override
    public BinaryPayload echoPayload(BinaryPayload value) {
        return value;
    }
}
