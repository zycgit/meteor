/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.nacos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.EventListener;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;

/** 已知 Naming 节点和 Config 键的原生订阅；范围内定期复核通知遗漏与读取失败。 */
final class NacosStorageWatch implements Cancellable {
    private static final Logger                        LOGGER            = LoggerFactory.getLogger(NacosStorageWatch.class);
    private final        NamingService                 naming;
    private final        ConfigService                 configs;
    private final        String                        group;
    private final        List<String>                  names;
    private final        List<String>                  dataIds;
    private final        Callable<Map<String, String>> snapshot;
    private final        Runnable                      listener;
    private final        ScheduledExecutorService      executor;
    private final        Consumer<NacosStorageWatch>   onCancel;
    private final        AtomicBoolean                 cancelled         = new AtomicBoolean();
    private final        AtomicBoolean                 queued            = new AtomicBoolean();
    private final        List<String>                  subscribedNames   = new ArrayList<>();
    private final        List<String>                  subscribedConfigs = new ArrayList<>();
    private final        EventListener                 namingListener    = event -> this.signal();
    private final        Listener                      configListener    = new Listener() {
        @Override
        public Executor getExecutor() {
            return NacosStorageWatch.this.executor;
        }

        @Override
        public void receiveConfigInfo(String content) {
            NacosStorageWatch.this.signal();
        }
    };
    private              Map<String, String>           previous;
    private              ScheduledFuture<?>            refresh;

    NacosStorageWatch(NamingService naming, ConfigService configs, String group, List<String> names, List<String> dataIds, Callable<Map<String, String>> snapshot, Runnable listener, ScheduledExecutorService executor, Consumer<NacosStorageWatch> onCancel) {
        this.naming = naming;
        this.configs = configs;
        this.group = group;
        this.names = names;
        this.dataIds = dataIds;
        this.snapshot = snapshot;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = executor;
        this.onCancel = onCancel;
    }

    synchronized void start() {
        try {
            for (String name : this.names) {
                this.naming.subscribe(name, this.group, this.namingListener);
                this.subscribedNames.add(name);
            }
            for (String dataId : this.dataIds) {
                this.configs.addListener(dataId, this.group, this.configListener);
                this.subscribedConfigs.add(dataId);
            }
            this.refresh = this.executor.scheduleWithFixedDelay(this::signal, 1, 1, TimeUnit.SECONDS);
            this.signal();
        } catch (Exception failure) {
            try {
                this.cancel();
            } catch (RuntimeException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw new IllegalStateException("Cannot subscribe to Nacos registry changes", failure);
        }
    }

    private void signal() {
        if (this.cancelled.get() || !this.queued.compareAndSet(false, true)) {
            return;
        }
        try {
            this.executor.execute(this::read);
        } catch (RejectedExecutionException closed) {
            this.queued.set(false);
        }
    }

    private void read() {
        this.queued.set(false);
        if (this.cancelled.get()) {
            return;
        }
        try {
            boolean namingUp = this.names.isEmpty() || StringUtils.equals("UP", this.naming.getServerStatus());
            boolean configUp = this.dataIds.isEmpty() || StringUtils.equals("UP", this.configs.getServerStatus());
            if (!namingUp || !configUp) {
                throw new IllegalStateException("Nacos is disconnected");
            }
            Map<String, String> current = this.snapshot.call();
            if (!this.cancelled.get() && !current.equals(this.previous)) {
                this.listener.run();
                this.previous = current;
            }
        } catch (Exception failure) {
            // 保持上次成功应用的配置，不能把读取异常解释为注册数据被删除。
            this.previous = null;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Cannot refresh Nacos subscription\n" + ExceptionUtils.getStackTrace(failure));
            }
        }
    }

    @Override
    public synchronized boolean cancel() {
        if (!this.cancelled.compareAndSet(false, true)) {
            return false;
        }
        if (this.refresh != null) {
            this.refresh.cancel(false);
        }
        Exception failure = null;
        for (String name : this.subscribedNames) {
            try {
                this.naming.unsubscribe(name, this.group, this.namingListener);
            } catch (Exception cleanup) {
                failure = append(failure, cleanup);
            }
        }
        for (String dataId : this.subscribedConfigs) {
            try {
                this.configs.removeListener(dataId, this.group, this.configListener);
            } catch (Exception cleanup) {
                failure = append(failure, cleanup);
            }
        }
        this.onCancel.accept(this);
        if (failure != null) {
            throw new IllegalStateException("Cannot unsubscribe Nacos registry changes", failure);
        }
        return true;
    }

    private static Exception append(Exception previous, Exception next) {
        if (previous == null) {
            return next;
        }
        previous.addSuppressed(next);
        return previous;
    }
}
