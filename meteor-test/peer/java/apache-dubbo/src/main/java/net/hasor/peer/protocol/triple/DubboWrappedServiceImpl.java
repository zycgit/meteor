/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.triple;
import net.hasor.example.protocol.triple.interop.TripleInteropPerson;
import org.apache.dubbo.rpc.RpcContext;

/** 由 Apache Dubbo 发布，避免复用 Meteor 的协议实现。 */
public final class DubboWrappedServiceImpl implements DubboWrappedService {
    @Override
    public String echo(String value) {
        return value;
    }

    @Override
    public String combine(String value, int count) {
        return value + ":" + count;
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
    public String noArgs() {
        return "empty";
    }

    @Override
    public byte[] binary(byte[] value) {
        return value;
    }

    @Override
    public TripleInteropPerson person(TripleInteropPerson value) {
        return new TripleInteropPerson(value.getName(), value.getAge() + 1);
    }

    @Override
    public String nullable(String value) {
        return value;
    }

    @Override
    public void nothing() {
    }

    @Override
    public String fail(String value) {
        throw new IllegalArgumentException("triple failure " + value);
    }

    @Override
    public String metadata(String value) {
        String metadata = RpcContext.getServerAttachment().getAttachment("x-triple-test");
        RpcContext.getServerResponseContext().setAttachment("x-triple-test", metadata);
        return value;
    }

    @Override
    public String slow(String value) {
        // 延迟由服务方法产生，让客户端实际经过 Triple 的调用超时链路。
        try {
            Thread.sleep(1000);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
        return value;
    }
}
