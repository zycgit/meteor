/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.MetServiceType;

/**
 * Rsf绑定信息。
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetBindInfo<T> {
    /** 服务元数据容量检查阈值，并发写入时允许超出。 */
    int MAX_METADATA_SIZE = 1000;

    /** 返回当前可见元信息键的只读快照，不随后续修改变化。 */
    Set<String> getMetaKeys();

    Object getMetaData(String key);

    /** 设置一个元数据值，null 表示删除，新增键按当前可见容量检查。 */
    void setMetaData(String key, Object value);

    /** 合并元数据，null 值表示删除；先校验再逐项更新，不保证整批原子性。 */
    void setMetaData(Map<String, Object> values);

    void removeMetaData(String key);

    /** @return 唯一标识（客户端唯一标识）。*/
    String getBindID();

    /** @return 服务名称。*/
    String getBindName();

    /** @return 获取已经定义的别名 */
    Set<String> getAliasTypes();

    /** @return 别名 */
    String getAliasName(String aliasType);

    /** @return 服务分组。*/
    String getBindGroup();

    /** @return 服务版本。*/
    String getBindVersion();

    /** @return 注册的服务类型。*/
    Class<T> getBindType();

    /** @return 是提供者还是消费者*/
    MetServiceType getServiceType();

    /** @return 指定使用的RPC通信协议，如果为空表示没有特殊指定。RSF将使用 MetContext.runProtocols()*/
    Set<String> getBindProtocols();

    /**
     * 返回按协议选择的上下文路径及匹配方式，键统一为小写，值和映射均只读。
     * 未选择路径的协议使用其默认上下文；显式选择不自动包含默认上下文，不影响 {@link #getBindProtocols()}。
     */
    Map<String, ContextPath> getBindContextPaths();

    /**
     * 返回接口是否为一个 Message 接口。
     * @see MetMessage
     */
    boolean isMessage();

    /** @return 是否强制进入分布式调用链；默认优先调用本地提供者。 */
    boolean isDistributedCall();

    /** 接口是否要求工作在隐藏模式下。*/
    boolean isShadow();

    /** @return 获取客户端调用服务超时时间。*/
    int getClientTimeout();

    /** @return 服务显式指定的序列化器；null 表示由调用所用的协议接入点决定。 */
    String getSerializeType();

    /** 服务绑定的本地资源，由协议扩展解释；未绑定时返回 null，不参与治理元数据同步。 */
    Object getBindResource();

    /** 服务的执行线程池是否为共享线程池。 */
    boolean isSharedThreadPool();
}
