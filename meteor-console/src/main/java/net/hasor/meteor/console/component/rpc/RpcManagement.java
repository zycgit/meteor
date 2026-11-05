/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.rpc;
import java.util.Map;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.admin.description.MetServiceDescription;

/** Console 间的注册中心管理通道，直接对应 RegistryAdmin 的基础管理能力。 */
@MetService(name = "meteor.console.Management", group = "meteor-console", version = "2", serializeType = "Json")
public interface RpcManagement {
    /** 管理凭据在 RPC 调用选项中的键。 */
    String CREDENTIAL_OPTION = "console.management.token";

    @MetMethod(id = 1)
    RegistryInfo registryInfo(RpcTarget target);

    @MetMethod(id = 2)
    RegistryStatus registryStatus(RpcTarget target);

    @MetMethod(id = 3)
    MetPage<MetServiceInfo> services(RpcTarget target, String filter, MetDirectoryQuery query);

    @MetMethod(id = 4)
    MetPage<String> applications(RpcTarget target, MetDirectoryQuery query);

    @MetMethod(id = 5)
    MetPage<MetInstanceInfo> instances(RpcTarget target, String application, MetDirectoryQuery query);

    @MetMethod(id = 6)
    String[] units(RpcTarget target, String application);

    @MetMethod(id = 7)
    MetInstanceInfo instanceInfo(RpcTarget target);

    @MetMethod(id = 8)
    MetServiceInfo serviceInfo(RpcTarget target, String serviceId);

    @MetMethod(id = 9)
    Map<String, MetServiceDescription> descriptions(RpcTarget target);

    @MetMethod(id = 10)
    MetPage<MetServiceMember> members(RpcTarget target, MetServiceType role, MetEndpointFilter filter, MetDirectoryQuery query);

    @MetMethod(id = 11)
    String fetchRule(RpcTarget target, MetRuleType type);

    @MetMethod(id = 12)
    void resetRule(RpcTarget target, MetRuleType type);

    @MetMethod(id = 13)
    MetValidationResult validateRule(RpcTarget target, MetRuleType type, String rule);

    @MetMethod(id = 14)
    void updateRule(RpcTarget target, MetRuleType type, String rule);

    @MetMethod(id = 15)
    boolean isRuleEnabled(RpcTarget target, MetRuleType type);

    @MetMethod(id = 16)
    void ruleEnabled(RpcTarget target, MetRuleType type, boolean enabled);

    @MetMethod(id = 17)
    boolean isServiceEnabled(RpcTarget target);

    @MetMethod(id = 18)
    void serviceEnabled(RpcTarget target, boolean enabled);

    @MetMethod(id = 19)
    boolean isInstanceBlocked(RpcTarget target);

    @MetMethod(id = 20)
    void instanceBlocked(RpcTarget target, boolean blocked);

    @MetMethod(id = 21)
    Map<String, Object> fetchMeta(RpcTarget target);

    @MetMethod(id = 22)
    void putMeta(RpcTarget target, Map<String, Object> values);

    @MetMethod(id = 23)
    void removeMeta(RpcTarget target, String[] keys);
}
