/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 服务成员摘要；允许最终一致，仅用于展示，不能作为服务发现的依据。 */
public class ServiceSummary {
    private int      providerCount;
    private int      consumerCount;
    private String[] protocols = new String[0];

    public int getProviderCount() {
        return this.providerCount;
    }

    public void setProviderCount(int providerCount) {
        this.providerCount = providerCount;
    }

    public int getConsumerCount() {
        return this.consumerCount;
    }

    public void setConsumerCount(int consumerCount) {
        this.consumerCount = consumerCount;
    }

    public String[] getProtocols() {
        return this.protocols;
    }

    public void setProtocols(String[] protocols) {
        this.protocols = protocols;
    }
}
