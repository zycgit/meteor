/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.container;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.rsf.*;
import net.hasor.rsf.address.AddressPool;
import net.hasor.rsf.address.RouteTypeEnum;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.domain.RsfServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @version : 2015年12月6日
 * @author 赵永春 (zyc@hasor.net)
 */
public class RsfContainer {
    protected            Logger                                     logger       = LoggerFactory.getLogger(this.getClass());
    private final static Supplier[]                                 EMPTY_FILTER = new Supplier[0];
    private final        Map<String, ServiceDefine<?>>              serviceMap   = new ConcurrentHashMap<>();
    private final        Map<String, ConcurrentMap<String, String>> aliasNameMap = new ConcurrentHashMap<>();
    private final        List<FilterDefine>                         filterList   = new ArrayList<>();
    private final        Object                                     filterLock   = new Object();
    private final        Map<String, Supplier<RsfFilter>[]>         filterCache  = new ConcurrentHashMap<>();
    private final        AddressPool                                addressPool;
    private final        RsfSettings                                settings;

    public RsfContainer(AddressPool addressPool, RsfSettings settings) {
        this.settings = settings;
        this.addressPool = addressPool;
    }

    /**
     * 计算指定服务上配置的过滤器。{@link RsfFilter}按照配置方式分为共有和私有。
     * 共有Filter的生效范围是所有Service，私有Filter的生效范围仅Service。
     * 每一个Filter在配置的时候都需要指定ID，根据ID私有Filter可以覆盖共有Filter的配置。
     * @param serviceID 服务ID
     */
    public Supplier<RsfFilter>[] getFilterProviders(String serviceID) {
        synchronized (this.filterLock) {
            ServiceDefine<?> info = this.serviceMap.get(serviceID);
            if (info == null) {
                return EMPTY_FILTER;
            }

            Supplier<RsfFilter>[] result = this.filterCache.get(serviceID);
            if (result == null) {
                Map<String, FilterDefine> filters = new LinkedHashMap<>();
                for (FilterDefine filter : this.filterList) {
                    filters.put(filter.filterID(), filter);
                }

                for (FilterDefine filter : info.getFilterSnapshots()) {
                    // 私有过滤器覆盖同名全局过滤器，并按注册顺序排在全局过滤器之后。
                    filters.remove(filter.filterID());
                    filters.put(filter.filterID(), filter);
                }

                result = filters.values().toArray(new Supplier[0]);
                this.filterCache.put(serviceID, result);
            }

            return result.clone();
        }
    }

    /**
     * 根据服务id获取服务对象。如果服务未定义或者服务未声明提供者，则返回null。
     * @param rsfBindInfo 服务ID。
     * @return 服务提供者
     */
    public <T> Supplier<T> getProvider(RsfBindInfo<T> rsfBindInfo) {
        ServiceDefine<?> info = this.serviceMap.get(rsfBindInfo.getBindID());
        if (info == null) {
            return null;
        }

        Supplier<?> target = info.getCustomerProvider();
        if (target != null) {
            return (Supplier<T>) target;
        }

        return null;
    }

    /**
     * 根据服务id获取服务元信息。
     * @param serviceID 服务ID。
     */
    public RsfBindInfo<?> getRsfBindInfo(String serviceID) {
        ServiceDefine<?> info = this.serviceMap.get(serviceID);
        if (info == null) {
            return null;
        }

        return info.getDomain();
    }

    /**
     * 根据服务id获取服务元信息。
     * @param aliasType 名字分类。
     * @param aliasName 别名。
     */
    public RsfBindInfo<?> getRsfBindInfo(String aliasType, String aliasName) {
        Map<String, String> aliasNameMaps = this.aliasNameMap.get(aliasType);
        if (aliasNameMaps == null) {
            return null;
        }

        String serviceID = aliasNameMaps.get(aliasName);
        if (serviceID == null) {
            return null;
        }

        return this.serviceMap.get(serviceID);
    }

    /**
     * 根据类型获取服务元信息。如果类型上配置了{@link RsfService @RsfService}注解，则使用该注解的配置信息。
     * 否则将使用RSF默认配置下的Group、Version。
     * @param serviceType 服务类型。
     */
    public <T> RsfBindInfo<T> getRsfBindInfo(Class<T> serviceType) {
        String serviceGroup = this.settings.getDefaultGroup();
        String serviceName = serviceType.getName();
        String serviceVersion = this.settings.getDefaultVersion();

        //覆盖
        RsfService serviceInfo = serviceType.getAnnotation(RsfService.class);
        if (serviceInfo != null) {
            if (!StringUtils.isBlank(serviceInfo.group())) {
                serviceGroup = serviceInfo.group();
            }
            if (!StringUtils.isBlank(serviceInfo.name())) {
                serviceName = serviceInfo.name();
            }
            if (!StringUtils.isBlank(serviceInfo.version())) {
                serviceVersion = serviceInfo.version();
            }
        }

        return (RsfBindInfo<T>) this.getRsfBindInfo(serviceGroup, serviceName, serviceVersion);
    }

