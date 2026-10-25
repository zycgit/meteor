/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;

/** 单次调用只验证一份类型向量，入参和返回值均为真实结构对象。 */
public interface TypeEchoService {
    @MetMethod(id = 1)
    TypeValue echo(@MetParam(id = 1, name = "request") TypeValue request);
}
