/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.rpc;
import net.hasor.cobble.StringUtils;

/** 注册中心管理接口的作用域标识；不接受直连地址或目标实例凭据。 */
public final class RpcTarget {
    public enum Scope {
        CLUSTER,
        SERVICE,
        INSTANCE
    }

    private Scope  scope;
    private String serviceId;
    private String instanceId;

    public static RpcTarget cluster() {
        RpcTarget target = new RpcTarget();
        target.scope = Scope.CLUSTER;
        return target;
    }

    public static RpcTarget service(String serviceId, String instanceId) {
        RpcTarget target = new RpcTarget();
        target.scope = Scope.SERVICE;
        target.serviceId = serviceId;
        target.instanceId = instanceId;
        target.validate();
        return target;
    }

    public static RpcTarget instance(String instanceId) {
        RpcTarget target = new RpcTarget();
        target.scope = Scope.INSTANCE;
        target.instanceId = instanceId;
        target.validate();
        return target;
    }

    public Scope getScope() {
        return this.scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public String getServiceId() {
        return this.serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getInstanceId() {
        return this.instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public void validate() {
        if (this.scope == null) {
            throw new IllegalArgumentException("缺少注册中心管理作用域");
        }

        switch (this.scope) {
            case CLUSTER:
                if (StringUtils.isNotBlank(this.serviceId) || StringUtils.isNotBlank(this.instanceId)) {
                    throw new IllegalArgumentException("集群作用域不接受服务或实例 ID");
                }
                break;
            case SERVICE:
                if (StringUtils.isBlank(this.serviceId)) {
                    throw new IllegalArgumentException("缺少服务 ID");
                }
                break;
            case INSTANCE:
                if (StringUtils.isBlank(this.instanceId) || StringUtils.isNotBlank(this.serviceId)) {
                    throw new IllegalArgumentException("实例作用域要求实例 ID，且不接受服务 ID");
                }
                break;
        }
    }
}
