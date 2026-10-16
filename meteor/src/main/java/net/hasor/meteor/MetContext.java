/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.serialize.SerializeCoder;

/**
 * RSF 环境。
 * @version : 2014年11月18日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetContext extends OnlineStatus, AutoCloseable {
    void start();

    /** 订阅本地生命周期与服务事件；同步回调应快速返回，关闭后释放订阅，取消不打断正在执行的回调。 */
    Cancellable subscribe(MetContextListener listener);

    /** 获取扩展运行对象或其提供的服务；未提供返回 null，存在多个匹配扩展时抛出异常。 */
    <T> T getExtension(Class<T> type);

    /** 获取实例ID，实例ID在应用每次启动时生成一个全新的ID。 */
    String getInstanceID();

    /** 获取所属应用名称，默认 application。 */
    String getApplicationName();

    /** @return 发起远程调用的客户端接口*/
    MetClient getRsfClient();

    /** @return 发起远程调用的客户端接口*/
    MetClient getRsfClient(String targetStr) throws URISyntaxException, UnknownHostException;

    /** @return 发起远程调用的客户端接口*/
    MetClient getRsfClient(URI targetURL) throws UnknownHostException;

    /** @return 发起远程调用的客户端接口*/
    MetClient getRsfClient(InterAddress target);

    /**根据服务名获取服务描述。*/
    <T> MetBindInfo<T> getServiceInfo(String serviceID);

    /**根据别名系统来查找服务。*/
    <T> MetBindInfo<T> getServiceInfo(String aliasType, String aliasName);

    /**根据服务名获取服务描述。*/
    <T> MetBindInfo<T> getServiceInfo(Class<T> serviceType);

    /**根据服务名获取服务描述。*/
    <T> MetBindInfo<T> getServiceInfo(String group, String name, String version);

    /**获取已经注册的所有服务名称。*/
    List<String> getServiceIDs();

    /**根据别名系统来获取该别名系统下所有服务ID。*/
    List<String> getServiceIDs(String aliasType);

    /**
     * 获取元信息所描述的服务对象
     * @param bindInfo 元信息所描述对象
     */
    <T> Supplier<T> getServiceProvider(MetBindInfo<T> bindInfo);

    /**获取运行着的协议*/
    Set<String> runProtocols();

    /**获取默认协议*/
    String getDefaultProtocol();

    /** 获取逻辑协议唯一的当前监听地址；匹配多个地址时抛出 IllegalArgumentException。 */
    InterAddress getListenAddress(String protocol);

    /** 获取逻辑协议对应的全部当前监听地址。 */
    List<InterAddress> getListenAddresses(String protocol);

    /** 获取逻辑协议在指定公开地址 scheme 下的唯一当前监听地址。 */
    InterAddress getListenAddress(String protocol, String addressScheme);

    /** 获取指定连接器、逻辑协议和公开地址 scheme 对应的当前监听地址。 */
    InterAddress getListenAddress(String connectorName, String protocol, String addressScheme);

    /** 获取本上下文独立的只读配置快照。 */
    MetSettings getSettings();

    /**获取当前实例的序列化器，未注册的名称返回 null。*/
    SerializeCoder getSerializeCoder(String name);

    /**获取地址路由更新接口。*/
    MetUpdater getUpdater();

    /**获取类加载器。*/
    ClassLoader getClassLoader();

    /**创建{@link MetPublisher}。*/
    MetPublisher publisher();

    /**应用上线并恢复入站监听。*/
    void online();

    /**停止接受新入站连接并开始关闭已接入连接；不等待所有连接或业务执行结束。*/
    void offline();
}
