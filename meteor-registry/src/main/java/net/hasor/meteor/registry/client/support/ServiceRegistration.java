/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceCatalogData;
import net.hasor.meteor.registry.shared.storage.domain.RefServiceRelationData;

/** 本机服务登记：共享的服务目录和唯一的发布或订阅关系。 */
final class ServiceRegistration {
    private final String                 serviceId;
    private final EntServiceCatalogData  service;
    private final MetServiceType         role;
    private final RefServiceRelationData relation;

    public ServiceRegistration(String serviceId, EntServiceCatalogData service, MetServiceType role, RefServiceRelationData relation) {
        this.serviceId = serviceId;
        this.service = service;
        this.role = role;
        this.relation = relation;
    }

    public String getServiceId() {
        return this.serviceId;
    }

    public EntServiceCatalogData getService() {
        return this.service;
    }

    public MetServiceType getRole() {
        return this.role;
    }

    public RefServiceRelationData getRelation() {
        return this.relation;
    }
}
