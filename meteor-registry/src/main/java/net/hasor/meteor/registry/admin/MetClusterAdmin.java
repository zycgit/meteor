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
    // 应用、服务与实例目录分页查询
    //

    /** 分页查询服务目录；文本条件使用 filter，query 提供字段、协议、单元及分页条件。 */
    MetPage<MetServiceInfo> services(String filter, MetDirectoryQuery query);

    /** 分页读取持久应用目录；query 为 null 时读取默认首页，应用全部实例离线后仍保留。 */
    MetPage<String> applications(MetDirectoryQuery query);

    /** 分页查询应用下的实例档案，包含在线状态；应用名称为空表示集群实例目录。 */
    MetPage<MetInstanceInfo> instances(String applicationName, MetDirectoryQuery query);

    /** 查询指定应用的单元目录，不展开实例服务清单。 */
    String[] units(String application);

    //
    // 全局默认规则
    //

    /** 获取全局保存的规则正文；null 表示未保存，空字符串表示清除规则，不受启停开关影响。 */
    String fetchRule(MetRuleType type);

    /** 删除全局默认规则正文，保留策略开关及其他范围；没有其他规则时清空应用规则。 */
    void resetRule(MetRuleType type);

    /** 使用 XML 验证规则，不修改当前配置。 */
    MetValidationResult validateRule(MetRuleType type, String rule);

    /** 更新 XML 规则正文，null 删除，空字符串清除规则。 */
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

    /** 获取全局保存的元数据，无配置时返回空 Map，修改返回值不会写入存储。 */
    Map<String, Object> fetchMeta();

    /** 设置单个全局默认元数据值，保留其他键，合并后最多 1000 条。 */
    void putMeta(String key, Object value);

    /** 原子合并全局默认元数据，保留其他键；提交及合并后最多 1000 条，键和值不能为 null。 */
    void putMeta(Map<String, Object> values);

    /** 原子移除指定全局默认元数据键，保留服务与实例覆盖；重复或不存在的键被忽略，空参数不修改配置。 */
    void removeMeta(String... keys);
}
