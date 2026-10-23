/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;

/** 业务实现只处理 Java 对象，不解析 IDL 或网络报文。 */
public class IdlServiceImpl implements IdlService {
    @Override
    public IdlPoint mirror(IdlPoint point) {
        return point;
    }

    @Override
    public int difference(int right, int left) {
        return left - right;
    }
}
