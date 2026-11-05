/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global;
import net.hasor.cobble.StringUtils;
import net.hasor.core.InjectSettings;

/** 控制台运行配置；由容器统一注入，使用方只读取配置值。 */
public final class MeteorConfig {
    @InjectSettings(value = "meteor.unit", defaultValue = "default")
    private String  unit;
    @InjectSettings(value = "data.directory", defaultValue = "data")
    private String  dataDirectory;
    @InjectSettings(value = "session.secureCookie", defaultValue = "false")
    private boolean secureCookie;
    @InjectSettings(value = "commands.context.ttl", defaultValue = "300000")
    private long    commandContextTtl;
    @InjectSettings(value = "security.enable", defaultValue = "false")
    private String  security;
    @InjectSettings("security.account")
    private String  securityAccount;
    @InjectSettings("management.token")
    private String  managementToken;
    @InjectSettings(value = "storage.type", defaultValue = "jdbc")
    private String  storageType;
    @InjectSettings("storage.address")
    private String  storageAddress;
    @InjectSettings(value = "storage.database", defaultValue = "h2")
    private String  storageDatabase;
    @InjectSettings("storage.username")
    private String  storageUsername;
    @InjectSettings("storage.password")
    private String  storagePassword;
    @InjectSettings("storage.managementToken")
    private String  storageManagementToken;
    @InjectSettings("storage.namespace")
    private String  storageNamespace;
    @InjectSettings(value = "storage.group", defaultValue = "meteor")
    private String  storageGroup;
    @InjectSettings(value = "storage.root", defaultValue = "/meteor")
    private String  storageRoot;
    @InjectSettings(value = "rpc.unit", defaultValue = "default")
    private String  rpcUnit;
    @InjectSettings(value = "rpc.port", defaultValue = "19193")
    private int     rpcPort;
    @InjectSettings(value = "rpc.host", defaultValue = "127.0.0.1")
    private String  rpcHost;

    public String getUnit() {
        return this.unit;
    }

    public String getDataDirectory() {
        return this.dataDirectory;
    }

    public boolean isSecureCookie() {
        return this.secureCookie;
    }

    public long getCommandContextTtl() {
        return this.commandContextTtl;
    }

    public boolean isSecurityEnabled() {
        if (StringUtils.equalsIgnoreCase(this.security, "true")) {
            return true;
        }
        if (StringUtils.equalsIgnoreCase(this.security, "false")) {
            return false;
        }
        throw new IllegalArgumentException("security.enable 必须为 true 或 false");
    }

    public String getSecurityAccount() {
        return this.securityAccount;
    }

    public String getManagementToken() {
        String token = System.getenv("METEOR_REGISTRY_MANAGEMENT_TOKEN");
        if (StringUtils.isNotBlank(token)) {
            return token;
        }
        return this.managementToken;
    }

    public boolean isRegistryEnabled() {
        return StringUtils.isNotBlank(this.storageAddress);
    }

    public String getStorageType() {
        return this.storageType;
    }

    public String getStorageAddress() {
        return this.storageAddress;
    }

    public String getStorageDatabase() {
        return this.storageDatabase;
    }

    public String getStorageUsername() {
        return this.storageUsername;
    }

    public String getStoragePassword() {
        return this.storagePassword;
    }

    public String getStorageManagementToken() {
        return this.storageManagementToken;
    }

    public String getStorageNamespace() {
        return this.storageNamespace;
    }

    public String getStorageGroup() {
        return this.storageGroup;
    }

    public String getStorageRoot() {
        return this.storageRoot;
    }

    public String getRpcUnit() {
        return this.rpcUnit;
    }

    public int getRpcPort() {
        return this.rpcPort;
    }

    public String getRpcHost() {
        return this.rpcHost;
    }
}
