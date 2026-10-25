/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.dubbo.interop;
import java.util.List;
import java.util.Map;

/** Meteor 和独立官方 Dubbo 进程共同使用的普通 Java 业务接口。 */
public interface DubboInteropService {
    String echo(String value);

    String combine(String value, int count);

    String overloaded(String value);

    String overloaded(int value);

    String noArgs();

    byte[] binary(byte[] value);

    DubboInteropPerson person(DubboInteropPerson value);

    List<DubboInteropPerson> people(List<DubboInteropPerson> values);

    Map<String, Object> properties(Map<String, Object> values);

    String nullable(String value);

    void nothing();

    String fail(String value);

    String metadata(String value);

    String slow(String value);

    void notice(String value);

    int notices();
}
