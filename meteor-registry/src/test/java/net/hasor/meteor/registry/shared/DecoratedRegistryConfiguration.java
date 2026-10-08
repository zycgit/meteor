/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/** 测试装饰正式存储会话，用于注入故障或观察生命周期；仍通过配置入口创建被测模块。 */
public final class DecoratedRegistryConfiguration extends RegistryConfiguration {
    private final RegistryConfiguration        delegate;
    private final UnaryOperator<StorageAccess> decorator;

    public DecoratedRegistryConfiguration(RegistryConfiguration delegate, UnaryOperator<StorageAccess> decorator) {
        this.delegate = delegate;
        this.decorator = decorator;
    }

    @Override
    public String getProvider() {
        return this.delegate.getProvider();
    }

    @Override
    protected Callable<StorageAccess> createStorageOpener() {
        return () -> this.decorator.apply(this.delegate.openStorage());
    }
}
