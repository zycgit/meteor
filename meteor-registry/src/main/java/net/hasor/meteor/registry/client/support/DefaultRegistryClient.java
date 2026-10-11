/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEvent;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.shared.rpc.ConfigurationData;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.EntInstanceConfigurationData;

/** 协调本地运行服务与注册服务的生命周期、订阅及推拉流程。 */
public final class DefaultRegistryClient implements RegistryClient, AutoCloseable {
    private static final Logger                         LOGGER         = LoggerFactory.getLogger(DefaultRegistryClient.class);
    private final        MetContext                     context;
    private final        ManagementRPCImpl              rpcService;
    private final        long                           refreshMillis;
    private final        Callable<StorageAccess>        storageOpener;
    private final        List<Cancellable>              watches        = new ArrayList<>();
    private final        Map<String, List<Cancellable>> serviceWatches = new HashMap<>();
    private final        AtomicBoolean                  queued         = new AtomicBoolean();
    private volatile     ScheduledThreadPoolExecutor    worker;
    private volatile     boolean                        running;
    private              StorageAccess                  storage;
    private              RegistrationService            registrationService;

    /** 创建阶段只注册本地组件，存储连接留到 beforeStart。 */
    public DefaultRegistryClient(MetContext context, Callable<StorageAccess> storageOpener, long refreshMillis, String publishHost) {
        this.context = context;
        this.storageOpener = storageOpener;
        this.refreshMillis = refreshMillis;
        this.rpcService = new ManagementRPCImpl(context, publishHost);
    }

