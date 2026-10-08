/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.util.LinkedHashMap;
import java.util.Map;
import net.hasor.meteor.registry.shared.rpc.AddressData;
import net.hasor.meteor.registry.shared.rpc.InstanceData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;

/** 一次本地采集结果；发布时拆为实例、实例服务和发现关系的独立记录。 */
final class RegistrationSnapshot {
    private InstanceData             instance     = null;
    private ServiceMemberData[]      services     = new ServiceMemberData[0];
    private Map<String, Object>      settings     = new LinkedHashMap<>();
    private Map<String, AddressData> addressBooks = new LinkedHashMap<>();

    public InstanceData getInstance() {
        return this.instance;
    }

    public void setInstance(InstanceData instance) {
        this.instance = instance;
    }

    public ServiceMemberData[] getServices() {
        return this.services;
    }

    public void setServices(ServiceMemberData[] services) {
        this.services = services;
    }

    public Map<String, Object> getSettings() {
        return this.settings;
    }

    public void setSettings(Map<String, Object> settings) {
        this.settings = settings;
    }

    public Map<String, AddressData> getAddressBooks() {
        return this.addressBooks;
    }

    public void setAddressBooks(Map<String, AddressData> addressBooks) {
        this.addressBooks = addressBooks;
    }

}
