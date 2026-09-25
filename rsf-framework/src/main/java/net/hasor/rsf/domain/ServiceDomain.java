/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.domain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.hasor.cobble.StringUtils;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfMessage;

/**
 * 服务的描述信息，包括了服务的发布和订阅信息。
 * @version : 2014年9月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public class ServiceDomain<T> implements RsfBindInfo<T> {
    private final ConcurrentMap<String, Object> metadata = new ConcurrentHashMap<>();

    public Object getMetaData(String key) {
        return this.metadata.get(key);
    }

    public void setMetaData(String key, Object value) {
        if (value == null) {
            this.metadata.remove(key);
        } else {
            this.metadata.put(key, value);
        }
    }

    public void removeMetaData(String key) {
        this.metadata.remove(key);
    }

    private String              bindID             = null;      // 服务ID
    private String              bindName           = null;      // 服务名
    private String              bindGroup          = "default"; // 服务分组
    private String              bindVersion        = "1.0.0";   // 服务版本
    private Map<String, String> aliasNameMap       = null;      // 别名
    private Class<T>            bindType           = null;      // 服务类型
    private boolean             asMessage          = false;     // 是否为消息接口
    private boolean             asShadow           = false;     // 是否为消息接口
    private boolean             isSharedThreadPool = true;      // 是否共享调用线程池(提供者)
    private int                 clientTimeout      = 6000;      // 调用超时（毫秒）
    private String              serializeType      = null;      // 传输序列化类型
    private RsfServiceType      serviceType        = null;      // 服务类型（提供者 or 消费者）
    private Set<String>         bindProtocols      = null;      // 服务特殊置顶的协议类型

    public ServiceDomain(Class<T> bindType) {
        this.bindType = bindType;
        this.asMessage = bindType.isAnnotationPresent(RsfMessage.class);
        this.aliasNameMap = new HashMap<>();
        this.bindProtocols = new HashSet<>();
    }

    public String getBindID() {
        if (this.bindID == null) {
            this.bindID = String.format("[%s]%s-%s", this.bindGroup, this.bindName, this.bindVersion);
        }
        return this.bindID;
    }

    /**获取发布服务的名称。*/
    public String getBindName() {
        return this.bindName;
    }

    @Override
    public Set<String> getAliasTypes() {
        return Collections.unmodifiableSet(this.aliasNameMap.keySet());
    }

    /**设置发布服务的名称。*/
    public void setBindName(String bindName) {
        this.bindName = bindName;
    }

    /**获取发布服务的分组名称（默认是：default）。*/
    public String getBindGroup() {
        return this.bindGroup;
    }

    /**设置发布服务的分组名称（默认是：default）。*/
    public void setBindGroup(String bindGroup) {
        this.bindGroup = bindGroup;
    }

    /**获取发布服务的版本号。*/
    public String getBindVersion() {
        return this.bindVersion;
    }

    /**设置发布服务的版本号。*/
    public void setBindVersion(String bindVersion) {
        this.bindVersion = bindVersion;
    }

    /** @return 别名 */
    public String getAliasName(String aliasType) {
        return this.aliasNameMap.get(aliasType);
    }

    /**设置服务别名*/
    public void putAliasName(String aliasType, String aliasName) {
        aliasType = Objects.requireNonNull(aliasType, "aliasType is null.");
        aliasName = Objects.requireNonNull(aliasName, "aliasName is null.");
        this.aliasNameMap.put(aliasType, aliasName);
    }

    /**服务类型*/
    public Class<T> getBindType() {
        return this.bindType;
    }

    /**是否为消息接口。*/
    public boolean isMessage() {
        return this.asMessage || this.bindType.isAnnotationPresent(RsfMessage.class);
    }

    /** 设置接口的工作状态,如果接口标记了@RsfMessage,那么无论设置什么值 isMessage 都会返回true。 */
    public void setMessage(boolean asMessage) {
        this.asMessage = asMessage;
    }

    /** 接口是否要求工作在隐藏模式下。*/
    public boolean isShadow() {
        return this.asShadow;
    }

    public void setShadow(boolean asShadow) {
        this.asShadow = asShadow;
    }

    /**获取客户端调用服务超时时间。*/
    public int getClientTimeout() {
        return this.clientTimeout;
    }

    /**设置客户端调用服务超时时间。*/
    public void setClientTimeout(int clientTimeout) {
        this.clientTimeout = clientTimeout;
    }

    /**获取客户端使用的对象序列化格式。*/
    public String getSerializeType() {
        return this.serializeType;
    }

    @Override
    public boolean isSharedThreadPool() {
        return this.isSharedThreadPool;
    }

    public void setSharedThreadPool(boolean sharedThreadPool) {
        this.isSharedThreadPool = sharedThreadPool;
    }

    /**设置客户端使用的对象序列化格式。*/
    public void setSerializeType(String serializeType) {
        this.serializeType = serializeType;
    }

    /**获取服务类型，消费者还是提供者*/
    public RsfServiceType getServiceType() {
        return this.serviceType;
    }

    /**设置服务类型，消费者还是提供者*/
    public void setServiceType(RsfServiceType serviceType) {
        this.serviceType = serviceType;
    }

    @Override
    public Set<String> getBindProtocols() {
        return this.bindProtocols;
    }

    public void addBindProtocol(String bindProtocol) {
        if (StringUtils.isBlank(bindProtocol)) {
            return;
        }
        this.bindProtocols.add(bindProtocol);
    }

    @Override
    public String toString() {
        return "ServiceDomain{" + "bindID='" + this.bindID + '\'' +//
                ", bindName='" + this.bindName + '\'' + //
                ", bindGroup='" + this.bindGroup + '\'' +//
                ", bindVersion='" + this.bindVersion + '\'' + //
                ", bindType=" + this.bindType + //
                '}';
    }
}