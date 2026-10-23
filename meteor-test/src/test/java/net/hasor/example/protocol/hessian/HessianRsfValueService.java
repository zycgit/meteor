/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;

/** Hessian 在 RSF 通道中编码基础业务参数。 */
public interface HessianRsfValueService {
    String echo(String value);

    int add(int left, int right);

    int[] array(int[] values);
}
