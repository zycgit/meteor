/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.core.*;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.console.component.commands.dto.directory.ClusterInfoRequest;
import net.hasor.meteor.console.component.commands.dto.directory.InstancesRequest;
import net.hasor.meteor.console.component.commands.dto.directory.ServicesRequest;
import net.hasor.meteor.console.component.provider.ProviderFactories;
import net.hasor.meteor.console.dao.ClusterDao;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.registry.TargetAdmin;

/** 按集群分发命令，持有连接环境并回收空闲连接。 */
@Singleton
public final class CommandDispatcher implements AutoCloseable {
    private static final Logger                                  LOGGER    = LoggerFactory.getLogger(CommandDispatcher.class);
    @Inject
    private              AppContext                              appContext;
    @Inject
    private              ClusterDao                              clusters;
    @Inject
    private              ProviderFactories                       providers;
    @Inject
    private              SessionService                          sessions;
    @Inject
    private              MeteorConfig                            config;
    private final        Map<CommandType, RegistryCommand<?, ?>> commands  = new EnumMap<>(CommandType.class);
    private final        Map<String, ContextEntry>               contexts  = new LinkedHashMap<>();
    private final        Set<CommandContext>                     temporary = new LinkedHashSet<>();
    private              ContextEntry                            direct;
    private              ScheduledExecutorService                timer;
    private              long                                    lifetimeNanos;
    private              boolean                                 closed;

