/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import net.hasor.meteor.protocol.soap.SoapGet;

/** 显式区分只读 HTTP 资源与普通 SOAP operation。 */
public interface SoapStatusService {
    @SoapGet
    String status();

    String internalStatus();
}
