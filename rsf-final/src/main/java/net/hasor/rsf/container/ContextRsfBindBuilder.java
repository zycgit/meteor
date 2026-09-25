/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.container;

import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.RsfEnvironment;

import java.util.function.Supplier;
import net.hasor.rsf.utils.RsfInstances;

/**
 * 服务注册器
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
abstract class ContextRsfBindBuilder extends AbstractRsfBindBuilder {
    protected abstract RsfBeanContainer getContainer();

    protected abstract RsfContext getRsfContext();



    @Override
    protected <T> Supplier<? extends T> toProvider(Class<T> bindInfo) {
        return () -> RsfInstances.create(bindInfo);
    }

    public RsfEnvironment getEnvironment() {
        return this.getRsfContext().getEnvironment();
    }

    protected <T> RsfBindInfo<T> addService(ServiceDefine<T> serviceDefine) {
        getContainer().publishService(serviceDefine);
        return serviceDefine;
    }

    protected void addShareFilter(FilterDefine filterDefine) {
        this.getContainer().publishFilter(filterDefine);
    }
}
