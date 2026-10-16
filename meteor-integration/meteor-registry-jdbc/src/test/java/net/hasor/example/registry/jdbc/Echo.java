/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.example.registry.jdbc;
import net.hasor.meteor.MetService;

/** JDBC 注册发现使用的普通 Java 服务。 */
@MetService(group = "h2-test")
public interface Echo {
    String echo(String value);
}
