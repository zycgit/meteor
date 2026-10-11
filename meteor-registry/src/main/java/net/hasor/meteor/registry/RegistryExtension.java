/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.registry.admin.support.TargetAdminImpl;
import net.hasor.meteor.registry.client.RegistryClient;
import net.hasor.meteor.registry.client.support.DefaultRegistryClient;

/** 统一管理在线与离线模式；仅在线模式持有注册中心管理端与客户端。 */
final class RegistryExtension implements MetExtension {
    private final TargetAdmin           targetAdmin;
    private final RegistryAdmin         admin;
    private final DefaultRegistryClient client;

    RegistryExtension(MetContext context, RegistryAdmin admin, DefaultRegistryClient client) {
        this.targetAdmin = new TargetAdminImpl(context);
        this.admin = admin;
        this.client = client;
    }

    @Override
    public <T> T getService(Class<T> type) {
        if (type == TargetAdmin.class) {
            return type.cast(this.targetAdmin);
        }
        if (type == RegistryAdmin.class) {
            return type.cast(this.admin);
        }
        if (type == RegistryClient.class) {
            return type.cast(this.client);
        }
        return null;
    }

    @Override
    public void beforeStart() throws Exception {
        if (this.client != null) {
            this.client.beforeStart();
        }
    }

    @Override
    public void afterStart() throws Exception {
        if (this.client != null) {
            this.client.afterStart();
        }
    }

    @Override
    public void close() throws Exception {
        // 直连连接、管理 RPC 和治理过滤器由 MetContext 统一释放。
        if (this.client == null) {
            return;
        }

        // 在线模式释放存储会话；客户端清理失败时仍关闭管理端，并保留两侧异常。
        Exception failure = null;
        try {
            this.client.close();
        } catch (Exception cleanup) {
            failure = cleanup;
        }
        try {
            this.admin.close();
        } catch (Exception cleanup) {
            if (failure == null) {
                throw cleanup;
            }
            if (failure != cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
