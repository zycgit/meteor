/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.route.ArgsKey;
import net.hasor.meteor.connector.ConnectorConfig;

/**
 * RSF 配置。
 * @version : 2014年11月18日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetSettings {
    /**获取本地数据目录。*/
    Path getDataHome();

    /**获取默认超时时间。*/
    int getDefaultTimeout();

    /**获取默认超时时间。*/
    String getDefaultGroup();

    /**获取默认超时时间。*/
    String getDefaultVersion();

    /**获取服务默认使用的序列化类型。*/
    String getSerializeType();

    /**获取默认附加到响应中的选项。*/
    MetOptionSet getResponseOptions();

    /**获取默认附加到请求中的选项。*/
    MetOptionSet getRequestOptions();

    /**处理任务队列的最大大小，作为服务端当队列满了之后所有新进来的请求都会被回应 ChooseOther*/
    int getQueueMaxSize();

    /**the number of threads to keep in the pool, even if they are idle, unless allowCoreThreadTimeOut is set.*/
    int getQueueMinPoolSize();

    /**the maximum number of threads to allow in the pool.*/
    int getQueueMaxPoolSize();

    /**(SECONDS),when the number of threads is greater than the core, this is the maximum time that excess idle threads will wait for new tasks before terminating.*/
    long getQueueKeepAliveTime();

    /**客户端请求超时时间*/
    int getRequestTimeout();

    /**最大并发请求数*/
    int getMaximumRequest();

    /** 并发调用请求限制策略，当并发调用达到限制值后的策略（Reject 抛出异常，WaitSecond 最多等待1秒获取空闲并发名额）*/
    SendLimitPolicy getSendLimitPolicy();

    /**客户端发起一个连接请求所允许的最大耗时（单位毫秒）*/
    int getConnectTimeout();

    /**获取本地服务绑定地址*/
    String getBindAddress();

    /**获取默认传输协议*/
    String getDefaultProtocol();

    /**可使用的协议名集合*/
    Set<String> getProtocols();

    /**获取本地服务绑定地址*/
    InterAddress getBindAddressSet(String protocolName);

    /**获取已解析的连接器配置及其专属选项。*/
    Collection<ConnectorConfig> getConnectorConfigs();

    /**获取地址参数路由键的实现类。*/
    Class<? extends ArgsKey> getArgsKeyClass();

    /**获取本机所属单元*/
    String getUnitName();

    /**获取地址失效之后，等待重新尝试连接的时间(毫秒)。默认60秒。*/
    long getInvalidWaitTime();

    /**自动刷新地址本缓存的时间，默认6分钟。*/
    long getRefreshCacheTime();

    /**每次缓存地址本到磁盘时的时间间隔（单位:毫秒）默认:1小时*/
    long getDiskCacheTimeInterval();

    /**启用磁盘地址本缓存，在refreshCacheTime期间每隔1小时自动写入一次。（被回收的服务不享受此待遇）*/
    boolean isLocalDiskCache();

    /**应用自动上线*/
    boolean isAutomaticOnline();

}
