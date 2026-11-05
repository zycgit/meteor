/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.model.fo;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.registry.admin.MetDirectoryQuery;

/** 管理查询或写入的目标范围，与 HTTP 传输无关。 */
public final class ManagementTarget {
    private boolean      direct;
    private String       clusterId;
    private String       instanceId;
    private String       serviceId;
    private String       scope;
    private String       filter;
    private String       applicationName;
    private String       targetUnit;
    private String       localUnit;
    private InterAddress target;
    private String       managementToken;

    public String getManagementToken() {
        return this.managementToken;
    }

    public void setManagementToken(String managementToken) {
        this.managementToken = managementToken;
    }

    private String field;
    private String protocol;

    public String getField() {
        return this.field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getProtocol() {
        return this.protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    private String cursor;
    private String unitName;
    private int    limit = 100;

    public String getCursor() {
        return this.cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
    }

    public String getUnitName() {
        return this.unitName;
    }

    public void setUnitName(String unitName) {
        this.unitName = unitName;
    }

    public int getLimit() {
        return this.limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public MetDirectoryQuery directoryQuery() {
        if (this.limit < 1 || this.limit > 1000) {
            throw new IllegalArgumentException("limit 必须为 1 到 1000");
        }
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setSearch(this.filter);
        query.setField(this.field);
        query.setProtocol(this.protocol);
        query.setUnitName(this.unitName);
        query.setCursor(this.cursor);
        query.setLimit(this.limit);
        return query;
    }

    public boolean isDirect() {
        return this.direct;
    }

    public void setDirect(boolean value) {
        this.direct = value;
    }

    public String getClusterId() {
        return this.clusterId;
    }

    public void setClusterId(String value) {
        this.clusterId = value;
    }

    public String getInstanceId() {
        return this.instanceId;
    }

    public void setInstanceId(String value) {
        this.instanceId = value;
    }

    public String getServiceId() {
        return this.serviceId;
    }

    public void setServiceId(String value) {
        this.serviceId = value;
    }

    public String getScope() {
        return this.scope;
    }

    public void setScope(String value) {
        this.scope = value;
    }

    public String getFilter() {
        return this.filter;
    }

    public void setFilter(String value) {
        this.filter = value;
    }

    public String getApplicationName() {
        return this.applicationName;
    }

    public void setApplicationName(String value) {
        this.applicationName = value;
    }

    public String getTargetUnit() {
        return this.targetUnit;
    }

    public void setTargetUnit(String value) {
        this.targetUnit = value;
    }

    public String getLocalUnit() {
        return this.localUnit;
    }

    public void setLocalUnit(String value) {
        this.localUnit = value;
    }

    public InterAddress getTarget() {
        return this.target;
    }

    public void setTarget(InterAddress value) {
        this.target = value;
    }

    public String requireClusterId() {
        if (StringUtils.isBlank(this.clusterId)) {
            throw new IllegalArgumentException("缺少参数：clusterId");
        }
        return this.clusterId;
    }

    public String requireInstanceId() {
        if (StringUtils.isBlank(this.instanceId)) {
            throw new IllegalArgumentException("缺少参数：instanceId");
        }
        return this.instanceId;
    }

    public String requireServiceId() {
        if (StringUtils.isBlank(this.serviceId)) {
            throw new IllegalArgumentException("缺少参数：serviceId");
        }
        return this.serviceId;
    }

    public String requireScope() {
        if (StringUtils.isBlank(this.scope)) {
            throw new IllegalArgumentException("缺少参数：scope");
        }
        return this.scope;
    }

    public ManagementTarget atInstance(String id) {
        ManagementTarget copy = new ManagementTarget();
        copy.direct = this.direct;
        copy.clusterId = this.clusterId;
        copy.instanceId = id;
        copy.serviceId = this.serviceId;
        copy.scope = this.scope;
        copy.filter = this.filter;
        copy.applicationName = this.applicationName;
        copy.localUnit = this.localUnit;
        copy.target = this.target;
        copy.managementToken = this.managementToken;
        return copy;
    }
}
