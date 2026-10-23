/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 普通 Java 接口直接发布为 Hessian 服务，无需绑定 IDL。 */
public interface HessianDemoService {
    String echo(String value);

    int number(int value);

    long longValue(long value);

    double decimal(double value);

    boolean enabled(boolean value);

    byte[] binary(byte[] value);

    Date date(Date value);

    List<HessianPerson> people(List<HessianPerson> values);

    Map<String, Object> struct(Map<String, Object> values);

    String nullable(String value);

    String overloaded(String value);

    String overloaded(int value);

    String fail(String message);

    String current();
}
