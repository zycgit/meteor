/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.nacos;
import java.util.Properties;
import java.util.concurrent.Callable;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.config.ConfigService;
import com.alibaba.nacos.api.naming.NamingService;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.registry.RegistryConfiguration;
import net.hasor.meteor.registry.admin.RegistryInfo;
import net.hasor.meteor.registry.shared.storage.StorageAccess;

/** Nacos 注册中心配置，直接创建独立存储会话。 */
public final class NacosRegistryConfiguration extends RegistryConfiguration {
    private String address       = null;
    private String username      = null;
    private String password      = null;
    private String group         = "meteor";
    private String namespace     = null;
    private long   timeoutMillis = 5000;

    /** 设置 Nacos 服务地址，必填，无默认地址。 */
    public NacosRegistryConfiguration setAddress(String address) {
        this.address = address;
        return this;
    }

    /** 设置存储认证用户名，与 password 配套使用。 */
    public NacosRegistryConfiguration setUsername(String username) {
        this.username = username;
        return this;
    }

    /** 设置存储认证密码，允许空密码，不作为管理 API 的 token。 */
    public NacosRegistryConfiguration setPassword(String password) {
        this.password = password;
        return this;
    }

    /** 设置注册数据分组，默认 meteor。 */
    public NacosRegistryConfiguration setGroup(String value) {
        this.group = value;
        return this;
    }

    /** 设置 Nacos 命名空间 ID，默认使用公共命名空间。 */
    public NacosRegistryConfiguration setNamespace(String value) {
        this.namespace = value;
        return this;
    }

    /** 设置配置读取超时，单位毫秒，默认 5000。 */
    public NacosRegistryConfiguration setTimeoutMillis(long value) {
        this.timeoutMillis = value;
        return this;
    }

    @Override
    public String getProvider() {
        return "nacos";
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

        if (StringUtils.isBlank(this.group)) {
            throw new IllegalArgumentException("Nacos group must not be blank");
        }
        if (this.timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }

        // SDK 所需属性仅由明确的配置字段生成，构建后不再读取可变配置。
        Properties properties = new Properties();
        properties.setProperty("serverAddr", this.address.trim());
        if (this.namespace != null) {
            properties.setProperty("namespace", this.namespace);
        }
        if (this.username != null) {
            properties.setProperty("username", this.username);
            properties.setProperty("password", this.password);
        }
        String group = this.group;
        long timeout = this.timeoutMillis;
        return () -> {
            NamingService naming = NacosFactory.createNamingService(properties);
            ConfigService configs = null;
            try {
                configs = NacosFactory.createConfigService(properties);
                return new NacosRegistry(naming, configs, properties, group, timeout);
            } catch (Exception failure) {
                if (configs != null) {
                    try {
                        configs.shutDown();
                    } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }

                try {
                    naming.shutDown();
                } catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        };
    }

    @Override
    protected RegistryInfo describeStorage() {
        RegistryInfo info = super.describeStorage();
        info.getScope().put("address", this.address.trim());
        info.getScope().put("group", this.group);
        if (this.namespace != null) {
            info.getScope().put("namespace", this.namespace);
        }
        return info;
    }
}
