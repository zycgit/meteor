/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.rpc;
import java.util.Map;
import net.hasor.meteor.MetService;

/** 目标实例的管理通信契约，服务操作通过 serviceId 选择本地服务，独立于管理 API 和存储访问。 */
@MetService(group = "meteor-management", version = "1", serializeType = "Json")
public interface ManagementRPC {
    // 实例和服务快照

    /** 读取当前实例信息。 */
    InstanceData instance();

    /** 读取当前运行配置。 */
    Map<String, Object> settings();

    /** 读取本实例声明的业务服务摘要。 */
    ServiceInfoData[] services();

    /** 读取指定业务服务的声明和端点，摘要由管理门面提取。 */
    ServiceMemberData service(String serviceId);

    /** 读取指定服务的本地地址本。 */
    AddressData addresses(String serviceId);

    // 实例阻断与默认选项

    /** 查询本实例的网络业务调用阻断状态。 */
    boolean isBlock();

    /** 设置网络业务调用阻断状态，管理调用不受影响。 */
    void setBlock(boolean block);

    /** 更新请求默认选项，null 删除该选项。 */
    void setRequestOption(String name, String value);

    /** 更新响应默认选项，null 删除该选项。 */
    void setResponseOption(String name, String value);

    // 实例规则

    /** 读取现有业务服务共同的规则正文；各服务不一致时抛异常。 */
    String fetchInstanceRule(RuleTypeData type);

    /** 将规则应用到现有业务服务，null 清空；不保存未来服务的规则模板。 */
    void updateInstanceRule(RuleTypeData type, String rule);

    // 服务启停与规则

    /** 查询指定服务的网络调用启用状态。 */
    boolean isServiceEnabled(String serviceId);

    /** 设置指定服务的网络调用启用状态。 */
    void setServiceEnabled(String serviceId, boolean enabled);

    /** 读取指定服务的规则正文。 */
    String fetchServiceRule(String serviceId, RuleTypeData type);

    /** 在目标实例校验服务规则，不更新配置。 */
    ValidationData validateServiceRule(String serviceId, RuleTypeData type, String rule);

    /** 更新指定服务的规则，null 清空当前规则。 */
    void updateServiceRule(String serviceId, RuleTypeData type, String rule);

    // 服务元数据

    /** 合并指定服务的元数据。 */
    void putServiceMeta(String serviceId, Map<String, Object> values);

    /** 删除指定服务的元数据键，不恢复历史值。 */
    void removeServiceMeta(String serviceId, String[] keys);
}
