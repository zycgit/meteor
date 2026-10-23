/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.concurrent.Flow;

/** 两个 Java 签名不同，但 wrapper 都只能看到单个 String 消息。 */
public class TripleAmbiguousServiceImpl implements TripleAmbiguousService {
    public String exchange(String value) {
        return value;
    }

    public String exchange(Flow.Publisher<String> values) {
        return "stream";
    }
}
