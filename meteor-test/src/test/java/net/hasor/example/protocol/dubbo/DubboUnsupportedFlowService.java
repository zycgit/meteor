/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.dubbo;
import java.util.concurrent.Flow;

/** 流式业务应使用 Triple，经典 Dubbo 二进制协议明确拒绝此声明。 */
public interface DubboUnsupportedFlowService {
    Flow.Publisher<String> values(Flow.Publisher<String> input);
}
