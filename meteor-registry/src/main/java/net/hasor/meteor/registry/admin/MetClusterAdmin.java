/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.util.Map;

/** 集群级治理接口，管理全局规则、服务与实例目录及注册中心信息。 */
public interface MetClusterAdmin {
    //
    // 注册中心信息与后端可达性
    //

    /** 获取注册中心适配器、隔离范围和支持能力等信息。 */
    RegistryInfo info();

    /** 检查注册中心存储后端的可达状态。 */
    RegistryStatus status();

    //
    // 服务与实例目录；空过滤条件表示全部
    //

    /** 按服务标识包含的文本筛选服务目录，过滤条件为空时返回全部。 */
    MetServiceInfo[] services(String filter);

    /** 按应用名称查询实例目录，应用名称为空时返回全部。 */
    MetInstanceInfo[] instances(String applicationName);

    //
    // 全局默认规则
    //

    /** 获取全局保存的规则正文；null 表示未保存，空字符串表示清除规则，不受启停开关影响。 */
    String fetchRule(MetRuleType type);

    /** 删除全局默认规则正文，保留策略开关及其他范围；没有其他规则时清空应用规则。 */
    void resetRule(MetRuleType type);

    /** 校验指定类型的规则，不保存配置。 */
    MetValidationResult validateRule(MetRuleType type, String rule);

    /** 只更新全局默认规则正文，null 删除正文，空字符串清除规则，保留独立启停开关。 */
    void updateRule(MetRuleType type, String rule);

    /** 查询当前范围的策略开关，未设置时为启用；不表示应用已经完成同步。 */
    boolean isRuleEnabled(MetRuleType type);

    /** 启用当前范围的策略，恢复使用已保存的正文；未保存正文时继续继承。 */
    void enableRule(MetRuleType type);

    /** 禁用当前范围的策略，保留正文，并阻止继承同类上层规则。 */
    void disableRule(MetRuleType type);

    //
    // 全局默认元数据
    //

    /** 设置单个全局默认元数据值，保留其他键，合并后最多 1000 条。 */
    void putMeta(String key, Object value);

    /** 原子合并全局默认元数据，保留其他键；提交及合并后最多 1000 条，键和值不能为 null。 */
    void putMeta(Map<String, Object> values);

    /** 原子移除指定全局默认元数据键，保留服务与实例覆盖；重复或不存在的键被忽略，空参数不修改配置。 */
    void removeMeta(String... keys);
}
