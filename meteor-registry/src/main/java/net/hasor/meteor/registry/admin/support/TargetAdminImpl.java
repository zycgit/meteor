/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin.support;
import java.util.Objects;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.shared.rpc.ManagementRPC;

/** 通过调用方提供的 Meteor 实例创建直连管理对象，不持有存储会话。 */
public final class TargetAdminImpl implements TargetAdmin {
    private final MetContext context;

    public TargetAdminImpl(MetContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public MetInstanceAdmin getInstanceAdminByAddress(InterAddress address) {
        return new TargetInstanceAdmin(this.remote(address));
    }

    @Override
    public MetServiceAdmin getServiceAdminByAddress(InterAddress address, String serviceId) {
        if (StringUtils.isBlank(serviceId)) {
            throw new IllegalArgumentException("Service ID must not be blank");
        } else {
            return new TargetServiceAdmin(this.remote(address), serviceId);
        }
    }

    private ManagementRPC remote(InterAddress address) {
        Objects.requireNonNull(address, "management address");
        synchronized (this.context) {
            if (this.context.getServiceInfo(ManagementRPC.class) == null) {
                this.context.publisher().rsfService(ManagementRPC.class).asShadow().register();
            }
        }

        return this.context.getRsfClient(address).wrapper(ManagementRPC.class);
    }
}
