/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * @author 赵永春 (zyc@hasor.net)
 * @version : 2015年4月5日
 */
public class AddressUnitTest {
    @Test
    public void protocol() {
        ConcurrentMap<InterAddress, String> concurrentMap = new ConcurrentHashMap<>();
        concurrentMap.put(new InterAddress("127.0.0.1", 8000, "etc2"), "123");
        concurrentMap.put(new InterAddress("127.0.0.1", 8000, "etc2"), "123");

        assertEquals(1, concurrentMap.size());
    }

    @Test
    public void differentProtocolsRemainDistinctMapKeys() {
        ConcurrentMap<InterAddress, String> concurrentMap = new ConcurrentHashMap<>();
        concurrentMap.put(new InterAddress("http", "127.0.0.1", 8000, "etc2"), "123");
        concurrentMap.put(new InterAddress("127.0.0.1", 8000, "etc2"), "123");

        assertEquals(2, concurrentMap.size());
    }

    @Test
    public void unitAddress() throws URISyntaxException {
        InterAddress unit = new InterAddress("rsf://127.0.0.1:8000/unit");
        assertEquals("rsf://127.0.0.1:8000/unit", unit.toString());
    }

    @Test
    public void localAddress() throws URISyntaxException {
        InterAddress unit = new InterAddress("rsf://local:8000/unit");
        assertEquals("rsf://local:8000/unit", unit.toString());
        assertNotNull(unit.getHost());
        assertFalse(unit.getHost().isEmpty());
        assertNotEquals("local", unit.getHost());
    }

    @Test
    public void ipAddress() throws URISyntaxException {
        InterAddress interAddress1 = new InterAddress("127.0.0.1", 8000, "etc2");
        InterAddress interAddress2 = new InterAddress("127.0.0.1", 8000, "etc2");
        InterAddress interAddress3 = new InterAddress("rsf://127.0.0.1:8000/etc2");
        InterAddress interAddress4 = new InterAddress("RSF://127.0.0.1:8000/etc2");
        boolean eq1 = interAddress1.equals(interAddress2);
        boolean eq2 = interAddress1.equals(interAddress3);
        boolean eq3 = interAddress1.equals(interAddress4);

        assertTrue(eq1 && eq2 && eq3);
    }
}
