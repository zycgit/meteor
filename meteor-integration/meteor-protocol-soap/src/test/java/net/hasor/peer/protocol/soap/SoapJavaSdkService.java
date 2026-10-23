/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.soap;
import jakarta.jws.WebService;
import net.hasor.example.protocol.soap.SoapJavaSdkEcho;

/** Metro 从标准 JAX-WS 注解创建的真实服务，不携带 Meteor 类型。 */
@WebService(serviceName = "EchoService", portName = "EchoServicePort", //
        targetNamespace = "urn:hasor:meteor:soap:EchoService", //
        endpointInterface = "net.hasor.example.protocol.soap.SoapJavaSdkEcho")
public class SoapJavaSdkService implements SoapJavaSdkEcho {
    @Override
    public String echo(String value) {
        return "sdk-server:" + value;
    }
}
