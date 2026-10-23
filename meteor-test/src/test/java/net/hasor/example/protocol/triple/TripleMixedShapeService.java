/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.concurrent.Flow;

/** 同名方法可具有不同流形状，前提是 wrapper 的消息类型可以区分它们。 */
public interface TripleMixedShapeService {
    String source(int value);

    Flow.Publisher<String> source(String prefix);

    String sink(int value);

    String sink(Flow.Publisher<String> values);
}
