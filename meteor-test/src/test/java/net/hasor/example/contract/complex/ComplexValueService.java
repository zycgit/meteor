/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.complex;

/** 两种业务入口都接收一个具体 DTO，适用于单消息请求协议。 */
public interface ComplexValueService {
    ComplexDocument acceptDocument(ComplexDocument request);

    ComplexIndex acceptIndex(ComplexIndex request);
}
