/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple.interop;

/** 无需 IDL 即可发布的多参数、重载及普通对象接口。 */
public interface TripleInteropWrappedService {
    String echo(String text);

    String combine(String text, int number);

    String overloaded(String text);

    String overloaded(int number);

    String noArgs();

    byte[] binary(byte[] bytes);

    TripleInteropPerson person(TripleInteropPerson person);

    String nullable(String text);

    void nothing();

    String fail(String text);

    String metadata(String text);

    String slow(String text);
}
