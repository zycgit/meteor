/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.xmlrpc;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** 普通 Java 接口以 XML-RPC 服务别名发布，无需定义 XML 消息类。 */
public interface XmlRpcDemoService {
    String echo(String value);

    int subtract(int left, int right);

    boolean negate(boolean value);

    double half(double value);

    byte[] binary(byte[] value);

    Date date(Date value);

    List<XmlRpcPerson> people(List<XmlRpcPerson> values);

    Map<String, Object> struct(Map<String, Object> value);

    String fail(String message);

    String current();
}
