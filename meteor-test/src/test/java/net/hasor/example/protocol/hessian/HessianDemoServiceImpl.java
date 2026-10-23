/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;
import java.util.*;

/** 共享业务实现，协议负责 Hessian 参数与结果转换。 */
public class HessianDemoServiceImpl implements HessianDemoService {
    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int number(int value) {
        return value;
    }

    @Override
    public long longValue(long value) {
        return value;
    }

    @Override
    public double decimal(double value) {
        return value;
    }

    @Override
    public boolean enabled(boolean value) {
        return value;
    }

    @Override
    public byte[] binary(byte[] value) {
        return value;
    }

    @Override
    public Date date(Date value) {
        return value;
    }

    @Override
    public List<HessianPerson> people(List<HessianPerson> values) {
        return new ArrayList<>(values);
    }

    @Override
    public Map<String, Object> struct(Map<String, Object> values) {
        return new LinkedHashMap<>(values);
    }

    @Override
    public String nullable(String value) {
        return value;
    }

    @Override
    public String overloaded(String value) {
        return "text:" + value;
    }

    @Override
    public String overloaded(int value) {
        return "number:" + value;
    }

    @Override
    public String fail(String message) {
        throw new IllegalArgumentException(message);
    }

    @Override
    public String current() {
        return "ready";
    }
}
