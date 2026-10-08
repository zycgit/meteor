/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** PROVIDES/CONSUMES 关系正文：协议端点与可发现的发布地址。 */
public final class RefServiceRelationData extends RefData {
    private RefProtocolMountData[] endpoints = new RefProtocolMountData[0];
    private String[]               addresses = new String[0];

    public RefProtocolMountData[] getEndpoints() {
        return this.endpoints;
    }

    public void setEndpoints(RefProtocolMountData[] value) {
        this.endpoints = value;
    }

    public String[] getAddresses() {
        return this.addresses;
    }

    public void setAddresses(String[] value) {
        this.addresses = value;
    }
}
