/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos.scenario;
import net.hasor.meteor.MetService;

/** 最小业务接口，用于验证注册、发现后的服务可调用。 */
@MetService(group = "nacos-example")
public interface EchoService {
    String echo(String value);
}
