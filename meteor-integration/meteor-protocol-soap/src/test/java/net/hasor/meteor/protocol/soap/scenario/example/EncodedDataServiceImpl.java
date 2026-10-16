/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap.scenario.example;

/** 回传收到的数据，供测试检查跨网络的类型及引用身份。 */
public class EncodedDataServiceImpl implements EncodedDataService {
    @Override
    public EncodedGraphNode graph(EncodedGraphNode value) {
        return value;
    }

    @Override
    public String[][] matrix(String[][] value) {
        return value;
    }
}
