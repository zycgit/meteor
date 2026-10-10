/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.config.listener.Listener;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.FuzzyWatchChangeEvent;
import com.alibaba.nacos.api.naming.listener.FuzzyWatchEventWatcher;
import com.alibaba.nacos.api.naming.pojo.ListView;
import net.hasor.cobble.ExceptionUtils;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.concurrent.future.Cancellable;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.registry.shared.utils.RegistryJson;

/** Config 分片监听与 Naming 服务发现、实例监听共同驱动订阅。 */
final class NacosStorageWatch implements Cancellable, Listener, EventListener, FuzzyWatchEventWatcher {
    private static final Logger                        LOGGER       = LoggerFactory.getLogger(NacosStorageWatch.class);
    private static final String                        PREFIX       = "meteor.v2.";
    private final        NamingService                 naming;
    private final        ConfigService                 configs;
    private final        String                        group;
    private final        long                          timeout;
    private final        List<String>                  families;
    private final        Callable<Map<String, String>> snapshot;
    private final        Runnable                      listener;
    private final        ScheduledExecutorService      executor;
    private final        Consumer<NacosStorageWatch>   onCancel;
    private final        Object                        resources    = new Object();
    private final        Set<String>                   patterns     = new HashSet<>();
    private final        Set<String>                   configNames  = new HashSet<>();
    private final        Set<String>                   serviceNames = new HashSet<>();
    private final        AtomicBoolean                 queued       = new AtomicBoolean();
    private final        AtomicBoolean                 refresh      = new AtomicBoolean();
    private final        AtomicBoolean                 cancelled    = new AtomicBoolean();
    private              Map<String, String>           previous;
    private              boolean                       retry;
    private              ScheduledFuture<?>            maintenance;

    NacosStorageWatch(NamingService naming, ConfigService configs, String group, long timeout, List<String> families, Callable<Map<String, String>> snapshot, Runnable listener, ScheduledExecutorService executor, Consumer<NacosStorageWatch> onCancel) {
        this.naming = naming;
        this.configs = configs;
        this.group = group;
        this.timeout = timeout;
        this.families = families;
        this.snapshot = snapshot;
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = executor;
        this.onCancel = onCancel;
    }

    void start() {
        // 正常变更由原生通知驱动；定时任务只检查连接状态并补偿失败。
        this.maintenance = this.executor.scheduleWithFixedDelay(this::maintain, 1, 1, TimeUnit.SECONDS);
        this.signal();
    }

    @Override
    public Executor getExecutor() {
        // SDK 回调只提交工作，不执行存储查询和用户代码。
        return null;
    }

    @Override
    public void receiveConfigInfo(String content) {
        this.signal();
    }

    @Override
    public void onEvent(Event event) {
        this.signal();
    }

    @Override
    public void onEvent(FuzzyWatchChangeEvent event) {
        if (StringUtils.equals("FUZZY_WATCH_DIFF_SYNC_NOTIFY", event.getSyncType())) {
            this.refresh.set(true);
        }
        this.signal();
    }

    private void requireConnected() {
        boolean namingConnected = StringUtils.equals("UP", this.naming.getServerStatus());
        boolean configConnected = StringUtils.equals("UP", this.configs.getServerStatus());
        if (!namingConnected || !configConnected) {
            throw new IllegalStateException("Nacos is disconnected");
        }
    }

    private void maintain() {
        if (this.cancelled.get()) {
            return;
        }

        try {
            this.requireConnected();
            if (this.retry) {
                this.signal();
            }
        } catch (Exception disconnected) {
            this.previous = null;
            this.retry = true;
        }
    }

    private void signal() {
        if (this.cancelled.get() || !this.queued.compareAndSet(false, true)) {
            return;
        }

        try {
            this.executor.execute(this::read);
        } catch (RejectedExecutionException stopped) {
            this.queued.set(false);
        }
    }

    private void read() {
        this.queued.set(false);
        if (this.cancelled.get()) {
            return;
        }
        if (this.refresh.getAndSet(false)) {
            this.previous = null;
        }

        try {
            this.requireConnected();
            this.subscribeSources();
            Map<String, String> current = this.snapshot.call();
            if (!this.cancelled.get() && !current.equals(this.previous)) {
                this.listener.run();
                this.previous = current;
            }
            this.retry = false;
        } catch (Exception failure) {
            this.previous = null;
            this.retry = true;
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Nacos subscription will retry\n" + ExceptionUtils.getStackTrace(failure));
            }
        }
    }

    private void subscribeSources() throws Exception {
        List<String> prefixes = new ArrayList<>();
        for (String family : this.families) {
            String prefix = PREFIX + family + ".";
            prefixes.add(prefix);
            this.watchNaming(prefix);
            if (family.startsWith("r.")) {
                String sourcePrefix = PREFIX + "source." + family.substring(2) + ".";
                prefixes.add(sourcePrefix);
                this.watchNaming(sourcePrefix);
            }

            String catalog = PREFIX + "catalog." + family;
            this.subscribeConfig(catalog);
            String content = this.configs.getConfig(catalog, this.group, this.timeout);
            if (content != null) {
                for (String shard : RegistryJson.read(content, String[].class)) {
                    this.subscribeConfig(PREFIX + "config." + family + "." + shard);
                }
            }
        }
        // 先安装所有目录监听，再统一列举已有服务，避免按记录类型反复扫描全目录。
        this.subscribeNaming(prefixes);
    }

    private void subscribeConfig(String name) throws Exception {
        synchronized (this.resources) {
            if (!this.cancelled.get() && !this.configNames.contains(name)) {
                this.configs.addListener(name, this.group, this);
                this.configNames.add(name);
            }
        }
    }

    private void watchNaming(String prefix) throws Exception {
        synchronized (this.resources) {
            if (this.cancelled.get()) {
                return;
            }

            String pattern = prefix + "*";
            if (!this.patterns.contains(pattern)) {
                this.naming.fuzzyWatch(pattern, this.group, this);
                this.patterns.add(pattern);
            }
        }
    }

    private void subscribeNaming(List<String> prefixes) throws Exception {
        int page = 1;
        ListView<String> names;
        do {
            names = this.naming.getServicesOfServer(page, 100, this.group);
            for (String name : names.getData()) {
                for (String prefix : prefixes) {
                    if (!name.startsWith(prefix)) {
                        continue;
                    }
                    synchronized (this.resources) {
                        if (!this.cancelled.get() && !this.serviceNames.contains(name)) {
                            this.naming.subscribe(name, this.group, this);
                            this.serviceNames.add(name);
                        }
                    }
                    break;
                }
            }
        } while (page++ * 100 < names.getCount());
    }

    @Override
    public boolean cancel() {
        if (!this.cancelled.compareAndSet(false, true)) {
            return false;
        }

        this.maintenance.cancel(false);
        synchronized (this.resources) {
            for (String name : this.configNames) {
                this.configs.removeListener(name, this.group, this);
            }

            for (String name : this.serviceNames) {
                try {
                    this.naming.unsubscribe(name, this.group, this);
                } catch (Exception failure) {
                    LOGGER.warn("Cannot unsubscribe Nacos service " + name, failure);
                }
            }

            for (String pattern : this.patterns) {
                try {
                    this.naming.cancelFuzzyWatch(pattern, this.group, this);
                } catch (Exception failure) {
                    LOGGER.warn("Cannot cancel Nacos service discovery " + pattern, failure);
                }
            }
            this.configNames.clear();
            this.serviceNames.clear();
            this.patterns.clear();
        }

        this.onCancel.accept(this);
        return true;
    }
}