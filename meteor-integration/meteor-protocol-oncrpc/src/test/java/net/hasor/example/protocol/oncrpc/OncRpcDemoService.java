/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProcedure;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProgram;

/** 对应 src/test/rpc/interop.x，过程编号由业务 IDL 固定。 */
@OncRpcProgram(id = 0x31234567L, version = 1)
public interface OncRpcDemoService {
    @OncRpcProcedure(id = 1)
    String echo(String value);

    @OncRpcProcedure(id = 2)
    int add(int left, int right);

    @OncRpcProcedure(id = 3)
    OncRpcSample mirror(OncRpcSample value);

    @OncRpcProcedure(id = 4)
    void ping();

    @OncRpcProcedure(id = 5)
    void crash();

    @OncRpcProcedure(id = 6)
    String auth();

    @OncRpcProcedure(id = 7)
    int delay(int milliseconds);

    @OncRpcProcedure(id = 8)
    int increment(int delta);

    @OncRpcProcedure(id = 9)
    int count();

    @OncRpcProcedure(id = 10)
    void reset();

    @OncRpcProcedure(id = 11)
    byte[] bytes(byte[] value);
}
