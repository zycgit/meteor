/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.model.fo;
import java.net.URI;
import net.hasor.cobble.StringUtils;

/** 本地保存的连接定义，不是注册中心业务数据。 */
public final class ClusterProfile {
    private String id;
    private String name;
    private String type;
    private String address;
    private String database;
    private String username;
    private String password;
    private String managementToken;
    private String namespace;
    private String group;
    private String root;

    public String getId() {
        return this.id;
    }

    public void setId(String value) {
        this.id = value;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String value) {
        this.name = value;
    }

    public String getType() {
        return this.type;
    }

    public void setType(String value) {
        this.type = value;
    }

    public String getAddress() {
        return this.address;
    }

    public void setAddress(String value) {
        this.address = value;
    }

    public String getDatabase() {
        return this.database;
    }

    public void setDatabase(String value) {
        this.database = value;
    }

    public String getUsername() {
        return this.username;
    }

    public void setUsername(String value) {
        this.username = value;
    }

    public String getPassword() {
        return this.password;
    }

    public void setPassword(String value) {
        this.password = value;
    }

    public String getManagementToken() {
        return this.managementToken;
    }

    public void setManagementToken(String value) {
        this.managementToken = value;
    }

    public String getNamespace() {
        return this.namespace;
    }

    public void setNamespace(String value) {
        this.namespace = value;
    }

    public String getGroup() {
        return this.group;
    }

    public void setGroup(String value) {
        this.group = value;
    }

    public String getRoot() {
        return this.root;
    }

    public void setRoot(String value) {
        this.root = value;
    }

    public void validate() {
        if (StringUtils.isBlank(this.name) || StringUtils.isBlank(this.address)) {
            throw new IllegalArgumentException("连接名称和地址不能为空");
        }
        if (this.name.length() > 100 || this.address.length() > 2048) {
            throw new IllegalArgumentException("连接名称或地址过长");
        }
        if (this.type == null) {
            throw new IllegalArgumentException("请选择注册中心类型");
        }
        if (this.address.matches("(?i).*([?;&](password|pwd)=|://[^/]+@).*")) {
            throw new IllegalArgumentException("请通过独立密码字段提供凭据，不要写入连接地址");
        }
        if (StringUtils.equals("remote", this.type)) {
            URI uri;
            try {
                uri = URI.create(this.address);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Remote 地址格式不正确");
            }
            if (!StringUtils.equalsIgnoreCase(uri.getScheme(), "rsf") || uri.getHost() == null || uri.getPort() <= 0 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Remote 地址必须是 rsf://主机:端口/单元，且不能包含凭据");
            }
            if (StringUtils.isBlank(this.managementToken)) {
                throw new IllegalArgumentException("Remote 连接需要管理凭据");
            }
            if (this.managementToken.length() > 4096) {
                throw new IllegalArgumentException("管理凭据过长");
            }
            this.username = null;
            this.password = null;
            this.database = null;
            this.namespace = null;
            this.group = null;
            this.root = null;
        } else if (StringUtils.equals("jdbc", this.type)) {
            String prefix = switch (this.database == null ? "" : this.database) {
                case "h2" -> "jdbc:h2:";
                case "mysql" -> "jdbc:mysql:";
                case "postgresql" -> "jdbc:postgresql:";
                case "oracle" -> "jdbc:oracle:";
                case "sqlserver" -> "jdbc:sqlserver:";
                default -> throw new IllegalArgumentException("请选择数据库类型");
            };
            if (!this.address.startsWith(prefix)) {
                throw new IllegalArgumentException("JDBC URL 与所选数据库类型不符");
            }
        } else if (!StringUtils.equals("nacos", this.type) && !StringUtils.equals("zookeeper", this.type)) {
            throw new IllegalArgumentException("不支持的注册中心类型");
        }
        if (!StringUtils.equals("remote", this.type)) {
            this.managementToken = null;
        }
    }
}
