/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.List;

/** 普通 Java 接口直接发布，无需提供 proto 或生成客户端代码。 */
public interface TripleDemoService {
    TripleMessage echo(TripleMessage request);

    String greet(String name, int times);

    String overloaded(String value);

    String overloaded(int value);

    List<TripleMessage> list(List<TripleMessage> requests);

    void accept(TripleMessage request);

    TripleMessage fail(TripleMessage request);

    TripleMessage slow(TripleMessage request);
}
