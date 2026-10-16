/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.example.registry.zookeeper;
import net.hasor.meteor.MetService;

/** 发布与订阅场景使用的最小业务接口。 */
@MetService(group = "zookeeper-example")
public interface EchoService {
    String echo(String value);
}
