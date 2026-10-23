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

/** Standard rpcbind version 3 control procedures, excluding forwarding and transport conversion. */
@MetService(id = 100000)
public interface RpcbindV3Service {
    @MetMethod(id = 1)
    boolean set(RpcbindMapping mapping) throws Exception;

    @MetMethod(id = 2)
    boolean unset(RpcbindMapping mapping) throws Exception;

    @MetMethod(id = 3)
    String getAddress(RpcbindMapping mapping) throws Exception;

    @MetMethod(id = 4)
    RpcbindMapping[] dump() throws Exception;

    @MetMethod(id = 6)
    long getTime() throws Exception;
}
