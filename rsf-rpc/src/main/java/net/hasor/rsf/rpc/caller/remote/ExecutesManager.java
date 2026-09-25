/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.rpc.caller.remote;
import net.hasor.cobble.concurrent.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * 业务线程
 * @version : 2014年11月11日
 * @author 赵永春 (zyc@hasor.net)
 */
public class ExecutesManager {
    protected     Logger                                    logger = LoggerFactory.getLogger(getClass());
    private       Supplier<ThreadPoolExecutor>              defaultExecutorProvider;
    private       ThreadPoolExecutor                        defaultExecutor;
    private final ConcurrentMap<String, ThreadPoolExecutor> servicePoolCache;

    public ExecutesManager(final int minCorePoolSize, final int maxCorePoolSize, final int queueSize, final long keepAliveTime, final ClassLoader loader) {
        this.logger.info("executesManager init ->> minCorePoolSize ={}, maxCorePoolSize ={}, queueSize ={}, keepAliveTime ={}",//
                minCorePoolSize, maxCorePoolSize, queueSize, keepAliveTime);
        //
        final BlockingQueue<Runnable> inWorkQueue = new LinkedBlockingQueue<>(queueSize);
        this.defaultExecutorProvider = () -> new ThreadPoolExecutor(//
                minCorePoolSize,    //
                maxCorePoolSize,    //
                keepAliveTime,      //
                TimeUnit.SECONDS,   //
                inWorkQueue,        //
                ThreadUtils.threadFactory(loader, "RSF-Biz-%s", false),//
                new ThreadPoolExecutor.AbortPolicy()//
        );
        this.defaultExecutor = this.defaultExecutorProvider.get();
        this.servicePoolCache = new ConcurrentHashMap<>();
    }

    public Executor getExecute(String serviceUniqueName) {
        if (!this.servicePoolCache.isEmpty() && serviceUniqueName != null) {
            ThreadPoolExecutor executor = this.servicePoolCache.get(serviceUniqueName);
            if (executor != null) {
                return executor;
            }
        }
        return this.defaultExecutor;
    }

    /**停止应用服务。*/
    public void shutdown() {
        List<ThreadPoolExecutor> executors = new ArrayList<>(this.servicePoolCache.values());
        executors.add(this.defaultExecutor);
        this.servicePoolCache.clear();
        for (ThreadPoolExecutor executor : executors) {
            executor.shutdownNow();
        }
    }
}
