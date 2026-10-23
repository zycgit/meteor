/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProcedure;
import net.hasor.meteor.protocol.oncrpc.annotation.OncRpcProgram;

/** Standard rpcbind version 3 control procedures, excluding forwarding and transport conversion. */
@OncRpcProgram(id = 100000, version = 3)
public interface RpcbindV3Service {
    @OncRpcProcedure(id = 1)
    boolean set(RpcbindMapping mapping) throws Exception;

    @OncRpcProcedure(id = 2)
    boolean unset(RpcbindMapping mapping) throws Exception;

    @OncRpcProcedure(id = 3)
    String getAddress(RpcbindMapping mapping) throws Exception;

    @OncRpcProcedure(id = 4)
    RpcbindMapping[] dump() throws Exception;

    @OncRpcProcedure(id = 6)
    long getTime() throws Exception;
}
