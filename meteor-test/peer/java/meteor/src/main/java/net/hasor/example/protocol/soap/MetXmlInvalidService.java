/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 非法 DTO 映射应在服务发布时失败。 */
public interface MetXmlInvalidService {
    MetXmlConflictingValue echo(MetXmlConflictingValue value);
}
