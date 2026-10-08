/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import java.util.Map;

/** 管理一个服务；Default 操作存储配置，Target 通过已有 API 修改目标实例，范围在创建时确定。 */
public interface MetServiceAdmin {
    //
    // 服务信息与关联实例
    //

    /** 获取当前范围的服务标识及提供者、消费者数量。 */
    MetServiceInfo info();

    /** 获取当前范围的服务声明和方法列表，服务级优先选提供者，实例级使用目标实例声明。 */
    MetServiceDetail detail();

    /** 在当前范围内按端点条件查询提供者，null 表示不额外筛选，保留完整端点信息。 */
    MetServiceMember[] providers(MetEndpointFilter filter);

    /** 在当前范围内按端点条件查询消费者，null 表示不额外筛选，保留完整端点信息。 */
    MetServiceMember[] consumers(MetEndpointFilter filter);

    //
    // 规则校验与按项更新
    //

    /** 获取规则正文；Default 读取存储正文，Target 读取应用当前规则。 */
    String fetchRule(MetRuleType type);

    /** 删除规则正文；Default 保留开关并重新继承，Target 清空应用当前规则。 */
    void resetRule(MetRuleType type);

    /** 校验指定类型的规则，不保存配置。 */
    MetValidationResult validateRule(MetRuleType type, String rule);

    /** 更新指定规则正文；Default 保留存储开关，Target 直接应用；null 删除，空字符串清除规则。 */
    void updateRule(MetRuleType type, String rule);

    /** Default 查询存储策略开关，未设置时为启用；Target 判断应用当前是否配置了非空规则正文。 */
    boolean isRule(MetRuleType type);

    /** 启用存储策略，重新应用保存的正文或继承值；Target 调用抛不支持异常。 */
    void enableRule(MetRuleType type);

    /** 禁用存储策略并清空应用规则，保留存储正文且不继承上层；Target 调用抛不支持异常。 */
    void disableRule(MetRuleType type);

    //
    // 服务启停
    //

    /** 查询服务启用状态；Default 返回配置状态，Target 返回目标实例的当前状态。 */
    boolean isEnable();

    /** 启用服务；Default 的实例配置受服务整体禁用约束，Target 修改本机过滤器状态。 */
    void enable();

    /** 禁用当前范围内的服务，停止对应实例的该服务网络调用和地址发布。 */
    void disable();

    //
    // 元数据按键管理
    //

    /** 设置当前范围的单个元数据覆盖值，保留其他键，合并后最多 1000 条。 */
    void putMeta(String key, Object value);

    /** 批量合并元数据，保留其他键；最多 1000 条且键值非 null，Default 按单条存储记录原子更新。 */
    void putMeta(Map<String, Object> values);

    /** 删除指定元数据键；Default 重新继承存储值，Target 直接删除；重复或不存在的键被忽略。 */
    void removeMeta(String... keys);
}
