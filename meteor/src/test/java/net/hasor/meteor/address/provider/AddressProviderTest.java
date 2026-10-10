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
import net.hasor.meteor.address.dsl.RouteContext;
import org.junit.Test;
import static org.junit.Assert.*;

public class AddressProviderTest {
    @Test
    public void fixedProviderKeepsItsAddressAcrossServices() throws Exception {
        InterAddress address = new InterAddress("rsf://127.0.0.1:2181/default");
        AddressProvider provider = new InstanceAddressProvider(address);
        assertSame(address, provider.get(new RouteContext("first", "echo", new Object[] { "a" })));
        assertSame(address, provider.get(new RouteContext("second", "other", null)));
        assertFalse(provider.isDistributed());
    }

    @Test
    public void poolProviderUsesXmlRoutesAndTracksAddressRemoval() throws Exception {
        AddressPool pool = new AddressPool();
        InterAddress first = new InterAddress("rsf://127.0.0.1:2181/default");
        InterAddress second = new InterAddress("rsf://127.0.0.1:2182/default");
        pool.appendAddress("service", Arrays.asList(first, second));
        AddressProvider provider = new PoolAddressProvider(pool);
        assertTrue(provider.isDistributed());
        String serviceScript = "<route><filter name='selected' from='addresses' item='a' test='a.port == 2182'/><return value='selected'/></route>";
        assertTrue(pool.updateServiceRoute("service", serviceScript));
        assertEquals(second, provider.get(new RouteContext("service", "echo", new Object[] { "value" })));
        assertTrue(pool.updateServiceRoute("service", ""));
        pool.removeAddress("service", second);
        assertEquals(first, provider.get(new RouteContext("service", "echo", null)));
        pool.removeAddress("service", first);
        assertNull(provider.get(new RouteContext("service", "echo", null)));
    }
}
