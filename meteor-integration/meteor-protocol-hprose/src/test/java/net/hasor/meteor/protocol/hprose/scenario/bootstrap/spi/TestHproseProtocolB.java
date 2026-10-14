/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.bootstrap.spi;

/** 第二个 SPI 名称与第一个挂载共享真实 HTTP/Hprose 适配能力。 */
public final class TestHproseProtocolB extends TestHproseProtocolA {
    @Override
    public String name() {
        return "hproseb";
    }
}