    public synchronized void beforeStart() throws Exception {
        this.storage = this.storageOpener.call();
        this.registrationService = new RegistrationService(this.storage);
        this.worker = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "Meteor-registry-client-" + this.context.getInstanceID());
            thread.setContextClassLoader(this.context.getClassLoader());
            thread.setDaemon(true);
            return thread;
        });
        this.worker.setRemoveOnCancelPolicy(true);

        // 与普通订阅者使用同一事件入口；回调只投递任务，不在容器线程访问存储。
        this.watches.add(this.context.subscribe(this::onContextEvent));

        // 监听开启前先应用治理；此时不能登记尚未就绪的地址。
        String instanceId = this.context.getInstanceID();
        this.watches.addAll(this.registrationService.watchInstance(instanceId, this::requestRefresh));
        this.pullConfiguration(null, false);
    }

    public synchronized void afterStart() throws Exception {
        // 首次发布同步完成，不额外投递一次全量拉取干扰启动后的主动操作。
        this.reconcile();

        // 订阅处理后续变更，周期核对补偿丢失通知和临时记录。
        this.worker.scheduleWithFixedDelay(this::requestRefresh, this.refreshMillis, this.refreshMillis, TimeUnit.MILLISECONDS);
        this.running = true;
    }

    private void onContextEvent(MetContextEvent event) {
        switch (event.getType()) {
            case REGISTERED, REMOVED, ONLINE, OFFLINE -> this.requestRefresh();
            default -> {
                // 首次发布和资源释放仍由扩展生命周期负责。
            }
        }
    }

    private void requestRefresh() {
        if (!this.running || !this.queued.compareAndSet(false, true)) {
            return;
        }

        try {
            this.worker.execute(() -> {
                this.queued.set(false);
                this.refresh();
            });
        } catch (RejectedExecutionException failure) {
            this.queued.set(false);
            if (this.running) {
                LOGGER.warn("Registry refresh rejected", failure);
            }
        }
    }

    private synchronized void refresh() {
        if (!this.running) {
            return;
        }

        try {
            this.reconcile();
        } catch (Exception failure) {
            LOGGER.warn("Registry synchronization failed; retaining last known addresses and configuration", failure);
        }
    }

    private void watchServices(List<String> ids) {
        for (String id : ids) {
            if (!this.serviceWatches.containsKey(id)) {
                List<Cancellable> watches = this.registrationService.watchService(this.context.getInstanceID(), id, this::requestRefresh);
                this.serviceWatches.put(id, watches);
            }
        }

        Iterator<Map.Entry<String, List<Cancellable>>> iterator = this.serviceWatches.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, List<Cancellable>> entry = iterator.next();
            if (!ids.contains(entry.getKey())) {
                entry.getValue().forEach(Cancellable::cancel);
                iterator.remove();
            }
        }
    }

    @Override
    public Future<Void> pushSync() {
        return this.submitSync(false, null);
    }

    @Override
    public Future<Void> pullSync() {
        return this.submitSync(true, null);
    }

    @Override
    public Future<Void> pushSync(String serviceId) {
        this.requireServiceId(serviceId);
        return this.submitSync(false, serviceId);
    }

    @Override
    public Future<Void> pullSync(String serviceId) {
        this.requireServiceId(serviceId);
        return this.submitSync(true, serviceId);
    }

    private void requireServiceId(String serviceId) {
        if (StringUtils.isBlank(serviceId)) {
            throw new IllegalArgumentException("Service ID must not be blank");
        }
    }

    private Future<Void> submitSync(boolean pull, String serviceId) {
        // 投递不获取同步任务的锁，存储 I/O 不能阻塞异步 API 的返回。
        BasicFuture<Void> result = new BasicFuture<>();
        try {
            if (!this.running) {
                throw new IllegalStateException("Registry client is not running");
            }

            // 与自动同步共用串行队列，完成同步后再通知调用方。
            this.worker.execute(() -> {
                try {
                    this.syncLocal(pull, serviceId);
                    result.completed(null);
                } catch (Exception failure) {
                    result.failed(failure);
                }
            });
        } catch (Exception failure) {
            result.failed(failure);
        }
        return result;
    }

    private synchronized void syncLocal(boolean pull, String serviceId) throws Exception {
        if (!this.running) {
            throw new IllegalStateException("Registry client is not running");
        }

        if (pull) {
            this.reconcile(serviceId, true);
            return;
        }

        // 推送仅采集和上报本地信息，不读取或应用注册中心治理配置。
        RegistrationSnapshot record = this.rpcService.snapshot(serviceId);
        this.registrationService.publish(record, serviceId);
    }

    private void reconcile() throws Exception {
        this.reconcile(null, false);
    }

    private void reconcile(String serviceId, boolean force) throws Exception {
        try {
            this.pullConfiguration(serviceId, force);
        } catch (Exception failure) {
            // 读取或应用失败仍报告实际状态，但不能吞掉主动调用的原始异常。
            try {
                this.registrationService.publish(this.rpcService.snapshot(serviceId), serviceId);
            } catch (Exception report) {
                failure.addSuppressed(report);
            }
            throw failure;
        }
        this.registrationService.publish(this.rpcService.snapshot(serviceId), serviceId);
    }

    private void pullConfiguration(String serviceId, boolean force) throws Exception {
        String instanceId = this.context.getInstanceID();
        List<String> allServices = this.rpcService.serviceIds();
        this.watchServices(allServices);
        if (serviceId != null && !allServices.contains(serviceId)) {
            throw new IllegalArgumentException("Service is not registered on this instance: " + serviceId);
        }

        List<Exception> failures = new ArrayList<>();
        if (serviceId == null) {
            EntInstanceConfigurationData settings = this.registrationService.instanceConfiguration(instanceId);
            Exception failure = this.rpcService.applyInstance(settings);
            if (failure != null) {
                failures.add(failure);
            }
        }

        List<String> selected = serviceId == null ? allServices : List.of(serviceId);
        if (!selected.isEmpty()) {
            ConfigurationData defaults = this.registrationService.defaults();
            ConfigurationData machine = this.registrationService.instanceRules(instanceId);
            for (String id : selected) {
                ConfigurationData service = this.registrationService.serviceConfiguration(id);
                ConfigurationData local = this.registrationService.serviceOverride(id, instanceId);
                Exception failure = this.rpcService.apply(id, defaults, service, machine, local);
                if (failure != null) {
                    failures.add(failure);
                }
            }
        }

        if (!failures.isEmpty()) {
            Exception failure = failures.get(0);
            for (int i = 1; i < failures.size(); i++) {
                failure.addSuppressed(failures.get(i));
            }
            throw failure;
        }
        for (String id : selected) {
            this.rpcService.updateAddresses(id, this.registrationService.providers(id), force);
        }
    }

    /** 释放订阅、任务和存储会话，管理 RPC 和过滤器由整个上下文持有。 */
    @Override
    public synchronized void close() throws Exception {
        this.running = false;

        // 每个资源均尝试释放，某个订阅失败不能阻止存储会话关闭。
        List<AutoCloseable> resources = new ArrayList<>();
        for (Cancellable watch : this.watches) {
            resources.add(watch::cancel);
        }

        for (List<Cancellable> subscriptions : this.serviceWatches.values()) {
            for (Cancellable subscription : subscriptions) {
                resources.add(subscription::cancel);
            }
        }
        if (this.worker != null) {
            resources.add(this.worker::shutdown);
        }
        if (this.storage != null) {
            resources.add(this.storage);
        }

        Exception failure = null;
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (Exception cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }

        this.watches.clear();
        this.serviceWatches.clear();
        this.storage = null;
        this.registrationService = null;
        if (failure != null) {
            throw failure;
        }
    }
}
