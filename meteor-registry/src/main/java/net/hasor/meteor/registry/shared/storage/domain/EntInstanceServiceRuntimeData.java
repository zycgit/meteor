/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.storage.domain;

/** 实例下 SERVICE/RUNTIME 存储正文；实例和服务 ID 由 EntityRef 提供。 */
public final class EntInstanceServiceRuntimeData extends EntData {
    private EntServiceDeclarationData service     = null; // 服务声明
    private boolean                   enabled     = true; // 已上报的服务启用状态
    private EntAddressBookData        addressBook = new EntAddressBookData(); // 已上报的本地地址本

    public EntServiceDeclarationData getService() {
        return this.service;
    }

    public void setService(EntServiceDeclarationData service) {
        this.service = service;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public EntAddressBookData getAddressBook() {
        return this.addressBook;
    }

    public void setAddressBook(EntAddressBookData addressBook) {
        this.addressBook = addressBook;
    }
}
