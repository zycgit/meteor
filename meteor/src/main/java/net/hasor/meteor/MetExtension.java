/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;

/** 上下文独享的扩展实例；事件通过 MetContext.subscribe 订阅，不支持独立卸载或控制所属上下文的启动关闭。 */
public interface MetExtension extends AutoCloseable {
    /** 获取扩展提供的服务；不支持该类型时返回 null，查询本身不创建运行资源。 */
    <T> T getService(Class<T> type);

    /** 开启网络监听前执行，失败将终止上下文启动。 */
    void beforeStart() throws Exception;

    /** 网络监听就绪后执行，失败将关闭上下文及已经打开的监听。 */
    void afterStart() throws Exception;

    /** 释放扩展资源；即使启动未完成也会调用，不能关闭所属上下文。 */
    @Override
    void close() throws Exception;
}
