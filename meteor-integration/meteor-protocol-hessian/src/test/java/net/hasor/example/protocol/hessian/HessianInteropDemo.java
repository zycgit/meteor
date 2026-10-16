/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hessian;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** 双向互通使用同一普通业务接口，客户端与服务端分别来自不同实现。 */
public interface HessianInteropDemo {
    String echo(String value);

    String overloaded();

    String overloaded(String value);

    String overloaded(int value);

    HessianInteropPerson person(HessianInteropPerson value);

    String nullable(String value);

    byte[] binary(byte[] value);

    List<HessianInteropPerson> collection(List<HessianInteropPerson> value);

    Map<String, Object> structure(Map<String, Object> value);

    String fail(String message) throws HessianInteropException;

    String failIo(String message) throws IOException;
}
