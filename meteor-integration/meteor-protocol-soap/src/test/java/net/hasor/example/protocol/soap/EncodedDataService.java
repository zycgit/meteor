/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 编码数据经普通 Java 接口调用，图和数组各自独立成一个 operation。 */
public interface EncodedDataService {
    EncodedGraphNode graph(EncodedGraphNode value);

    String[][] matrix(String[][] value);
}
