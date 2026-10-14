/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;

/** 管理一个实例；Default 读写存储配置，Target 通过 RPC 查询和修改实例的运行状态。 */
public interface MetInstanceAdmin {
    /** 读取实例规则；Target 读取现有业务服务的共同值，不一致时抛异常。 */
    String fetchRule(MetRuleType type);

    /** 更新 XML 规则正文，null 删除，空字符串清除规则。 */
    void updateRule(MetRuleType type, String rule);

    /** 清除实例规则正文；Default 保留存储开关，Target 清空当前业务服务的规则。 */
    void resetRule(MetRuleType type);

    /** 查询存储中的实例规则开关；Target 调用抛不支持异常。 */
    boolean isRuleEnabled(MetRuleType type);

    /** 启用存储中的实例规则并重新应用正文；Target 调用抛不支持异常。 */
    void enableRule(MetRuleType type);

    /** 禁用存储规则并清空应用规则，停止同类上层继承；Target 调用抛不支持异常。 */
    void disableRule(MetRuleType type);

    //
    // 实例状态
    //

    /** 获取目标实例的标识、阻断状态和监听地址等信息。 */
    MetInstanceInfo instance();

    /** 查询阻断状态；Default 优先返回期望配置，Target 返回当前状态。 */
    boolean isBlock();

    /** 解除网络业务调用阻断，不改变服务各自的启停设置。 */
    void unblock();

    /** 阻断网络业务调用；Default 经存储订阅生效，Target 在对端执行完成后返回。 */
    void block();

    //
    // 实例关联的服务
    //

    /** 分页读取目标实例的业务服务清单，角色、协议和成员数量仅统计该实例；query 为 null 时读取默认首页。 */
    MetPage<MetServiceInfo> getServiceList(MetDirectoryQuery query);

    /** 查询目标实例中指定业务服务的信息，未声明该服务时抛异常。 */
    MetServiceInfo getServiceById(String serviceId);
}
