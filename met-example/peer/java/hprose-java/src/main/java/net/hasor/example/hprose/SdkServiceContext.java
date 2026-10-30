/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.hprose;
import hprose.server.HproseClients;
import hprose.server.ServiceContext;

/** 为 JDK HTTP 托管的 Hprose SDK 请求创建独立调用上下文。 */
final class SdkServiceContext extends ServiceContext {
    SdkServiceContext(HproseClients service) {
        super(service);
    }
}
