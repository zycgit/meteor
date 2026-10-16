/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

import java.util.concurrent.atomic.AtomicReference;

/** 原样返回普通对象，同时记录实际解码后的属性。 */
public final class ProfileServiceImpl implements ProfileService {
    private final AtomicReference<Profile> received = new AtomicReference<>();

    @Override
    public Profile roundTrip(Profile profile) {
        this.received.set(profile);
        return profile;
    }

    public Profile getReceived() {
        return this.received.get();
    }
}
