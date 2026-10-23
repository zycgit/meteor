/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;

/** 程序号和过程号来自文件，Java 只声明名称映射。 */
public interface IdlService {
    @MetMethod(name = "echo")
    IdlPoint mirror(IdlPoint point);

    @MetMethod(name = "subtract")
    int difference(@MetParam(name = "right") int right, @MetParam(name = "left") int left);
}
