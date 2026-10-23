/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 与 Meteor、Caucho 均无耦合的普通服务实现。 */
public class HessianInteropDemoImpl implements HessianInteropDemo {
    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public String overloaded() {
        return "zero-args";
    }

    @Override
    public String overloaded(String value) {
        return "string:" + value;
    }

    @Override
    public String overloaded(int value) {
        return "int:" + value;
    }

    @Override
    public HessianInteropPerson person(HessianInteropPerson value) {
        return value;
    }

    @Override
    public String nullable(String value) {
        return value;
    }

    @Override
    public byte[] binary(byte[] value) {
        return value;
    }

    @Override
    public List<HessianInteropPerson> collection(List<HessianInteropPerson> value) {
        return new ArrayList<>(value);
    }

    @Override
    public Map<String, Object> structure(Map<String, Object> value) {
        return new LinkedHashMap<>(value);
    }

    @Override
    public String fail(String message) throws HessianInteropException {
        throw new HessianInteropException(message);
    }

    @Override
    public String failIo(String message) throws IOException {
        throw new IOException(message);
    }
}
