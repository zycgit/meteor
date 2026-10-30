/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.hprose;
import hprose.server.HproseClients;
import hprose.server.ServiceContext;

/** 为官方服务引擎建立每次 HTTP 请求独占的调用上下文。 */
final class HproseOrderContext extends ServiceContext {
    HproseOrderContext(HproseClients clients) {
        super(clients);
    }
}
