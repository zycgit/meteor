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
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.meta.ServiceMeta;
import net.hasor.meteor.domain.meta.ServiceMetaParser;

/**
 * 一次服务注册的有效配置与协议发布信息，公共 Java 声明由 ServiceMeta 共享。
 * @version : 2014年9月12日
 * @author 赵永春 (zyc@hasor.net)
 */
public class ServiceDomain<T> implements MetBindInfo<T> {
    private          String                    bindID;                                      // 服务ID
    private          String                    bindName;                                    // 服务名
    private          String                    bindGroup;                                   // 服务分组
    private          String                    bindVersion;                                 // 服务版本
    private final    Map<String, String>       aliasNameMap       = new HashMap<>();         // 别名
    private final    Class<T>                  bindType;                                    // 服务类型
    private          boolean                   asMessage;                                   // 是否由注册主动启用消息模式
    private          boolean                   asShadow;                                    // 是否隐藏服务
    private          boolean                   asDistributedCall;                           // 是否强制进入分布式调用链
    private          boolean                   isSharedThreadPool = true;                   // 是否共享调用线程池(提供者)
    private          int                       clientTimeout;                               // 调用超时（毫秒）
    private          String                    serializeType;                               // 传输序列化类型
    private          MetServiceType            serviceType;                                 // 服务类型（提供者 or 消费者）
    private final    Set<String>               bindProtocols      = new HashSet<>();        // 服务指定的协议类型
    private final    Map<String, Object>       metadata           = new ConcurrentHashMap<>();
    private volatile Map<String, ProtocolMeta> protocols          = Map.of();
    private final    ServiceMeta               serviceMeta;

    public ServiceDomain(Class<T> bindType) {
        this.bindType = bindType;
        this.serviceMeta = ServiceMetaParser.parse(bindType);
    }

    /** Java 公共声明，不包含端点选项或协议编译结果。 */
    public ServiceMeta getServiceMeta() {
        return this.serviceMeta;
    }

    /** 设置或清空原始资源声明，保留同一协议的路径声明与发布结果。 */
    public void setBindResource(String protocol, Object bindResource) {
        Map<String, ProtocolMeta> selected = new LinkedHashMap<>(this.protocols);
        String name = protocol.trim().toLowerCase(Locale.ROOT);

        ProtocolMeta previous = selected.get(name);
        ContextPath paths = previous == null ? null : previous.getContextPath();
        Map<String, ProtocolBinding> endpoints = previous == null ? Map.of() : previous.getEndpoints();

        boolean declared = paths != null || bindResource != null;
        if (declared || !endpoints.isEmpty()) {
            selected.put(name, new ProtocolMeta(this, paths, bindResource, endpoints));
        } else {
            selected.remove(name);
        }
        this.protocols = Collections.unmodifiableMap(selected);
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

    /**获取发布服务的有效分组名称。*/
    public String getBindGroup() {
        return this.bindGroup;
    }

    /**设置发布服务的有效分组名称。*/
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
        return this.asMessage || this.serviceMeta.isMessage();
    }

    /** 设置接口的工作状态,如果接口标记了@MetMessage,那么无论设置什么值 isMessage 都会返回true。 */
    public void setMessage(boolean asMessage) {
        this.asMessage = asMessage;
    }

    /** 是否强制进入分布式调用链。 */
    public boolean isDistributedCall() {
        return this.asDistributedCall;
    }

    /** 设置是否强制进入分布式调用链。 */
    public void setDistributed(boolean distributed) {
        this.asDistributedCall = distributed;
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

    public Map<String, ProtocolMeta> getProtocolMetas() {
        return this.protocols;
    }

    public ProtocolMeta getProtocolMeta(String protocol) {
        return this.protocols.get(protocol.toLowerCase(Locale.ROOT));
    }

    /** 整体提交已准备好的协议元信息，声明与端点结果由发布准备阶段补全。 */
    public void replaceProtocolPublication(Map<String, ProtocolMeta> protocols) {
        Map<String, ProtocolMeta> selected = new LinkedHashMap<>();
        // 直接接收本轮完整元信息，不在提交阶段重新合并或包装。
        for (Map.Entry<String, ProtocolMeta> entry : protocols.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            ProtocolMeta meta = entry.getValue();
            boolean declared = meta.getContextPath() != null || meta.getBindResource() != null;
            if (declared || !meta.getEndpoints().isEmpty()) {
                selected.put(name, meta);
            }
        }

        // 未纳入新结果的协议只保留原始声明，旧对象和在途请求持有的绑定保持不变。
        for (Map.Entry<String, ProtocolMeta> entry : this.protocols.entrySet()) {
            if (selected.containsKey(entry.getKey())) {
                continue;
            }

            ProtocolMeta meta = entry.getValue();
            if (meta.getContextPath() != null || meta.getBindResource() != null) {
                if (!meta.getEndpoints().isEmpty()) {
                    meta = new ProtocolMeta(meta.getService(), meta.getContextPath(), meta.getBindResource(), Map.of());
                }
                selected.put(entry.getKey(), meta);
            }
        }
        this.protocols = Collections.unmodifiableMap(selected);
    }

    /** 更新一个协议的原始路径声明，已持有的只读快照不随后续修改变化。 */
    public void setBindContextPaths(String protocol, ContextPath context) {
        if (StringUtils.isBlank(protocol)) {
            throw new IllegalArgumentException("Path protocol must not be blank");
        }
        Objects.requireNonNull(context, "context");

        // 替换项移到末尾，同一个规范化选择器重复声明时保留最后一次选择。
        Map<String, ProtocolMeta> selected = new LinkedHashMap<>(this.protocols);
        String key = protocol.trim().toLowerCase(Locale.ROOT);
        ProtocolMeta previous = selected.remove(key);
        Map<String, ProtocolBinding> endpoints = previous == null ? Map.of() : previous.getEndpoints();
        Object resource = previous == null ? null : previous.getBindResource();

        selected.put(key, new ProtocolMeta(this, context, resource, endpoints));
        this.protocols = Collections.unmodifiableMap(selected);
    }

    @Override
    public Set<String> getMetaKeys() {
        return Set.copyOf(this.metadata.keySet());
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
