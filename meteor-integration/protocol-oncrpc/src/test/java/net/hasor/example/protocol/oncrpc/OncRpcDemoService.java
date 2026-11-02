/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;

/** 对应 src/test/rpc/interop.x，过程编号由业务 IDL 固定。 */
@MetService(id = 0x31234567L)
public interface OncRpcDemoService {
    @MetMethod(id = 1)
    String echo(String value);

    @MetMethod(id = 2)
    int add(int left, int right);

    @MetMethod(id = 3)
    OncRpcSample mirror(OncRpcSample value);

    @MetMethod(id = 4)
    void ping();

    @MetMethod(id = 5)
    void crash();

    @MetMethod(id = 6)
    String auth();

    @MetMethod(id = 7)
    int delay(int milliseconds);

    @MetMethod(id = 8)
    int increment(int delta);

    @MetMethod(id = 9)
    int count();

    @MetMethod(id = 10)
    void reset();

    @MetMethod(id = 11)
    byte[] bytes(byte[] value);
}
