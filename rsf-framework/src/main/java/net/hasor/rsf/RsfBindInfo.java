/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf;
import java.util.Set;
import net.hasor.rsf.domain.RsfServiceType;

/**
 * Rsf绑定信息。
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface RsfBindInfo<T> {
    Object getMetaData(String key);

    void setMetaData(String key, Object value);

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
    RsfServiceType getServiceType();

    /** @return 指定使用的RPC通信协议，如果为空表示没有特殊指定。RSF将使用 RsfContext.runProtocols()*/
    Set<String> getBindProtocols();

    /**
     * 返回接口是否为一个 Message 接口。
     * @see RsfMessage
     */
    boolean isMessage();

    /** 接口是否要求工作在隐藏模式下。*/
    boolean isShadow();

    /** @return 获取客户端调用服务超时时间。*/
    int getClientTimeout();

    /** @return 获取序列化方式*/
    String getSerializeType();

    /** 服务的执行线程池是否为共享线程池。 */
    boolean isSharedThreadPool();
}