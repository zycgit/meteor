/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetMessage;

/**
 * 服务的描述信息，包括了服务的发布和订阅信息。
 * @version : 2014年9月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public class ServiceDomain<T> implements MetBindInfo<T> {
    private       String              bindID             = null;      // 服务ID
    private       String              bindName           = null;      // 服务名
    private       String              bindGroup          = "default"; // 服务分组
    private       String              bindVersion        = "1.0.0";   // 服务版本
    private       Map<String, String> aliasNameMap       = null;      // 别名
    private       Class<T>            bindType           = null;      // 服务类型
    private       boolean             asMessage          = false;     // 是否为消息接口
    private       boolean             asShadow           = false;     // 是否为消息接口
    private       boolean             isSharedThreadPool = true;      // 是否共享调用线程池(提供者)
    private       int                 clientTimeout      = 6000;      // 调用超时（毫秒）
    private       String              serializeType      = null;      // 传输序列化类型
    private       MetServiceType      serviceType        = null;      // 服务类型（提供者 or 消费者）
    private       Set<String>         bindProtocols      = null;      // 服务特殊置顶的协议类型
    private final Map<String, Object> metadata           = new ConcurrentHashMap<>();

    public ServiceDomain(Class<T> bindType) {
        this.bindType = bindType;
        this.asMessage = bindType.isAnnotationPresent(MetMessage.class);
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
        return this.asMessage || this.bindType.isAnnotationPresent(MetMessage.class);
    }

    /** 设置接口的工作状态,如果接口标记了@MetMessage,那么无论设置什么值 isMessage 都会返回true。 */
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
    public MetServiceType getServiceType() {
        return this.serviceType;
    }

    /**设置服务类型，消费者还是提供者*/
    public void setServiceType(MetServiceType serviceType) {
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

    public Object getMetaData(String key) {
        return this.metadata.get(Objects.requireNonNull(key, "metadata key"));
    }

    public void setMetaData(String key, Object value) {
        Objects.requireNonNull(key, "metadata key");
        if (value == null) {
            this.metadata.remove(key);
        } else {
            if (!this.metadata.containsKey(key) && this.metadata.size() >= MAX_METADATA_SIZE) {
                throw new IllegalArgumentException("Service metadata cannot exceed " + MAX_METADATA_SIZE + " entries");
            }
            this.metadata.put(key, value);
        }
    }

    public void setMetaData(Map<String, Object> values) {
        // 先在副本上应用删除，腾出新增条目的容量。
        Map<String, Object> merged = new HashMap<>(this.metadata);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metadata key");
            if (entry.getValue() == null) {
                merged.remove(key);
            }
        }

        // 按当前可见数据检查容量，校验失败不写入；并发更新时允许超过上限。
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() != null) {
                if (!merged.containsKey(entry.getKey()) && merged.size() >= MAX_METADATA_SIZE) {
                    throw new IllegalArgumentException("Service metadata cannot exceed " + MAX_METADATA_SIZE + " entries");
                }
                merged.put(entry.getKey(), entry.getValue());
            }
        }

        // 只更新本次传入的键，保留其他线程对无关键的修改。
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            if (entry.getValue() == null) {
                this.metadata.remove(entry.getKey());
            } else {
                this.metadata.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public void removeMetaData(String key) {
        this.metadata.remove(Objects.requireNonNull(key, "metadata key"));
    }

    @Override
    public String toString() {
        return "ServiceDomain{" + "bindID='" + this.bindID + '\'' + //
                ", bindName='" + this.bindName + '\'' +             //
                ", bindGroup='" + this.bindGroup + '\'' +           //
                ", bindVersion='" + this.bindVersion + '\'' +       //
                ", bindType=" + this.bindType +                     //
                '}';
    }
}