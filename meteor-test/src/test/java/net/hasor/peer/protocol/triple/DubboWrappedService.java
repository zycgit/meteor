/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.triple;
import net.hasor.example.protocol.triple.interop.TripleInteropPerson;

/** 官方 Dubbo 对端使用的普通 Java 服务契约。 */
public interface DubboWrappedService {
    String echo(String value);

    String combine(String value, int count);

    String overloaded(String value);

    String overloaded(int value);

    String noArgs();

    byte[] binary(byte[] value);

    TripleInteropPerson person(TripleInteropPerson value);

    String nullable(String value);

    void nothing();

    String fail(String value);

    String metadata(String value);

    String slow(String value);
}
