/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetReturn;

/** 公共名称只描述线上契约，Java 方法可以保留其应用内名称。 */
public interface NamedEcho {
    @MetMethod(name = "Echo")
    @MetReturn(name = "answer")
    String repeat(@MetParam(name = "message") String text);
}