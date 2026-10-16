/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.support;
import java.util.List;

/** 直接以集合为参数和返回值的普通 Java 服务。 */
public interface CollectionService {
    List<String> echo(List<String> values);
}
