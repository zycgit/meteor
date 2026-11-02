/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.zookeeper;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.common.PathUtils;

/** ZooKeeper 注册中心配置，直接创建独立存储会话。 */
public final class ZookeeperRegistryConfiguration extends RegistryConfiguration {
    private String address              = null;
    private String username             = null;
    private String password             = null;
    private String root                 = "/meteor";
    private int    timeoutMillis        = 5000;
    private int    sessionTimeoutMillis = 15000;

    /** 设置 ZooKeeper 集群连接地址，必填，无默认地址。 */
    public ZookeeperRegistryConfiguration setAddress(String address) {
        this.address = address;
        return this;
    }

    /** 设置存储认证用户名，与 password 配套使用。 */
    public ZookeeperRegistryConfiguration setUsername(String username) {
        this.username = username;
        return this;
    }

    /** 设置存储认证密码，允许空密码，不作为管理 API 的 token。 */
    public ZookeeperRegistryConfiguration setPassword(String password) {
        this.password = password;
        return this;
    }

    /** 设置注册数据根路径，默认 /meteor。 */
    public ZookeeperRegistryConfiguration setRoot(String value) {
        this.root = value;
        return this;
    }

    /** 设置连接超时，单位毫秒，默认 5000。 */
    public ZookeeperRegistryConfiguration setTimeoutMillis(int value) {
        this.timeoutMillis = value;
        return this;
    }

    /** 设置会话超时，单位毫秒，默认 15000。 */
    public ZookeeperRegistryConfiguration setSessionTimeoutMillis(int value) {
        this.sessionTimeoutMillis = value;
        return this;
    }

    @Override
    public String getProvider() {
        return "zookeeper";
    }

    @Override
    protected Callable<StorageAccess> createStorageOpener() {
        if (StringUtils.isBlank(this.address)) {
            throw new IllegalArgumentException("Registry address is required");
        }
        boolean credentialsConfigured = this.username != null || this.password != null;
        boolean missingUsername = StringUtils.isBlank(this.username);
        if (credentialsConfigured && (missingUsername || this.password == null)) {
            throw new IllegalArgumentException("Registry authentication requires both username and password");
        }

        PathUtils.validatePath(this.root);
        if (this.timeoutMillis <= 0 || this.sessionTimeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis and sessionTimeoutMillis must be positive");
        }
        if (this.username != null && this.username.contains(":")) {
            throw new IllegalArgumentException("ZooKeeper digest username must not contain ':'");
        }

        // 固定连接参数；直到启动时才创建 SDK 会话。
        String address = this.address.trim();
        String root = this.root;
        int timeout = this.timeoutMillis;
        int sessionTimeout = this.sessionTimeoutMillis;
        String username = this.username;
        String password = this.password;
        return () -> {
            CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder();
            builder.connectString(address);
            builder.sessionTimeoutMs(sessionTimeout);
            builder.connectionTimeoutMs(timeout);
            builder.retryPolicy(new ExponentialBackoffRetry(100, 3));
            if (username != null) {
                byte[] credentials = (username + ":" + password).getBytes(StandardCharsets.UTF_8);
                builder.authorization("digest", credentials);
            }

            CuratorFramework client = builder.build();
            try {
                client.start();
                if (!client.blockUntilConnected(timeout, TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("ZooKeeper connection timed out");
                }
                return new ZookeeperRegistry(client, root);
            } catch (Exception failure) {
                client.close();
                throw failure;
            }
        };
    }

    @Override
    protected RegistryInfo describeStorage() {
        RegistryInfo info = super.describeStorage();
        info.getScope().put("address", this.address.trim());
        info.getScope().put("root", this.root);
        return info;
    }
}
