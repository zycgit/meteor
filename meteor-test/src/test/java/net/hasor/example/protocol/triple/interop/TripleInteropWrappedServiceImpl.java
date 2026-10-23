/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple.interop;

/** 互通示例的业务实现不依赖对端 Dubbo API。 */
public class TripleInteropWrappedServiceImpl implements TripleInteropWrappedService {
    public String echo(String text) {
        return text;
    }

    public String combine(String text, int number) {
        return text + ":" + number;
    }

    public String overloaded(String text) {
        return "string:" + text;
    }

    public String overloaded(int number) {
        return "int:" + number;
    }

    public String noArgs() {
        return "empty";
    }

    public byte[] binary(byte[] bytes) {
        return bytes;
    }

    public TripleInteropPerson person(TripleInteropPerson person) {
        return new TripleInteropPerson(person.getName(), person.getAge() + 1);
    }

    public String nullable(String text) {
        return text;
    }

    public void nothing() {
    }

    public String fail(String text) {
        throw new IllegalArgumentException("triple failure " + text);
    }

    public String metadata(String text) {
        return text;
    }

    public String slow(String text) {
        // 业务等待保持有界，让调用端独立验证 RPC 期限。
        try {
            Thread.sleep(500);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
        return text;
    }
}
