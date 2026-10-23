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

/** Version 4 control contract; GETVERSADDR matches the requested program version exactly. */
@OncRpcProgram(id = 100000, version = 4)
public interface RpcbindV4Service extends RpcbindV3Service {
    @OncRpcProcedure(id = 9)
    String getVersionAddress(RpcbindMapping mapping) throws Exception;
}
