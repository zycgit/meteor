/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address.provider;
import net.hasor.rsf.address.AddressPool;
import net.hasor.rsf.address.InterAddress;

/**
 *
 * @version : 2015年12月18日
 * @author 赵永春 (zyc@hasor.net)
 */
public class PoolAddressProvider implements AddressProvider {
    private final AddressPool pool;

    public PoolAddressProvider(AddressPool pool) {
        this.pool = pool;
    }

    @Override
    public InterAddress get(String serviceID, String methodName, Object[] args) {
        return this.pool.nextAddress(serviceID, methodName, args);
    }

    @Override
    public boolean isDistributed() {
        return true;
    }

    @Override
    public String toString() {
        return "AddressProvider[" + this.pool.toString() + "]";
    }
}