/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import net.hasor.meteor.annotation.MetMessage;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;

/** 公共注解声明名称和编号，声明异常由 mapping.thrift 提供。 */
public interface NoteService {
    Note echo(@MetParam(id = 1) Note value);

    @MetMethod(name = "difference")
    int subtract(@MetParam(id = 7) int left, @MetParam(id = 2) int right);

    void fail(@MetParam(id = 1) int code) throws NoteFailure;

    @MetMessage
    void notify(@MetParam(id = 1) String text);

    int notificationCount();
}
