/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import net.hasor.meteor.registry.shared.storage.domain.DirectoryQuery;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.InstanceStatus;
import net.hasor.meteor.registry.shared.storage.domain.RecordPage;

/** 仅在故障、生命周期和手动拉取场景中装饰真实内存存储，不实现另一套存储。 */
public final class StorageProbe {
    private final    AtomicInteger       opened        = new AtomicInteger();
    private final    AtomicInteger       closed        = new AtomicInteger();
    private final    List<StorageAccess> sessions      = new ArrayList<>();
    private volatile boolean             unavailable;
    private volatile Supplier<Exception> readFailure;
    private          boolean             notifications = true;

    public StorageAccess observe(StorageAccess storage) {
        // 跟踪正式存储会话，关闭时只累计一次。
        this.opened.incrementAndGet();
        synchronized (this.sessions) {
            this.sessions.add(storage);
        }
        return (StorageAccess) Proxy.newProxyInstance(StorageAccess.class.getClassLoader(), new Class<?>[] { StorageAccess.class }, (proxy, method, args) -> {
            String name = method.getName();
            if (StringUtils.equals(name, "close")) {
                synchronized (this.sessions) {
                    if (this.sessions.remove(storage)) {
                        this.closed.incrementAndGet();
                    }
                }
            } else if (this.unavailable) {
                throw new IllegalStateException("offline storage");
            }

            // 分别模拟读取失败和通知暂停，不替代真实存储实现。
            if (StringUtils.equals(name, "getConfiguration") && this.readFailure != null) {
                throw this.readFailure.get();
            }

            boolean watch = StringUtils.equals(name, "watchConfiguration") || StringUtils.equals(name, "watchProviders");
            if (watch && !this.notifications) {
                return (Cancellable) () -> true;
            }

            // 其他操作交还原会话，保留底层异常。
            try {
                return method.invoke(storage, args);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    public int openedSessions() {
        return this.opened.get();
    }

    public int closedSessions() {
        return this.closed.get();
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public void setReadFailure(Supplier<Exception> readFailure) {
        this.readFailure = readFailure;
    }

    public void setNotifications(boolean notifications) {
        this.notifications = notifications;
    }

    /** 撤回当前会话的服务登记，验证自动核对恢复；会话失效由独立生命周期用例验证。 */
    public void withdrawSessionServices() throws Exception {
        synchronized (this.sessions) {
            for (StorageAccess session : this.sessions) {
                DirectoryQuery instances = new DirectoryQuery();
                do {
                    RecordPage<InstanceStatus> page = session.instances(null, instances);
                    for (InstanceStatus instance : page.getItems()) {
                        String id = instance.getInstance().getInstanceId();
                        DirectoryQuery services = new DirectoryQuery();
                        do {
                            RecordPage<EntServiceRuntimeData> entries = session.instanceServices(id, services);
                            for (EntServiceRuntimeData service : entries.getItems()) {
                                try {
                                    session.unregisterService(id, service.getServiceId());
                                } catch (IllegalStateException otherSession) {
                                    // 每个会话只能撤回自己拥有的实例。
                                }
                            }
                            services.setCursor(entries.getNextCursor());
                        } while (services.getCursor() != null);
                    }

                    instances.setCursor(page.getNextCursor());
                } while (instances.getCursor() != null);
            }
        }
    }
}
