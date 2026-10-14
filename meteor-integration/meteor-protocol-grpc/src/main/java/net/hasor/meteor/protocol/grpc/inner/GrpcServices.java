/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.inner;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.MetExtensionFactory;

/** 显式安装标准 Health 与 Reflection 服务，复用 Meteor 发布、过滤器和调用生命周期。 */
public final class GrpcServices implements MetExtensionFactory {
    private boolean health     = true;
    private boolean reflection = true;

    public void setHealth(boolean health) {
        this.health = health;
    }

    public void setReflection(boolean reflection) {
        this.reflection = reflection;
    }

    @Override
    public MetExtension create(MetContext context) throws Exception {
        return new GrpcServicesExtension(context, this.health, this.reflection);
    }
}
