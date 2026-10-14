/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage;
import java.util.Collection;
import java.util.Map;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.*;

/** 应用、实例、服务的业务存储契约；一次打开对应一个登记会话。 */
public interface StorageAccess extends AutoCloseable {
    // 持久目录与当前登记

    /** 点查持久应用；不存在返回 null。 */
    EntApplicationCatalogData getApplication(String applicationName) throws Exception;

    /** 幂等保存持久应用目录。 */
    void saveApplication(EntApplicationCatalogData application) throws Exception;

    /** 分页读取应用自身的目录，不聚合实例。 */
    RecordPage<EntApplicationCatalogData> applications(DirectoryQuery query) throws Exception;

    /** 点查实例身份及有效运行信息；是否保留离线身份由存储提供者决定，不存在返回 null。 */
    InstanceStatus getInstance(String instanceId) throws Exception;

    /** 分页读取指定应用下的完整实例目录；applicationName 为空表示集群实例目录。 */
    RecordPage<InstanceStatus> instances(String applicationName, DirectoryQuery query) throws Exception;

    /** 点查持久服务声明；不存在返回 null。 */
    EntServiceCatalogData getService(String serviceId) throws Exception;

    /** 保存持久服务声明，不修改当前成员与治理。 */
    void saveService(EntServiceCatalogData service) throws Exception;

    /** 分页读取持久服务目录。 */
    RecordPage<EntServiceCatalogData> services(DirectoryQuery query) throws Exception;

    /** 读取指定服务的成员摘要；各提供者封装原生聚合或摘要投影，缺项按空摘要处理。 */
    Map<String, ServiceSummary> serviceSummaries(Collection<String> serviceIds) throws Exception;

    /** 点查实例当前服务子项；未登记或会话失效返回 null。 */
    EntServiceRuntimeData getInstanceService(String instanceId, String serviceId) throws Exception;

    /** 分页读取指定实例当前服务子项。 */
    RecordPage<EntServiceRuntimeData> instanceServices(String instanceId, DirectoryQuery query) throws Exception;

    /** 点查服务下的完整成员；结果包含实例身份和服务运行信息。 */
    RefServiceRelationData getMember(String serviceId, MetServiceType role, String instanceId) throws Exception;

    /** 分页读取指定服务及角色的有效成员；结果直接包含身份与端点。 */
    RecordPage<RefServiceRelationData> members(String serviceId, MetServiceType role, DirectoryQuery query) throws Exception;

    /** 保存应用定义并登记本会话的实例运行信息；实例 ID 不应在并存进程间复用。 */
    void registerInstance(EntInstanceRuntimeData instance) throws Exception;

    /** 保存持久服务声明及当前成员关系；可从服务和实例两个方向查询。 */
    void registerService(String instanceId, EntServiceRuntimeData service) throws Exception;

    /** 撤回本会话单个服务的当前登记；保留服务声明和持久治理。 */
    void unregisterService(String instanceId, String serviceId) throws Exception;

    /** 查询指定应用档案中的单元名称，不读取各实例运行服务清单。 */
    String[] applicationUnits(String applicationName) throws Exception;

    /** 查询指定服务当前成员的单元名称。 */
    String[] serviceUnits(String serviceId) throws Exception;

    // 治理：两个 ID 均空为全局，仅 instanceId 为实例，仅 serviceId 为服务，两者为实例服务。

    /** 读取范围开关覆盖；不存在返回 null。 */
    EntityRecord<EntConfigurationData> getConfiguration(String instanceId, String serviceId) throws Exception;

    /** 条件保存范围开关；revision 为空只创建，冲突抛 ConcurrentModificationException。 */
    void saveConfiguration(String instanceId, String serviceId, EntityRecord<EntConfigurationData> value) throws Exception;

    /** 读取独立规则；不存在返回 null。 */
    EntityRecord<EntRuleConfigurationData> getRule(String instanceId, String serviceId, RuleKind kind) throws Exception;

    /** 条件保存单个规则；不改写其他规则及注册数据。 */
    void saveRule(String instanceId, String serviceId, RuleKind kind, EntityRecord<EntRuleConfigurationData> value) throws Exception;

    /** 读取整个元信息记录；不存在返回 null。 */
    EntityRecord<EntMetadataData> getMetadata(String instanceId, String serviceId) throws Exception;

    /** 条件保存整个元信息 Map；批量操作在单条记录内原子提交。 */
    void saveMetadata(String instanceId, String serviceId, EntityRecord<EntMetadataData> value) throws Exception;

    /** 条件删除开关覆盖，不影响实际登记。 */
    boolean deleteConfiguration(String instanceId, String serviceId, String revision) throws Exception;

    /** 条件删除单条规则及开关，恢复继承。 */
    boolean deleteRule(String instanceId, String serviceId, RuleKind kind, String revision) throws Exception;

    /** 条件删除整个元信息记录。 */
    boolean deleteMetadata(String instanceId, String serviceId, String revision) throws Exception;

    // 订阅与会话释放

    /** 订阅指定服务的提供者变化，包括失效；异步首次通知，返回取消句柄。 */
    Cancellable watchProviders(String serviceId, Runnable listener);

    /** 订阅一个治理范围的开关、规则与元信息；读取失败不当作删除。 */
    Cancellable watchConfiguration(String instanceId, String serviceId, Runnable listener);

    /** 关闭本会话及订阅，撤回当前登记，保留持久定义和持久治理。 */
    @Override
    void close() throws Exception;
}
