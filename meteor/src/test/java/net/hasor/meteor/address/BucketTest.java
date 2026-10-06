/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class BucketTest {
    private final InterAddress a = new InterAddress("127.0.0.1", 8000, "local");
    private final InterAddress b = new InterAddress("127.0.0.2", 8000, "remote");

    private AddressBucket bucket() {
        AddressBucket bucket = new AddressBucket("s", "local");
        bucket.newAddress(Arrays.asList(this.a, this.b), AddressTypeEnum.Dynamic);
        return bucket;
    }

    @Test
    public void duplicateAppendAcrossCallsIsIdempotent() {
        AddressBucket bucket = bucket();
        bucket.newAddress(Collections.singletonList(this.a), AddressTypeEnum.Dynamic);
        assertEquals(Arrays.asList(this.a, this.b), bucket.getAllAddresses());
    }

    @Test
    public void duplicateAddressesInSingleBatchAreDeduplicated() {
        AddressBucket bucket = new AddressBucket("s", "local");
        bucket.newAddress(Arrays.asList(this.a, this.a), AddressTypeEnum.Dynamic);
        assertEquals(Collections.singletonList(this.a), bucket.getAllAddresses());
    }

    @Test
    public void dynamicProviderCanBePromotedToStatic() {
        AddressBucket bucket = bucket();
        bucket.newAddress(Collections.singletonList(this.a), AddressTypeEnum.Static);
        bucket.invalidAddress(this.a, 60000);
        assertTrue("Promoted static provider must remain selectable", bucket.getAvailableAddresses().contains(this.a));
        assertEquals(Collections.singletonList(this.a), bucket.getStaticAddresses());
    }

    @Test
    public void unknownInvalidationDoesNotCreatePhantomAddress() {
        AddressBucket bucket = new AddressBucket("s", "local");
        bucket.invalidAddress(this.a, 60000);
        bucket.removeAddress(this.a);
        assertTrue(bucket.getInvalidAddresses().isEmpty());
        assertTrue(bucket.getAllAddresses().isEmpty());
    }

    @Test
    public void expiredInvalidationAllowsRetryAndRepeatedFailureDisablesAgain() {
        AddressBucket bucket = bucket();
        bucket.invalidAddress(this.a, -1);
        bucket.refreshAddress();
        assertTrue(bucket.getAvailableAddresses().contains(this.a));
        bucket.invalidAddress(this.a, 60000);
        bucket.refreshAddress();
        assertFalse(bucket.getAvailableAddresses().contains(this.a));
        bucket.newAddress(Collections.singletonList(this.a), AddressTypeEnum.Dynamic);
        assertFalse(bucket.getInvalidAddresses().contains(this.a));
        assertTrue(bucket.getAvailableAddresses().contains(this.a));
    }

    @Test
    public void repeatedFailureImmediatelyRemovesRetriedProvider() {
        AddressBucket bucket = bucket();
        bucket.invalidAddress(this.a, -1);
        bucket.refreshAddress();
        assertTrue(bucket.getAvailableAddresses().contains(this.a));
        bucket.invalidAddress(this.a, 60000);
        assertFalse("Direct bucket API must update availability after a failed retry", bucket.getAvailableAddresses().contains(this.a));
    }

    @Test
    public void explicitRemovalClearsInvalidAndStaticState() {
        AddressBucket bucket = bucket();
        bucket.invalidAddress(this.a, 60000);
        bucket.removeAddress(this.a);
        assertFalse(bucket.getAllAddresses().contains(this.a));
        assertFalse(bucket.getInvalidAddresses().contains(this.a));
        assertEquals(Collections.singletonList(this.b), bucket.getAvailableAddresses());
    }

    @Test
    public void bucketAddressGettersReturnDetachedCopies() {
        AddressBucket bucket = bucket();
        bucket.getAllAddresses().clear();
        bucket.getAvailableAddresses().clear();
        bucket.getInvalidAddresses().add(this.a);
        bucket.getStaticAddresses().add(this.a);
        assertEquals(2, bucket.getAllAddresses().size());
        assertEquals(2, bucket.getAvailableAddresses().size());
        assertTrue(bucket.getInvalidAddresses().isEmpty());
        assertTrue(bucket.getStaticAddresses().isEmpty());
    }

    @Test
    public void localUnitGetterDoesNotExposeMutableState() {
        AddressBucket bucket = bucket();
        bucket.getLocalUnitAddresses().clear();
        assertEquals(2, bucket.getAvailableAddresses().size());
        assertEquals(2, bucket.getLocalUnitAddresses().size());
    }

    @Test
    public void addressChangesNotifyRegisteredObservers() {
        AddressBucket bucket = bucket();
        AtomicInteger notifications = new AtomicInteger();
        bucket.addObserver((source, value) -> notifications.incrementAndGet());
        bucket.removeAddress(this.a);
        assertTrue("AddressBucket advertises Observable changes", notifications.get() > 0);
    }
}
