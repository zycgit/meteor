/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple;
import java.util.concurrent.Flow;

/** 同一 Java 接口同时演示普通调用与三种流式调用。 */
public interface TripleFlowService {
    TripleMessage echo(TripleMessage request);

    TripleMessage safeEcho(TripleMessage request);

    TripleMessage slow(TripleMessage request);

    TripleMessage fail(TripleMessage request);

    Flow.Publisher<TripleMessage> count(TripleMessage request);

    TripleMessage collect(Flow.Publisher<TripleMessage> requests);

    Flow.Publisher<TripleMessage> chat(Flow.Publisher<TripleMessage> requests);

    Flow.Publisher<TripleMessage> never(TripleMessage request);

    Flow.Publisher<TripleMessage> broken(TripleMessage request);
}
