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

/** Standard Port Mapper version 2 procedures; forwarding CALLIT is intentionally not part of this contract. */
@MetService(id = 100000)
public interface PortMapperService {
    @MetMethod(id = 1)
    boolean set(PortMapperMapping mapping) throws Exception;

    @MetMethod(id = 2)
    boolean unset(PortMapperMapping mapping) throws Exception;

    @MetMethod(id = 3)
    long getPort(PortMapperMapping mapping) throws Exception;

    @MetMethod(id = 4)
    PortMapperMapping[] dump() throws Exception;
}
