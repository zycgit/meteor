/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.dubbo;
import java.util.*;
import java.util.concurrent.*;
import net.hasor.cobble.concurrent.future.BasicFuture;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.concurrent.future.Future;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetContextEvent;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.domain.MetServiceType;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.registry.NotifyListener;

/** 协调本机声明、原生注册订阅与 Meteor 地址本；业务调用仍由 Meteor 处理。 */
final class DubboRegistryService implements DubboRegistryClient, AutoCloseable {
    private static final Logger                      LOGGER        = LoggerFactory.getLogger(DubboRegistryService.class);
    private final        MetContext                  context;
    private final        URL                         registryUrl;
    private final        String                      publishHost;
    private final        String                      secureScheme;
    private final        int                         timeoutMillis;
    private final        long                        refreshMillis;
    private final        Map<String, Set<URL>>       published     = new LinkedHashMap<>();
    private final        Map<String, URL>            subscriptions = new LinkedHashMap<>();
    private final        Map<String, NotifyListener> listeners     = new HashMap<>();
    private volatile     boolean                     running;
    private              boolean                     instanceUpdatePending;
    private              DubboRegistrySession        session;
    private              ScheduledExecutorService    worker;
    private              Cancellable                 events;

    DubboRegistryService(MetContext context, URL registryUrl, //
            String publishHost, String secureScheme, int timeoutMillis, long refreshMillis) {
        this.context = context;
        this.registryUrl = registryUrl;
        this.publishHost = publishHost;
        this.secureScheme = secureScheme;
        this.timeoutMillis = timeoutMillis;
        this.refreshMillis = refreshMillis;
    }

