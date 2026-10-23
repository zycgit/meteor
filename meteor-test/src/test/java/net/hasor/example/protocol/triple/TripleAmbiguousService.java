/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.concurrent.Flow;

/** 这组声明的线协议签名相同，不能作为 Triple 服务发布。 */
public interface TripleAmbiguousService {
    String exchange(String value);

    String exchange(Flow.Publisher<String> values);
}
