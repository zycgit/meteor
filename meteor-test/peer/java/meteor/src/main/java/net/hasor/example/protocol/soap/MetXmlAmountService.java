/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 同一普通 Java DTO 可使用自动生成或外部提供的 SOAP 契约。 */
public interface MetXmlAmountService {
    MetXmlAmount amount(MetXmlAmount value);
}
