/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.rpc;
import java.util.Map;
import java.util.Objects;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;

/** RPC 服务端借用 Provider，直接转发注册中心 API，不执行控制台业务命令或管理其生命周期。 */
public final class RpcManagementImpl implements RpcManagement {
    private final ProviderConnection connection;

    public RpcManagementImpl(ProviderConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    @Override
    public RegistryInfo registryInfo(RpcTarget target) {
        return cluster(target).info();
    }

    @Override
    public RegistryStatus registryStatus(RpcTarget target) {
        return cluster(target).status();
    }

    @Override
    public MetPage<MetServiceInfo> services(RpcTarget target, String filter, MetDirectoryQuery query) {
        return switch (scope(target)) {
            case CLUSTER -> cluster(target).services(filter, query);
            case INSTANCE -> {
                if (StringUtils.isNotBlank(filter)) {
                    throw new IllegalArgumentException("实例服务目录使用 query 提供查询条件");
                }
                yield instance(target).getServiceList(query);
            }
            case SERVICE -> throw new IllegalArgumentException("服务目录不接受服务作用域");
        };
    }

    @Override
    public MetPage<String> applications(RpcTarget target, MetDirectoryQuery query) {
        return cluster(target).applications(query);
    }

    @Override
    public MetPage<MetInstanceInfo> instances(RpcTarget target, String application, MetDirectoryQuery query) {
        return cluster(target).instances(application, query);
    }

    @Override
    public String[] units(RpcTarget target, String application) {
        return switch (scope(target)) {
            case CLUSTER -> cluster(target).units(application);
            case SERVICE -> service(target).units();
            case INSTANCE -> throw new IllegalArgumentException("单元目录不接受实例作用域");
        };
    }

    @Override
    public MetInstanceInfo instanceInfo(RpcTarget target) {
        return instance(target).instance();
    }

    @Override
    public MetServiceInfo serviceInfo(RpcTarget target, String serviceId) {
        return switch (scope(target)) {
            case SERVICE -> service(target).info();
            case INSTANCE -> instance(target).getServiceById(serviceId);
            case CLUSTER -> throw new IllegalArgumentException("服务信息要求服务或实例作用域");
        };
    }

    @Override
    public Map<String, MetServiceDescription> descriptions(RpcTarget target) {
        return service(target).descriptions();
    }

    @Override
    public MetPage<MetServiceMember> members(RpcTarget target, MetServiceType role, MetEndpointFilter filter, MetDirectoryQuery query) {
        MetServiceAdmin admin = service(target);
        if (role == MetServiceType.Provider) {
            return admin.providers(filter, query);
        }
        if (role == MetServiceType.Consumer) {
            return admin.consumers(filter, query);
        }
        throw new IllegalArgumentException("成员目录要求提供者或消费者角色");
    }

    @Override
    public String fetchRule(RpcTarget target, MetRuleType type) {
        return switch (scope(target)) {
            case CLUSTER -> cluster(target).fetchRule(type);
            case SERVICE -> service(target).fetchRule(type);
            case INSTANCE -> instance(target).fetchRule(type);
        };
    }

    @Override
    public void resetRule(RpcTarget target, MetRuleType type) {
        switch (scope(target)) {
            case CLUSTER -> cluster(target).resetRule(type);
            case SERVICE -> service(target).resetRule(type);
            case INSTANCE -> instance(target).resetRule(type);
        }
    }

    @Override
    public MetValidationResult validateRule(RpcTarget target, MetRuleType type, String rule) {
        return switch (scope(target)) {
            case CLUSTER -> cluster(target).validateRule(type, rule);
            case SERVICE -> service(target).validateRule(type, rule);
            case INSTANCE -> throw new UnsupportedOperationException("实例作用域不支持独立规则校验");
        };
    }

    @Override
    public void updateRule(RpcTarget target, MetRuleType type, String rule) {
        switch (scope(target)) {
            case CLUSTER -> cluster(target).updateRule(type, rule);
            case SERVICE -> service(target).updateRule(type, rule);
            case INSTANCE -> instance(target).updateRule(type, rule);
        }
    }

    @Override
    public boolean isRuleEnabled(RpcTarget target, MetRuleType type) {
        return switch (scope(target)) {
            case CLUSTER -> cluster(target).isRuleEnabled(type);
            case SERVICE -> service(target).isRule(type);
            case INSTANCE -> instance(target).isRuleEnabled(type);
        };
    }

    @Override
    public void ruleEnabled(RpcTarget target, MetRuleType type, boolean enabled) {
        switch (scope(target)) {
            case CLUSTER -> {
                MetClusterAdmin admin = cluster(target);
                if (enabled) {
                    admin.enableRule(type);
                } else {
                    admin.disableRule(type);
                }
            }
            case SERVICE -> {
                MetServiceAdmin admin = service(target);
                if (enabled) {
                    admin.enableRule(type);
                } else {
                    admin.disableRule(type);
                }
            }
            case INSTANCE -> {
                MetInstanceAdmin admin = instance(target);
                if (enabled) {
                    admin.enableRule(type);
                } else {
                    admin.disableRule(type);
                }
            }
        }
    }

    @Override
    public boolean isServiceEnabled(RpcTarget target) {
        return service(target).isEnable();
    }

    @Override
    public void serviceEnabled(RpcTarget target, boolean enabled) {
        MetServiceAdmin admin = service(target);
        if (enabled) {
            admin.enable();
        } else {
            admin.disable();
        }
    }

    @Override
    public boolean isInstanceBlocked(RpcTarget target) {
        return instance(target).isBlock();
    }

    @Override
    public void instanceBlocked(RpcTarget target, boolean blocked) {
        MetInstanceAdmin admin = instance(target);
        if (blocked) {
            admin.block();
        } else {
            admin.unblock();
        }
    }

    @Override
    public Map<String, Object> fetchMeta(RpcTarget target) {
        return switch (scope(target)) {
            case CLUSTER -> cluster(target).fetchMeta();
            case SERVICE -> service(target).fetchMeta();
            case INSTANCE -> throw new UnsupportedOperationException("实例作用域不支持元信息配置");
        };
    }

    @Override
    public void putMeta(RpcTarget target, Map<String, Object> values) {
        switch (scope(target)) {
            case CLUSTER -> cluster(target).putMeta(values);
            case SERVICE -> service(target).putMeta(values);
            case INSTANCE -> throw new UnsupportedOperationException("实例作用域不支持元信息配置");
        }
    }

    @Override
    public void removeMeta(RpcTarget target, String[] keys) {
        switch (scope(target)) {
            case CLUSTER -> cluster(target).removeMeta(keys);
            case SERVICE -> service(target).removeMeta(keys);
            case INSTANCE -> throw new UnsupportedOperationException("实例作用域不支持元信息配置");
        }
    }

    private RpcTarget.Scope scope(RpcTarget target) {
        if (target == null) {
            throw new IllegalArgumentException("缺少注册中心管理目标");
        }
        target.validate();
        return target.getScope();
    }

    private MetClusterAdmin cluster(RpcTarget target) {
        if (scope(target) != RpcTarget.Scope.CLUSTER) {
            throw new IllegalArgumentException("操作要求集群作用域");
        }
        return registry().getClusterAdmin();
    }

    private MetInstanceAdmin instance(RpcTarget target) {
        if (scope(target) != RpcTarget.Scope.INSTANCE) {
            throw new IllegalArgumentException("操作要求实例作用域");
        }
        return registry().getInstanceAdmin(target.getInstanceId());
    }

    private MetServiceAdmin service(RpcTarget target) {
        if (scope(target) != RpcTarget.Scope.SERVICE) {
            throw new IllegalArgumentException("操作要求服务作用域");
        }
        RegistryAdmin registry = registry();
        if (StringUtils.isBlank(target.getInstanceId())) {
            return registry.getServiceAdmin(target.getServiceId());
        }
        return registry.getServiceAdmin(target.getServiceId(), target.getInstanceId());
    }

    private RegistryAdmin registry() {
        return this.connection.registry();
    }
}
