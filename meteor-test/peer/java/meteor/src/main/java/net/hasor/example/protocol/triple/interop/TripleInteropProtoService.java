/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple.interop;
import java.util.concurrent.Flow;

/** String 值通过标准 StringValue 描述与官方 Protobuf 客户端互通。 */
public interface TripleInteropProtoService {
    String echo(String text);

    String fail(String text);

    String metadata(String text);

    String slow(String text);

    Flow.Publisher<String> count(String count);

    String collect(Flow.Publisher<String> values);

    Flow.Publisher<String> chat(Flow.Publisher<String> values);
}
