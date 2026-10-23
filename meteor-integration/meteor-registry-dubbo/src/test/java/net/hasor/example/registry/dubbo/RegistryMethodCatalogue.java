/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.registry.dubbo;

/** 注册中心的方法目录包含继承方法和同名重载，不包含静态工具方法。 */
public interface RegistryMethodCatalogue extends RegistryEchoService {
    String echo(int value);

    String ping();

    static String utility() {
        return "not-rpc";
    }
}
