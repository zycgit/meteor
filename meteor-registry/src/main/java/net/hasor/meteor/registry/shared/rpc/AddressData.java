/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.shared.rpc;

public final class AddressData {
    private String[] available = new String[0];
    private String[] invalid   = new String[0];
    private String[] localUnit = new String[0];

    public String[] getAvailable() {
        return this.available;
    }

    public void setAvailable(String[] available) {
        this.available = available;
    }

    public String[] getInvalid() {
        return this.invalid;
    }

    public void setInvalid(String[] invalid) {
        this.invalid = invalid;
    }

    public String[] getLocalUnit() {
        return this.localUnit;
    }

    public void setLocalUnit(String[] localUnit) {
        this.localUnit = localUnit;
    }
}
