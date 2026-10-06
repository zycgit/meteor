/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.provider;
import net.hasor.meteor.address.InterAddress;

/**
 *
 * @version : 2015年12月18日
 * @author 赵永春 (zyc@hasor.net)
 */
public class InstanceAddressProvider implements AddressProvider {
    private final InterAddress interAddress;

    public InstanceAddressProvider(InterAddress interAddress) {
        this.interAddress = interAddress;
    }

    @Override
    public InterAddress get(String serviceID, String methodName, Object[] args) {
        return this.interAddress;
    }

    @Override
    public boolean isDistributed() {
        return false;
    }

    @Override
    public String toString() {
        return "AddressProvider[" + this.interAddress.toString() + "]";
    }
}