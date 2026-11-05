/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.component.commands;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.component.provider.ProviderConnection;
import net.hasor.meteor.console.component.provider.ProviderFactories;
import net.hasor.meteor.console.global.error.ConsoleOperationException;
import net.hasor.meteor.console.model.fo.ClusterProfile;
import net.hasor.meteor.console.model.fo.ManagementTarget;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.registry.RegistryAdmin;
import net.hasor.meteor.registry.RegistryTokenProvider;
import net.hasor.meteor.registry.TargetAdmin;
import net.hasor.meteor.registry.admin.*;

/** 一条管理连接的执行环境；管理独占 Provider，直连时只借用 TargetAdmin。 */
public final class CommandContext implements AutoCloseable {
    private final ClusterProfile               profile;
    private final Callable<ProviderConnection> opener;
    private final TargetAdmin                  direct;
    private       ProviderConnection           connection;
    private       boolean                      closed;

    private CommandContext(ClusterProfile profile, Callable<ProviderConnection> opener, TargetAdmin direct) {
        this.profile = profile;
        this.opener = opener;
        this.direct = direct;
    }

    /** 首次执行命令时打开独占连接，每次管理调用仍读取当前凭据。 */
    static CommandContext open(ClusterProfile profile, RegistryTokenProvider credentials, ProviderFactories factories) {
        return new CommandContext(profile, () -> factories.open(profile, credentials), null);
    }

    /** 接管已打开连接，即使没有执行命令，关闭时也会释放它。 */
    static CommandContext own(ProviderConnection connection) {
        Objects.requireNonNull(connection, "connection");
        CommandContext context = new CommandContext(null, () -> connection, null);
        context.connection = connection;
        return context;
    }

    /** 借用直连管理入口，不关闭它或其所属上下文。 */
    static CommandContext direct(TargetAdmin direct) {
        return new CommandContext(null, null, Objects.requireNonNull(direct, "direct"));
    }

    synchronized <R> R execute(CommandRequest<R> request, RegistryCommand<CommandRequest<R>, R> command) {
        if (this.closed) {
            throw new ConcurrentModificationException("连接配置已更改，请刷新后重试");
        }
        if (request.getTarget().isDirect() && this.direct == null) {
            throw new IllegalArgumentException("直连实例操作需要独立直连入口");
        }
        try {
            if (this.connection == null && this.opener != null) {
                this.connection = this.opener.call();
            }
            return command.execute(this, request);
        } catch (Exception failure) {
            throw this.redact(failure);
        }
    }

    /** 返回借用的注册中心入口；生命周期由当前 Context 负责。 */
    public RegistryAdmin registry() {
        if (this.connection == null) {
            throw new UnsupportedOperationException("控制台以直连模式启动，未连接注册中心");
        }
        return this.connection.registry();
    }

    public MetClusterAdmin cluster() {
        return this.registry().getClusterAdmin();
    }

    public MetInstanceAdmin instance(ManagementTarget request) {
        if (request.isDirect()) {
            return this.direct.withTokenProvider(request::getManagementToken).getInstanceAdminByAddress(request.getTarget());
        } else {
            return this.registry().getInstanceAdmin(request.requireInstanceId());
        }
    }

    public MetServiceAdmin service(ManagementTarget request) {
        String serviceId = request.requireServiceId();
        if (request.isDirect()) {
            return this.direct.withTokenProvider(request::getManagementToken).getServiceAdminByAddress(request.getTarget(), serviceId);
        }

        String instanceId = request.getInstanceId();
        if (StringUtils.isNotBlank(instanceId)) {
            return this.registry().getServiceAdmin(serviceId, instanceId);
        } else {
            return this.registry().getServiceAdmin(serviceId);
        }
    }

    public String scope(ManagementTarget request) {
        String scope = request.requireScope();
        if (StringUtils.equals("cluster", scope) && request.isDirect()) {
            throw new UnsupportedOperationException("直连模式没有集群治理范围");
        }
        return scope;
    }

