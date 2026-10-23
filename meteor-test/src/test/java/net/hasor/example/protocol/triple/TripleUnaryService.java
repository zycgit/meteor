/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;

/** 只有普通调用的方法，供 HTTP unary 以及独立生产示例使用。 */
public interface TripleUnaryService {
    TripleMessage echo(TripleMessage request);

    TripleMessage slow(TripleMessage request);

    TripleMessage fail(TripleMessage request);
}
