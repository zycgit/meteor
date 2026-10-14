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
import net.hasor.cobble.concurrent.ThreadUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEventType;
import net.hasor.meteor.MetSettings;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;

/**
 * 业务线程
 * @version : 2014年11月11日
 * @author 赵永春 (zyc@hasor.net)
 */
final class MetDispatcherExecutor {
    private final    MetContext                              context;
    private final    ThreadPoolExecutor                      defaultExecutor;
    private final    Map<MetBindInfo<?>, ThreadPoolExecutor> serviceExecutors = new ConcurrentHashMap<>();
    private final    Object                                  lifecycleLock    = new Object();
    private final    Cancellable                             subscription;
    private volatile boolean                                 closed;
    private          int                                     poolSequence;

    MetDispatcherExecutor(MetContext context) {
        this.context = context;
        this.defaultExecutor = this.createExecutor("RSF-Biz-%s");
        this.subscription = context.subscribe(event -> {
            if (event.getType() == MetContextEventType.REMOVED) {
                this.remove(event.getServiceInfo());
            }
        });
    }

    private ThreadPoolExecutor createExecutor(String threadName) {
        MetSettings settings = this.context.getSettings();
        int minPoolSize = settings.getQueueMinPoolSize();
        int maxPoolSize = settings.getQueueMaxPoolSize();
        long keepAliveTime = settings.getQueueKeepAliveTime();
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(settings.getQueueMaxSize());
        var threadFactory = ThreadUtils.threadFactory(this.context.getClassLoader(), threadName, false);
        return new ThreadPoolExecutor(minPoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS, //
                workQueue, threadFactory, new ThreadPoolExecutor.AbortPolicy());
    }

    void execute(MetBindInfo<?> service, MetInvocationTask task) {
        if (this.closed) {
            throw new RejectedExecutionException("Meteor dispatcher is closed");
        }

        // 未指定独立池的服务共用默认池，未知服务仍交给调用任务返回 NotFound。
        ThreadPoolExecutor executor = this.defaultExecutor;
        if (service != null && service.getServiceType() == MetServiceType.Provider && !service.isSharedThreadPool()) {
            executor = this.serviceExecutor(service);
        }
        executor.execute(task);
    }

    private ThreadPoolExecutor serviceExecutor(MetBindInfo<?> service) {
        ThreadPoolExecutor executor = this.serviceExecutors.get(service);
        if (executor != null) {
            return executor;
        }

        // 首次调用才分配独立队列；正常请求不占用生命周期锁。
        synchronized (this.lifecycleLock) {
            if (this.closed || this.context.getServiceInfo(service.getBindID()) != service) {
                throw new RejectedExecutionException("Service is no longer available: " + service.getBindID());
            }
            executor = this.serviceExecutors.get(service);
            if (executor == null) {
                executor = this.createExecutor("RSF-Biz-Service-" + ++this.poolSequence + "-%s");
                this.serviceExecutors.put(service, executor);
            }
        }
        return executor;
    }

    private void remove(MetBindInfo<?> service) {
        ThreadPoolExecutor executor;
        synchronized (this.lifecycleLock) {
            executor = this.serviceExecutors.remove(service);
        }
        if (executor != null) {
            List<Runnable> queued = executor.shutdownNow();
            for (Runnable task : queued) {
                MetInvocationTask invocation = (MetInvocationTask) task;
                invocation.reject(ProtocolStatus.NotFound, "Service is no longer available: " + service.getBindID());
            }
        }
    }

    /**停止应用服务。*/
    void shutdown() {
        List<ThreadPoolExecutor> executors;
        synchronized (this.lifecycleLock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            executors = new ArrayList<>(this.serviceExecutors.values());
            this.serviceExecutors.clear();
        }

        // 回收线程时不持锁；晚到的请求由关闭标记或线程池的拒绝策略处理。
        this.subscription.cancel();
        this.defaultExecutor.shutdownNow();
        for (ThreadPoolExecutor executor : executors) {
            executor.shutdownNow();
        }
    }
}