    synchronized void open() {
        // 独占 SDK 模型只负责原生注册服务，不创建 Dubbo 业务代理或业务监听。
        this.session = new DubboRegistrySession(this.context, this.registryUrl);
        try {
            this.session.open();
            this.worker = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "Meteor-dubbo-registry-" + this.context.getInstanceID());
                thread.setContextClassLoader(this.context.getClassLoader());
                thread.setDaemon(true);
                return thread;
            });
            this.events = this.context.subscribe(this::onContextEvent);
        } catch (RuntimeException | Error failure) {
            try {
                this.close();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    void start() {
        this.running = true;
        this.awaitRefresh();
        this.worker.scheduleWithFixedDelay(this::periodicRefresh, //
                this.refreshMillis, this.refreshMillis, TimeUnit.MILLISECONDS);
    }

    private void periodicRefresh() {
        // 与事件及首次引用共用队列，捕获没有独立事件的服务元信息变更。
        if (this.running) {
            try {
                this.reconcile();
            } catch (RuntimeException failure) {
                LOGGER.warn("Native Dubbo registry reconciliation failed", failure);
            }
        }
    }

    private void onContextEvent(MetContextEvent event) {
        switch (event.getType()) {
            case REGISTERED, REMOVED, ONLINE, OFFLINE -> this.refresh();
            default -> {
                // 启动和关闭由扩展生命周期处理，不在事件回调里管理上下文。
            }
        }
    }

    @Override
    public <T> T reference(Class<T> serviceType) {
        this.requireRunning();
        // 原生 Dubbo 的未分组、未版本化服务使用独立绑定，不改变 Meteor 的本地默认身份。
        String name = serviceType.getName() + ".DubboNativeDefault";
        String group = this.context.getSettings().getDefaultGroup();
        String version = this.context.getSettings().getDefaultVersion();
        MetBindInfo<T> service = this.context.getServiceInfo(group, name, version);
        if (service == null) {
            service = this.context.publisher().rsfService(serviceType).name(name) //
                    .aliasName("dubbo", serviceType.getName()).protocol("dubbo") //
                    .asDistributedCall().register();
            service.setMetaData("dubbo.registry.group", "");
            service.setMetaData("dubbo.registry.version", "");
        }
        return this.reference(service);
    }

    @Override
    public <T> T reference(MetBindInfo<T> service) {
        this.requireRunning();
        boolean selectedDubbo = !DubboServiceUrls.selectedSchemes(this.context, service).isEmpty();
        if (service.getServiceType() != MetServiceType.Consumer || !selectedDubbo) {
            throw new IllegalArgumentException("Dubbo reference requires a consumer bound to the dubbo protocol");
        }
        if (this.context.getServiceInfo(service.getBindID()) == null) {
            throw new IllegalArgumentException("Dubbo consumer is not registered on this Meteor context");
        }
        this.awaitRefresh();
        Map<String, String> options = new HashMap<>();
        options.put("dubbo.group", DubboServiceUrls.nativeGroup(service));
        options.put("dubbo.version", DubboServiceUrls.nativeVersion(service));
        return this.context.getRsfClient().withOptions(options).getRemote(service);
    }

    private void requireRunning() {
        if (!this.running) {
            throw new IllegalStateException("Dubbo registry client is not running");
        }
    }

    private void awaitRefresh() {
        try {
            this.refresh().get(this.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while preparing Dubbo registration", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Unable to prepare Dubbo registration", failure);
        }
    }

    private Future<Void> refresh() {
        BasicFuture<Void> result = new BasicFuture<>();
        ScheduledExecutorService worker = this.worker;
        if (!this.running || worker == null) {
            result.failed(new IllegalStateException("Dubbo registry client is not running"));
            return result;
        }
        try {
            worker.execute(() -> {
                try {
                    this.reconcile();
                    result.completed(null);
                } catch (RuntimeException failure) {
                    LOGGER.warn("Native Dubbo registry synchronization failed", failure);
                    result.failed(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            result.failed(failure);
        }
        return result;
    }

    @Override
    public boolean isAvailable() {
        DubboRegistrySession current = this.session;
        return this.running && current != null && current.isAvailable();
    }

    private void reconcile() {
        if (!this.running) {
            throw new IllegalStateException("Dubbo registry client is not running");
        }

        // 按当前服务快照登记提供者、消费者；未监听或离线时不发布提供者地址。
        Set<String> providers = new HashSet<>();
        Set<String> consumers = new HashSet<>();
        List<InterAddress> addresses = this.context.getListenAddresses("dubbo");
        for (String serviceId : this.context.getServiceIDs()) {
            MetBindInfo<?> service = this.context.getServiceInfo(serviceId);
            if (service == null || service.isShadow()) {
                continue;
            }
            Set<String> schemes = DubboServiceUrls.selectedSchemes(this.context, service);
            if (schemes.isEmpty()) {
                continue;
            }
            if (service.getServiceType() == MetServiceType.Provider) {
                providers.add(serviceId);
                Set<URL> providerUrls = new LinkedHashSet<>();
                if (this.context.isOnline()) {
                    for (InterAddress address : addresses) {
                        if (!schemes.contains(address.getSchema())) {
                            continue;
                        }
                        URL provider = DubboServiceUrls.provider(this.context, service, address, //
                                this.publishHost, this.secureScheme);
                        providerUrls.add(provider);
                    }
                }
                this.publish(serviceId, providerUrls);
            } else {
                consumers.add(serviceId);
                this.subscribe(service);
            }
        }

        // 回收已移除或切换为隐藏模式的声明，清理本扩展维护的动态地址。
        for (String serviceId : new ArrayList<>(this.published.keySet())) {
            if (!providers.contains(serviceId)) {
                this.publish(serviceId, Set.of());
                this.published.remove(serviceId);
            }
        }
        for (String serviceId : new ArrayList<>(this.subscriptions.keySet())) {
            if (!consumers.contains(serviceId)) {
                this.unsubscribe(serviceId);
            }
        }
        if (this.instanceUpdatePending) {
            this.session.updateInstance();
            this.instanceUpdatePending = false;
        }
    }

    private void publish(String serviceId, Set<URL> providers) {
        Set<URL> previous = this.published.computeIfAbsent(serviceId, ignored -> new LinkedHashSet<>());
        for (URL url : new ArrayList<>(previous)) {
            if (!providers.contains(url)) {
                this.session.unregister(url);
                previous.remove(url);
                this.instanceUpdatePending = true;
            }
        }
        for (URL url : providers) {
            if (!previous.contains(url)) {
                this.session.register(url);
                previous.add(url);
                this.instanceUpdatePending = true;
            }
        }
    }

    private void subscribe(MetBindInfo<?> service) {
        String serviceId = service.getBindID();
        URL consumer = DubboServiceUrls.consumer(this.context, service);
        URL previous = this.subscriptions.get(serviceId);
        if (consumer.equals(previous)) {
            return;
        }
        if (previous != null) {
            this.unsubscribe(serviceId);
        }
        NotifyListener listener = urls -> this.receive(service, urls);
        this.session.register(DubboServiceUrls.registration(consumer));
        try {
            this.session.subscribe(consumer, listener);
            this.subscriptions.put(serviceId, consumer);
            this.listeners.put(serviceId, listener);
        } catch (RuntimeException failure) {
            this.session.unregister(DubboServiceUrls.registration(consumer));
            throw failure;
        }
    }

    private void receive(MetBindInfo<?> service, List<URL> urls) {
        if (!this.running || this.context.getServiceInfo(service.getBindID()) == null) {
            return;
        }
        List<InterAddress> addresses = DubboServiceUrls.providers(this.context, service, urls, this.secureScheme);
        this.context.getUpdater().refreshAddress(service.getBindID(), addresses);
    }

    private void unsubscribe(String serviceId) {
        URL consumer = this.subscriptions.remove(serviceId);
        NotifyListener listener = this.listeners.remove(serviceId);
        this.session.unsubscribe(consumer, listener);
        this.session.unregister(DubboServiceUrls.registration(consumer));
        this.context.getUpdater().refreshAddress(serviceId, List.of());
    }

    @Override
    public synchronized void close() {
        this.running = false;
        if (this.events != null) {
            this.events.cancel();
            this.events = null;
        }
        if (this.worker == null) {
            this.closeSession();
            return;
        }

        // 已经排队的操作先结束，再在同一队列撤销登记和释放独占 SDK 资源。
        var cleanup = this.worker.submit(this::closeSession);
        this.worker.shutdown();
        this.worker = null;
        try {
            cleanup.get(this.timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing Dubbo registry", failure);
        } catch (ExecutionException | TimeoutException failure) {
            throw new IllegalStateException("Cannot close Dubbo registry", failure);
        }
    }

    private void closeSession() {
        // 独占 SDK 模型统一撤销注册并释放订阅、连接；不关闭借用的 Meteor 上下文。
        if (this.session != null) {
            try {
                this.session.close();
            } finally {
                this.session = null;
                this.published.clear();
                this.subscriptions.clear();
                this.listeners.clear();
            }
        }
    }
}
