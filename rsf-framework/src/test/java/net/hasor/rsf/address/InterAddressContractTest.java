/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class InterAddressContractTest {
    @Test
    public void canonicalUriAndSocketRoundTrip() throws Exception {
        InterAddress address = new InterAddress("RSF://127.0.0.1:8000/zone");
        assertEquals("rsf", address.getSchema());
        assertEquals("zone", address.getFormUnit());
        assertEquals("127.0.0.1", address.getHost());
        assertEquals(8000, address.getPort());
        assertEquals("127.0.0.1:8000", address.getHostPort());
        assertEquals(address, new InterAddress(address.toURI()));
        assertEquals(8000, address.toSocketAddress().getPort());
        assertTrue(address.equalsHost("127.0.0.1:8000"));
        assertTrue(address.equalsHost(new InterAddress("http", "127.0.0.1", 8000, "other")));
        assertFalse(address.equalsHost((String) null));
        assertFalse(address.equalsHost((InterAddress) null));
        assertNotEquals(null, address);
        assertNotEquals(address, address.toString());
        assertNotEquals(address, new InterAddress("http", "127.0.0.1", 8000, "zone"));
    }

    @Test
    public void caseInsensitiveEqualityWorksAsMapKey() throws Exception {
        InterAddress upper = new InterAddress("rsf://LOCALHOST:8000/ZONE");
        InterAddress lower = new InterAddress("rsf://localhost:8000/zone");
        assertEquals(upper, lower);
        Map<InterAddress, String> map = new HashMap<>();
        map.put(upper, "provider");
        assertEquals("Equal addresses must resolve the same hash entry", "provider", map.get(lower));
        assertEquals(upper.hashCode(), lower.hashCode());
    }

    @Test
    public void ipv6UriRoundTrip() throws Exception {
        InterAddress address = new InterAddress("rsf://[::1]:8000/zone");
        assertEquals(address, new InterAddress(address.toURI()));
        assertEquals(8000, address.toSocketAddress().getPort());
    }

    @Test
    public void ipv6ComponentConstructorProducesParseableAddress() throws Exception {
        InterAddress address = new InterAddress("rsf", "::1", 8000, "zone");
        assertEquals(new InterAddress(address.toURI()), new InterAddress(address.toHostSchema()));
    }

    @Test
    public void absentUriAndHostAreRejected() {
        assertFalse(InterAddress.checkFormat(null));
        assertFalse(InterAddress.checkFormat(URI.create("rsf:/zone")));
        assertFalse(InterAddress.checkFormat(URI.create("rsf://127.0.0.1:0/zone")));
    }

    @Test
    public void localEndpointCanRequestAnEphemeralPortWithoutRelaxingServiceAddresses() {
        InterAddress local = InterAddress.forBinding("tcp", "127.0.0.1", 0, "default");
        assertEquals(0, local.getPort());
        assertEquals("tcp://127.0.0.1:0/default", local.toHostSchema());
        assertFalse(InterAddress.checkFormat(URI.create(local.toHostSchema())));
    }

    @Test
    public void missingPortIsRejected() {
        assertFalse(InterAddress.checkFormat(URI.create("rsf://127.0.0.1/zone")));
    }

    @Test
    public void outOfRangePortIsRejected() {
        assertFalse(InterAddress.checkFormat(URI.create("rsf://127.0.0.1:65536/zone")));
    }

    @Test
    public void missingSchemeIsRejected() {
        assertFalse(InterAddress.checkFormat(URI.create("//127.0.0.1:8000/zone")));
    }

    @Test
    public void malformedUnitReturnsFalseInsteadOfThrowing() {
        assertFalse(InterAddress.checkFormat(URI.create("rsf://127.0.0.1:8000/")));
    }

    @Test
    public void invalidUnitSuffixIsNotAcceptedByPartialMatch() {
        assertFalse(InterAddress.checkFormat(URI.create("rsf://127.0.0.1:8000/zone!")));
    }
}
