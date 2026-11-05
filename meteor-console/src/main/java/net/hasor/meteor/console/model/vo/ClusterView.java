/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.model.vo;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.model.fo.ClusterProfile;

/** 浏览器可见的连接信息；不包含保存的密码和管理凭据。 */
public final class ClusterView {
    private final String  id;
    private final String  name;
    private final String  type;
    private final String  address;
    private final String  database;
    private final String  username;
    private final String  namespace;
    private final String  group;
    private final String  root;
    private final boolean hasPassword;
    private final boolean hasManagementToken;

    public ClusterView(ClusterProfile profile) {
        this.id = profile.getId();
        this.name = profile.getName();
        this.type = profile.getType();
        this.address = profile.getAddress();
        this.database = profile.getDatabase();
        this.username = profile.getUsername();
        this.namespace = profile.getNamespace();
        this.group = profile.getGroup();
        this.root = profile.getRoot();
        this.hasPassword = StringUtils.isNotBlank(profile.getPassword());
        this.hasManagementToken = StringUtils.equals(profile.getType(), "remote") && StringUtils.isNotBlank(profile.getManagementToken());
    }

    public String getId() {
        return this.id;
    }

    public String getName() {
        return this.name;
    }

    public String getType() {
        return this.type;
    }

    public String getAddress() {
        return this.address;
    }

    public String getDatabase() {
        return this.database;
    }

    public String getUsername() {
        return this.username;
    }

    public String getNamespace() {
        return this.namespace;
    }

    public String getGroup() {
        return this.group;
    }

    public String getRoot() {
        return this.root;
    }

    public boolean isHasPassword() {
        return this.hasPassword;
    }

    public boolean isHasManagementToken() {
        return this.hasManagementToken;
    }
}
