/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.xmlrpc;
import java.util.*;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;

/** 所有 XML-RPC 示例共用的业务实现。 */
public class XmlRpcDemoServiceImpl implements XmlRpcDemoService {
    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public int subtract(int left, int right) {
        return left - right;
    }

    @Override
    public boolean negate(boolean value) {
        return !value;
    }

    @Override
    public double half(double value) {
        return value / 2;
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
    public List<XmlRpcPerson> people(List<XmlRpcPerson> values) {
        return new ArrayList<>(values);
    }

    @Override
    public Map<String, Object> struct(Map<String, Object> value) {
        return new LinkedHashMap<>(value);
    }

    @Override
    public String fail(String message) {
        throw new XmlRpcException(42, message);
    }

    @Override
    public String current() {
        return "ready";
    }
}
