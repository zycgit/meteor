/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.example;

public interface Echo {
    String echo(String value);

    int add(int left, int right);

    String fail();

    String slow(int millis) throws InterruptedException;

    int[] array(int[] values);
}
