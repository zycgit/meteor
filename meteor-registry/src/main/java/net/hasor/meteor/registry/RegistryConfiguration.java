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
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetExtension;
import net.hasor.meteor.bootstrap.MetExtensionFactory;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.client.support.DefaultRegistryClient;
import net.hasor.meteor.registry.client.support.ManagementRPCImpl;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/** 统一配置与创建入口；在线配置决定是否连接注册中心，具体子类提供存储能力。 */
public class RegistryConfiguration implements MetExtensionFactory {
    private boolean               online        = true;
    private RegistryTokenProvider tokenProvider = null;
    private String                publishHost   = null;
    private long                  refreshMillis = 1000;

    /** 获取存储提供者名称；基础配置没有存储提供者。 */
    public String getProvider() {
        return null;
    }

    /** 是否配置为连接注册中心，默认 true；不表示当前连接状态。 */
    public boolean isOnline() {
        return this.online;
    }

    /** 设置注册中心在线或离线模式，仅影响后续构建的上下文。 */
    public RegistryConfiguration setOnline(boolean online) {
        this.online = online;
        return this;
    }

    // 应用扩展、独立管理及存储访问

    /** 按在线配置安装扩展；离线模式不读取或校验存储配置。 */
    @Override
    public final MetExtension create(MetContext context) {
        if (context.getExtension(RegistryExtension.class) != null) {
            throw new IllegalStateException("Only one RegistryConfiguration can be installed on a Meteor context");
        }

        // 离线模式直接复用上下文的 RPC 能力。
        if (!this.online) {
            new ManagementRPCImpl(context, this.publishHost);
            return new RegistryExtension(context, null, null);
        }

        // 在线模式才创建存储客户端，实际连接留到启动阶段。
        if (this.refreshMillis <= 0) {
            throw new IllegalArgumentException("refreshMillis must be positive");
        }

        Callable<StorageAccess> opener = this.createStorageOpener();
        RegistryImpl admin = new RegistryImpl(opener, this.describeStorage(), this.tokenProvider);
        DefaultRegistryClient client = new DefaultRegistryClient(context, opener, this.refreshMillis, this.publishHost);
        return new RegistryExtension(context, admin, client);
    }

    /** 打开独立管理会话，调用方负责关闭；管理凭据不传给存储。 */
    public final RegistryAdmin buildAdmin() throws Exception {
        this.requireOnline();
        RegistryTokenProvider provider = this.tokenProvider;
        if (provider == null) {
            throw new IllegalArgumentException("tokenProvider is required");
        }

        Callable<StorageAccess> opener = this.createStorageOpener();
        RegistryInfo info = this.describeStorage();
        RegistryImpl admin = new RegistryImpl(opener, info, provider);
        admin.openStorage();
        return admin;
    }

    /** 打开独立存储会话，调用方负责关闭；无需管理凭据或 Meteor 上下文。 */
    public final StorageAccess openStorage() throws Exception {
        this.requireOnline();

        return this.createStorageOpener().call();
    }

    private void requireOnline() {
        if (!this.online) {
            throw new IllegalStateException("Registry storage is unavailable in offline mode");
        }
    }

    /** 校验并捕获具体存储配置，返回创建独立会话的动作；此时不打开连接。 */
    protected Callable<StorageAccess> createStorageOpener() {
        throw new IllegalStateException("Online mode requires a storage provider configuration");
    }

    /** 描述管理端展示的存储位置；不包含认证凭据。 */
    protected RegistryInfo describeStorage() {
        RegistryInfo info = new RegistryInfo();
        info.setProvider(this.getProvider());
        return info;
    }

    // 公共管理认证与应用接入配置

    /** 设置固定管理 token；应用接入和直接存储访问不需要此参数。 */
    public RegistryConfiguration setManagementToken(String token) {
        if (StringUtils.isBlank(token)) {
            throw new IllegalArgumentException("managementToken must not be blank");
        } else {
            return this.setTokenProvider(() -> token);
        }
    }

    /** 统一设置管理 token 获取方式；管理入口自动读取，无需调用方传递，不传给存储提供者。 */
    public RegistryConfiguration setTokenProvider(RegistryTokenProvider provider) {
        this.tokenProvider = provider;
        return this;
    }

    /** 设置应用对外发布的主机地址；null 时使用监听器的实际地址。 */
    public RegistryConfiguration setPublishHost(String publishHost) {
        this.publishHost = publishHost;
        return this;
    }

    /** 设置客户端周期核对间隔，单位毫秒，默认 1000，必须大于零。 */
    public RegistryConfiguration setRefreshMillis(long refreshMillis) {
        this.refreshMillis = refreshMillis;
        return this;
    }
}
