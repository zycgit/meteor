/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.hasor.rsf.address.InterAddress;

/**
 * RSF 环境。
 * @version : 2014年11月18日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface RsfContext extends OnlineStatus, AutoCloseable {
    void start();

    void close();

    /** 获取实例ID，实例ID在应用每次启动时生成一个全新的ID。 */
    String getInstanceID();

    /** @return 发起远程调用的客户端接口*/
    RsfClient getRsfClient();

    /** @return 发起远程调用的客户端接口*/
    RsfClient getRsfClient(String targetStr) throws URISyntaxException, UnknownHostException;

    /** @return 发起远程调用的客户端接口*/
    RsfClient getRsfClient(URI targetURL) throws UnknownHostException;

    /** @return 发起远程调用的客户端接口*/
    RsfClient getRsfClient(InterAddress target);

    /**根据服务名获取服务描述。*/
    <T> RsfBindInfo<T> getServiceInfo(String serviceID);

    /**根据别名系统来查找服务。*/
    <T> RsfBindInfo<T> getServiceInfo(String aliasType, String aliasName);

    /**根据服务名获取服务描述。*/
    <T> RsfBindInfo<T> getServiceInfo(Class<T> serviceType);

    /**根据服务名获取服务描述。*/
    <T> RsfBindInfo<T> getServiceInfo(String group, String name, String version);

    /**获取已经注册的所有服务名称。*/
    List<String> getServiceIDs();

    /**根据别名系统来获取该别名系统下所有服务ID。*/
    List<String> getServiceIDs(String aliasType);

    /**
     * 获取元信息所描述的服务对象
     * @param bindInfo 元信息所描述对象
     */
    <T> Supplier<T> getServiceProvider(RsfBindInfo<T> bindInfo);

    /**获取运行着的协议*/
    Set<String> runProtocols();

    /**获取默认协议*/
    String getDefaultProtocol();

    /** 获取RSF运行的地址。 */
    InterAddress bindAddress(String protocol);

    /**获取RSF配置*/
    RsfSettings getSettings();

    /**获取IoC容器*/

    /**获取{@link RsfEnvironment}*/
    RsfEnvironment getEnvironment();

    /**获取地址路由更新接口。*/
    RsfUpdater getUpdater();

    /**获取类加载器。*/
    ClassLoader getClassLoader();

    /**创建{@link RsfPublisher}。*/
    RsfPublisher publisher();

    /**应用上线（优雅上线）*/
    void online();

    /**应用下线（优雅停机）*/
    void offline();
}