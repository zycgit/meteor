/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.rpcbind;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;

/** Version 4 control contract; GETVERSADDR matches the requested program version exactly. */
@MetService(id = 100000)
public interface RpcbindV4Service extends RpcbindV3Service {
    @MetMethod(id = 9)
    String getVersionAddress(RpcbindMapping mapping) throws Exception;
}
