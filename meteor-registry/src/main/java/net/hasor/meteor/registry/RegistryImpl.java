/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry;
import java.util.concurrent.Callable;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.admin.MetClusterAdmin;
import net.hasor.meteor.registry.admin.MetInstanceAdmin;
import net.hasor.meteor.registry.admin.MetServiceAdmin;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.admin.support.AdminFactory;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/** 注册中心管理入口，持有存储会话并组装各维度的管理接口。 */
final class RegistryImpl implements RegistryAdmin {
    private final    AdminFactory            admins;
    private final    RegistryTokenProvider   tokenProvider;
    private final    Callable<StorageAccess> storageOpener;
    private          StorageAccess           storage;
    private volatile boolean                 closed;

    public RegistryImpl(Callable<StorageAccess> storageOpener, RegistryInfo info, RegistryTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
        this.admins = new AdminFactory(this::requireStorage, info.getProvider(), info.getScope());
        this.storageOpener = storageOpener;
    }

    private void requireOpen() {
        if (this.closed) {
            throw new IllegalStateException("Registry is closed");
        }
    }

    private StorageAccess requireStorage() {
        try {
            return this.openStorage();
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot open registry management storage", failure);
        }
    }

    synchronized StorageAccess openStorage() throws Exception {
        this.requireOpen();
        if (this.storage == null) {
            this.storage = this.storageOpener.call();
        }
        return this.storage;
    }

    private void requireManagementToken() {
        this.requireOpen();
        if (this.tokenProvider == null) {
            throw new IllegalStateException("tokenProvider is required for registry administration");
        }

        String token = this.tokenProvider.getToken();
        if (StringUtils.isBlank(token)) {
            throw new SecurityException("Invalid registry management token");
        }
    }

    @Override
    public MetClusterAdmin getClusterAdmin() {
        this.requireManagementToken();

        return this.admins.cluster();
    }

    @Override
    public MetServiceAdmin getServiceAdmin(String serviceId) {
        this.requireManagementToken();

        return this.admins.service(serviceId, null);
    }

    @Override
    public MetServiceAdmin getServiceAdmin(String serviceId, String instanceId) {
        this.requireManagementToken();

        if (StringUtils.isBlank(instanceId)) {
            throw new IllegalArgumentException("Instance ID must not be blank");
        } else {
            return this.admins.service(serviceId, instanceId);
        }
    }

    @Override
    public MetInstanceAdmin getInstanceAdmin(String instanceId) {
        this.requireManagementToken();

        return this.admins.instance(instanceId);
    }

    @Override
    public void close() throws Exception {
        synchronized (this) {
            if (this.closed) {
                return;
            }

            this.closed = true;
        }
        if (this.storage != null) {
            this.storage.close();
        }
    }
}
