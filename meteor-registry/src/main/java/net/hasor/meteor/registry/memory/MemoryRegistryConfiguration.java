/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.memory;
import java.util.concurrent.Callable;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/**
 * 内置纯内存注册中心，无外部连接或磁盘持久化。
 * 同一配置创建的客户端、管理端和存储会话共享数据；不同配置对象相互隔离。
 * 不需要地址或存储认证参数，记录随配置对象一起回收。
 */
public final class MemoryRegistryConfiguration extends RegistryConfiguration {
    private final MemoryRegistryDatabase database = new MemoryRegistryDatabase();

    @Override
    public String getProvider() {
        return "memory";
    }

    @Override
    protected Callable<StorageAccess> createStorageOpener() {
        return () -> new MemoryStorage(this.database);
    }
}
