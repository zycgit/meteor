/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;

public class HessianRsfValueServiceImpl implements HessianRsfValueService {
    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int add(int left, int right) {
        return left + right;
    }

    @Override
    public int[] array(int[] values) {
        return values;
    }
}
