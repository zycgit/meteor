/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;
import jakarta.jws.WebService;

/** 由真实 Metro Endpoint 接收并返回 MTOM 二进制参数。 */
@WebService(endpointInterface = "net.hasor.meteor.protocol.soap.scenario.example.MetroBinaryService", //
        targetNamespace = "urn:hasor:meteor:soap:BinaryService", //
        serviceName = "BinaryService", portName = "BinaryServicePort")
public class MetroBinaryServiceImpl implements MetroBinaryService {
    @Override
    public byte[] echo(byte[] data) {
        return data;
    }
}
