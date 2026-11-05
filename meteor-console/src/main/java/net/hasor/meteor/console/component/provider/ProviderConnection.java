/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.provider;
import java.util.Objects;
import net.hasor.meteor.registry.RegistryAdmin;

/** 统一提供注册中心管理接口，连接拥有本地或远端管理资源的生命周期。 */
public interface ProviderConnection extends AutoCloseable {
    /** 返回借用的注册中心管理入口，调用方不得单独关闭。 */
    RegistryAdmin registry();

    @Override
    void close() throws Exception;

    /** 接管新建管理入口的所有权，关闭连接时释放其持有的资源。 */
    static ProviderConnection registry(RegistryAdmin registry) {
        Objects.requireNonNull(registry, "registry");
        return new ProviderConnection() {
            @Override
            public RegistryAdmin registry() {
                return registry;
            }

            @Override
            public void close() throws Exception {
                registry.close();
            }
        };
    }
}
