/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose.bootstrap.spi;
/** A second protocol identity reusing the same HTTP/Hprose adapter. */
public final class TestHproseProtocolB extends TestHproseProtocolA {
    @Override
    public String name() {
        return "hproseb";
    }
}
