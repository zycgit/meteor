/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.rpc;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.meteor.MetSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 业务线程
 * @version : 2014年11月11日
 * @author 赵永春 (zyc@hasor.net)
 */
final class MetDispatcherExecutor {
    private final Logger                          logger = LoggerFactory.getLogger(getClass());
    private final Supplier<ThreadPoolExecutor>    defaultExecutorProvider;
    private final ThreadPoolExecutor              defaultExecutor;
    private final Map<String, ThreadPoolExecutor> servicePoolCache;

    public MetDispatcherExecutor(MetSettings settings, ClassLoader loader) {
        final int minCorePoolSize = settings.getQueueMinPoolSize();
        final int maxCorePoolSize = settings.getQueueMaxPoolSize();
        final int queueSize = settings.getQueueMaxSize();
        final long keepAliveTime = settings.getQueueKeepAliveTime();
        this.logger.info("dispatcherExecutor init ->> minCorePoolSize ={}, maxCorePoolSize ={}, queueSize ={}, keepAliveTime ={}",//
                minCorePoolSize, maxCorePoolSize, queueSize, keepAliveTime);

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
