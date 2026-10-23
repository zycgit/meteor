/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.util.Locale;

/** 每种模式单独生成 JUnit 实例，注册模式不得向消费者提供业务地址。 */
public enum OrderDiscovery {
    DIRECT,
    JDBC,
    ZOOKEEPER,
    NACOS;

    @Override
    public String toString() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
