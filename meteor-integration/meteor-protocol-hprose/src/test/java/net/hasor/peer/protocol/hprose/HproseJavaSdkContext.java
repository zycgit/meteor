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

/** 官方 Hprose 引擎每次 HTTP 交换独占的上下文。 */
final class HproseJavaSdkContext extends ServiceContext {
    HproseJavaSdkContext(HproseClients clients) {
        super(clients);
    }
}
