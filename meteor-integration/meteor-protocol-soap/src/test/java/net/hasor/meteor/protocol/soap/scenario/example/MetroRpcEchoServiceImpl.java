/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import jakarta.jws.WebService;

/** 由 Metro Endpoint 发布的真实 RPC/literal 服务实现。 */
@WebService(endpointInterface = "net.hasor.meteor.protocol.soap.scenario.example.MetroRpcEchoService", //
        targetNamespace = "urn:hasor:meteor:soap:RpcEchoService", //
        serviceName = "RpcEchoService", portName = "RpcEchoServicePort")
public class MetroRpcEchoServiceImpl implements MetroRpcEchoService {
    @Override
    public String echo(String message) {
        return message;
    }
}