    public List<String> targetInstances(ManagementTarget request) {
        String unit = request.getTargetUnit();
        if (StringUtils.isBlank(unit)) {
            return null;
        }

        boolean instanceRules = StringUtils.equals("instance", request.getScope());
        if (StringUtils.equals("ALL", unit) && !instanceRules) {
            return null;
        }
        if (request.isDirect()) {
            throw new IllegalArgumentException("直连操作只能管理当前实例");
        }

        Set<String> targets = new LinkedHashSet<>();
        MetDirectoryQuery query = new MetDirectoryQuery();
        if (instanceRules) {
            MetInstanceInfo source = this.instance(request).instance();
            if (StringUtils.equals("LOCAL", unit)) {
                unit = source.getUnitName();
            }

            query.setUnitName(StringUtils.equals("ALL", unit) ? null : unit);
            do {
                MetPage<MetInstanceInfo> page = this.cluster().instances(source.getApplicationName(), query);
                for (MetInstanceInfo instance : page.getItems()) {
                    targets.add(instance.getInstanceId());
                }
                query.setCursor(page.getNextCursor());
            } while (query.getCursor() != null);
        } else {
            if (StringUtils.isNotBlank(request.getInstanceId())) {
                throw new IllegalArgumentException("当前操作已限定单个实例，不能再扩大到单元");
            }

            query.setUnitName(StringUtils.equals("LOCAL", unit) ? request.getLocalUnit() : unit);
            MetServiceAdmin service = this.service(request);
            for (MetServiceType role : MetServiceType.values()) {
                query.setCursor(null);
                do {
                    MetPage<MetServiceMember> page;
                    if (role == MetServiceType.Provider) {
                        page = service.providers(null, query);
                    } else {
                        page = service.consumers(null, query);
                    }
                    for (MetServiceMember member : page.getItems()) {
                        targets.add(member.getInstance().getInstanceId());
                    }
                    query.setCursor(page.getNextCursor());
                } while (query.getCursor() != null);
            }
        }

        if (targets.isEmpty()) {
            throw new IllegalArgumentException("目标单元没有可治理的实例");
        }
        return new ArrayList<>(targets);
    }

    public int applyTargets(ManagementTarget target, List<String> instances, Consumer<ManagementTarget> action) {
        if (instances == null) {
            action.accept(target);
            return 0;
        }
        int affected = 0;
        for (String id : instances) {
            try {
                action.accept(target.atInstance(id));
                affected++;
            } catch (Exception failure) {
                String message = "已完成 " + affected + " 个实例；" + id + " 失败：" + failure.getMessage();
                throw new ConsoleOperationException(message, failure);
            }
        }
        return affected;
    }

    public MetValidationResult validateRule(ManagementTarget request, MetRuleType type, String rule) {
        return switch (this.scope(request)) {
            case "cluster" -> this.cluster().validateRule(type, rule);
            case "service" -> this.service(request).validateRule(type, rule);
            case "instance" -> throw new UnsupportedOperationException("实例管理接口未提供独立规则校验");
            default -> throw new IllegalArgumentException("未知治理范围");
        };
    }

    private ConsoleOperationException redact(Exception failure) {
        Throwable visible = failure;
        while ((visible instanceof UndeclaredThrowableException || visible instanceof InvocationTargetException || visible instanceof ExecutionException || visible instanceof CompletionException) && visible.getCause() != null) {
            visible = visible.getCause();
        }
        Throwable cause = visible;
        while (cause instanceof ConsoleOperationException && cause.getCause() != null) {
            cause = cause.getCause();
        }

        String message = visible.getMessage();
        if (cause instanceof MetException remote && remote.getStatus() == ProtocolStatus.Unauthorized) {
            // 消息统一，但保留原始 401，让 RPC 级联和 HTTP 边界各自转换状态。
            message = "Remote Console authentication failed";
        }
        if (StringUtils.isBlank(message)) {
            message = visible.getClass().getSimpleName();
        }
        if (this.profile != null) {
            if (StringUtils.isNotBlank(this.profile.getPassword())) {
                message = message.replace(this.profile.getPassword(), "***");
            }
            if (StringUtils.isNotBlank(this.profile.getManagementToken())) {
                message = message.replace(this.profile.getManagementToken(), "***");
            }
        }
        return new ConsoleOperationException(message, visible);
    }

    @Override
    public synchronized void close() {
        if (!this.closed) {
            this.closed = true;
            if (this.connection != null) {
                try {
                    this.connection.close();
                } catch (Exception failure) {
                    throw this.redact(failure);
                }
            }
        }
    }

}