    @Init
    public void init() {
        long lifetime = this.config.getCommandContextTtl();
        if (lifetime <= 0) {
            throw new IllegalArgumentException("commands.context.ttl 必须大于 0，单位为毫秒");
        }
        this.lifetimeNanos = TimeUnit.MILLISECONDS.toNanos(lifetime);
        for (CommandType type : CommandType.values()) {
            this.commands.put(type, this.appContext.getInstance(type.getCommandType()));
        }
        this.timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "meteor-console-context-expiry");
            thread.setDaemon(true);
            return thread;
        });
        this.timer.scheduleAtFixedRate(this::expireContexts, 1, 1, TimeUnit.SECONDS);
    }

    /** 每次调用明确选择集群；直连请求不携带集群 ID。 */
    public <R> R execute(String clusterId, CommandRequest<R> request) {
        RegistryCommand<CommandRequest<R>, R> command = this.command(request);
        ManagementTarget target = request.getTarget();
        String requestedCluster = target.getClusterId();
        if (target.isDirect()) {
            if (StringUtils.isNotBlank(clusterId) || StringUtils.isNotBlank(requestedCluster)) {
                throw new IllegalArgumentException("直连操作不接受 clusterId");
            }
        } else {
            if (StringUtils.isBlank(clusterId)) {
                throw new IllegalArgumentException("缺少参数：clusterId");
            }
            if (StringUtils.isNotBlank(requestedCluster) && !StringUtils.equals(clusterId, requestedCluster)) {
                throw new IllegalArgumentException("命令的 clusterId 与管理目标不一致");
            }
        }

        ContextEntry entry;
        synchronized (this.contexts) {
            this.requireOpen();
            if (target.isDirect()) {
                if (this.direct == null) {
                    MetContext meteor = this.appContext.getInstance(MetContext.class);
                    this.direct = new ContextEntry(CommandContext.direct(meteor.getExtension(TargetAdmin.class)));
                }
                entry = this.direct;
            } else {
                ClusterProfile profile = this.clusters.get(clusterId);
                entry = this.contexts.computeIfAbsent(clusterId, id -> {
                    CommandContext context = CommandContext.open(profile, this.sessions, this.providers);
                    return new ContextEntry(context);
                });
            }
            entry.active++;
            entry.lastAccessNanos = System.nanoTime();
        }
        try {
            return entry.context.execute(request, command);
        } finally {
            synchronized (this.contexts) {
                entry.active--;
            }
        }
    }

    /** 候选配置只使用临时连接，不替换已保存集群的连接。 */
    public void test(ClusterProfile profile) {
        Objects.requireNonNull(profile, "profile").validate();
        CommandContext context;
        synchronized (this.contexts) {
            this.requireOpen();
            context = CommandContext.open(profile, this.sessions, this.providers);
            this.temporary.add(context);
        }
        try (context) {
            ManagementTarget target = new ManagementTarget();
            target.setLimit(1);
            this.execute(context, new ClusterInfoRequest(target));
            this.execute(context, new ServicesRequest(target));
            this.execute(context, new InstancesRequest(target));
        } finally {
            synchronized (this.contexts) {
                this.temporary.remove(context);
            }
        }
    }

    /** 配置更新或删除后释放旧连接，后续命令按新配置重新创建。 */
    public void invalidate(String clusterId) {
        ContextEntry previous;
        synchronized (this.contexts) {
            previous = this.contexts.remove(clusterId);
        }
        if (previous != null) {
            this.closeContext(previous.context);
        }
    }

    <R> R execute(CommandContext context, CommandRequest<R> request) {
        Objects.requireNonNull(context, "context");
        return context.execute(request, this.command(request));
    }

    /** 枚举在编译期绑定请求、实现和结果类型，校验先于连接创建。 */
    @SuppressWarnings("unchecked")
    private <R> RegistryCommand<CommandRequest<R>, R> command(CommandRequest<R> request) {
        Objects.requireNonNull(request, "request");
        CommandType type = request.getCommand();
        if (type == null || !type.getRequestType().isInstance(request)) {
            throw new IllegalArgumentException("命令与请求类型不匹配：" + type);
        }
        if (request.getTarget() == null) {
            throw new IllegalArgumentException("缺少管理目标");
        }

        RegistryCommand<?, ?> command = this.commands.get(type);
        if (command == null) {
            throw new IllegalStateException("命令未初始化：" + type);
        } else {
            return (RegistryCommand<CommandRequest<R>, R>) command;
        }
    }

    private void requireOpen() {
        if (this.closed) {
            throw new ConcurrentModificationException("命令入口已关闭");
        }
    }

    private void expireContexts() {
        List<CommandContext> expired = new ArrayList<>();
        synchronized (this.contexts) {
            if (this.closed) {
                return;
            }
            long now = System.nanoTime();
            Iterator<ContextEntry> entries = this.contexts.values().iterator();
            while (entries.hasNext()) {
                ContextEntry entry = entries.next();
                if (this.isExpired(entry, now)) {
                    entries.remove();
                    expired.add(entry.context);
                }
            }
            if (this.direct != null && this.isExpired(this.direct, now)) {
                expired.add(this.direct.context);
                this.direct = null;
            }
        }
        // 连接关闭可能阻塞；不能持有所有集群共享的缓存锁。
        for (CommandContext context : expired) {
            this.closeContext(context);
        }
    }

    private boolean isExpired(ContextEntry entry, long now) {
        return entry.active == 0 && now - entry.lastAccessNanos >= this.lifetimeNanos;
    }

    private void closeContext(CommandContext context) {
        try {
            context.close();
        } catch (Exception failure) {
            LOGGER.warn("Cannot close console registry session", failure);
        }
    }

    @Override
    @Destroy
    public void close() {
        List<CommandContext> owned = new ArrayList<>();
        synchronized (this.contexts) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            for (ContextEntry entry : this.contexts.values()) {
                owned.add(entry.context);
            }
            this.contexts.clear();
            owned.addAll(this.temporary);
            this.temporary.clear();
            if (this.direct != null) {
                owned.add(this.direct.context);
                this.direct = null;
            }
        }
        if (this.timer != null) {
            this.timer.shutdownNow();
        }
        for (CommandContext context : owned) {
            this.closeContext(context);
        }
        if (this.timer != null) {
            try {
                if (!this.timer.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Console context expiry task did not stop within 5 seconds");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ContextEntry {
        private final CommandContext context;
        private       long           lastAccessNanos;
        private       int            active;

        private ContextEntry(CommandContext context) {
            this.context = context;
        }
    }
}
