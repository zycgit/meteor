/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.thrift.mapping;
import net.hasor.meteor.annotation.MetField;

/** 声明异常同样使用普通 Java Exception。 */
public class NoteFailure extends Exception {
    @MetField(id = 1)
    private int code;

    public NoteFailure() {
    }

    public int getCode() { return this.code; }
    public void setCode(int code) { this.code = code; }
}
