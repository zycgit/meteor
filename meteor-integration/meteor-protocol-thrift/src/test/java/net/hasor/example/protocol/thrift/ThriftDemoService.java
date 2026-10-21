/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift;
import net.hasor.example.protocol.thrift.generated.ExtendedInteropService;
import net.hasor.meteor.MetMessage;
import org.apache.thrift.TException;

/** 普通业务接口继承官方 IDL 契约；消息语义同时向 Meteor 显式声明。 */
public interface ThriftDemoService extends ExtendedInteropService.Iface {
    @Override
    @MetMessage
    void notify(String value) throws TException;
}
