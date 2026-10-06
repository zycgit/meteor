/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.provider;
import java.util.Arrays;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.InterAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddressProviderTest {
    @Test
    public void fixedProviderKeepsItsAddressAcrossServices() throws Exception {
        InterAddress address = new InterAddress("rsf://127.0.0.1:2181/default");
        AddressProvider provider = new InstanceAddressProvider(address);
        assertSame(address, provider.get("first", "echo", new Object[] { "a" }));
        assertSame(address, provider.get("second", "other", null));
        assertFalse(provider.isDistributed());
    }

    @Test
    public void poolProviderUsesGroovyRoutesAndTracksAddressRemoval() throws Exception {
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2181/default");
        InterAddress second = new InterAddress("rsf://127.0.0.1:2182/default");
        pool.appendAddress("service", Arrays.asList(first, second));
        AddressProvider provider = new PoolAddressProvider(pool);
        assertTrue(provider.isDistributed());
        assertTrue(pool.updateServiceRoute("service", "def evalAddress(serviceID, addresses) { addresses.findAll { it.endsWith(':2182') } }"));
        assertEquals(second, provider.get("service", "echo", new Object[] { "value" }));
        assertTrue(pool.updateServiceRoute("service", ""));
        pool.removeAddress("service", second);
        assertEquals(first, provider.get("service", "echo", null));
        pool.removeAddress("service", first);
        assertNull(provider.get("service", "echo", null));
    }
}