    /**
     * 根据服务坐标获取服务元信息。
     * @param group 组别
     * @param name 服务名
     * @param version 服务版本
     */
    public RsfBindInfo<?> getRsfBindInfo(String group, String name, String version) {
        String serviceID = "[" + group + "]" + name + "-" + version;//String.format("[%s]%s-%s", group, name, version);
        return this.getRsfBindInfo(serviceID);
    }

    /**获取所有已经注册的服务名称。*/
    public List<String> getServiceIDs() {
        return new ArrayList<>(this.serviceMap.keySet());
    }

    /**根据别名系统获取所有已经注册的服务名称。*/
    public List<String> getServiceIDs(String category) {
        ConcurrentMap<String, String> aliasNameMaps = this.aliasNameMap.get(category);
        if (aliasNameMaps == null) {
            return Collections.EMPTY_LIST;
        } else {
            return new ArrayList<>(aliasNameMaps.keySet());
        }
    }

    /**获取配置对象。*/
    public RsfSettings getSettings() {
        return this.settings;
    }

    /* ----------------------------------------------------------------------------------------- */

    /**创建{@link RsfPublisher}。*/
    public RsfPublisher createPublisher() {
        return new RsfBindBuilder(this);
    }

    /**
     * 添加一个全局服务过滤器。
     * @param define 过滤器对象。
     */
    protected final void publishFilter(FilterDefine define) {
        String filterID = Objects.requireNonNull(define.filterID());
        synchronized (this.filterLock) {
            for (FilterDefine filter : this.filterList) {
                if (filterID.equals(filter.filterID())) {
                    throw new IllegalStateException("duplicate filterID :" + filterID);
                }
            }

            this.filterList.add(define);
            this.filterCache.clear();
        }
    }

    /**
     * 发布服务
     * @param serviceDefine 服务定义。
     */
    protected final synchronized <T> void publishService(ServiceDefine<T> serviceDefine) {
        String serviceID = serviceDefine.getDomain().getBindID();
        if (this.serviceMap.containsKey(serviceID)) {
            String serviceType = this.serviceMap.get(serviceID).getDomain().getServiceType().name();
            String logMessage = "a " + serviceType + " of the same name already exists , serviceID -> " + serviceID;
            this.logger.error(logMessage);
            throw new IllegalStateException(logMessage);
        }

        if (RsfServiceType.Provider == serviceDefine.getServiceType() && serviceDefine.getCustomerProvider() == null) {
            throw new RsfException(ProtocolStatus.Forbidden, "Provider Not set the implementation class.");
        }

        this.logger.info("service to public, id= {}", serviceID);
        this.serviceMap.put(serviceID, serviceDefine);

        // .收录别名
        Set<String> aliasTypes = serviceDefine.getAliasTypes();
        for (String aliasType : aliasTypes) {
            ConcurrentMap<String, String> aliasMap = this.aliasNameMap.get(aliasType);
            if (aliasMap == null) {
                aliasMap = new ConcurrentHashMap<>();
                this.aliasNameMap.putIfAbsent(aliasType, aliasMap);
            }
            String aliasName = serviceDefine.getAliasName(aliasType);
            if (StringUtils.isBlank(aliasName)) {
                continue;
            }
            aliasMap.putIfAbsent(aliasName, serviceID);
        }

        // .追加地址
        this.addressPool.appendStaticAddress(serviceID, serviceDefine.getAddressSet());

        // .更新流控
        String flowControl = serviceDefine.getFlowControl();
        if (StringUtils.isNotBlank(flowControl)) {
            this.addressPool.updateFlowControl(serviceID, flowControl);
        }

        // .更新路由
        Map<RouteTypeEnum, String> scriptMap = serviceDefine.getRouteScript();
        if (scriptMap != null && !scriptMap.isEmpty()) {
            for (Map.Entry<RouteTypeEnum, String> routeEnt : scriptMap.entrySet()) {
                this.addressPool.updateRoute(serviceID, routeEnt.getKey(), routeEnt.getValue());
            }
        }
    }

    /**
     * 回收发布的服务
     * @param serviceID 服务定义。
     */
    public synchronized boolean recoverService(String serviceID) {
        if (this.serviceMap.containsKey(serviceID)) {
            // .回收服务
            synchronized (this.filterLock) {
                this.serviceMap.remove(serviceID);
                this.filterCache.remove(serviceID);
            }

            for (Map.Entry<String, ConcurrentMap<String, String>> aliasEntry : this.aliasNameMap.entrySet()) {
                ConcurrentMap<String, String> aliasSet = aliasEntry.getValue();
                ArrayList<String> toRemove = new ArrayList<>();
                for (Map.Entry<String, String> entry : aliasSet.entrySet()) {
                    if (serviceID.equals(entry.getValue())) {
                        toRemove.add(entry.getKey());
                    }
                }

                for (String key : toRemove) {
                    aliasSet.remove(key);
                }
            }

            this.addressPool.removeBucket(serviceID);
            return true;
        }
        return false;
    }
}
