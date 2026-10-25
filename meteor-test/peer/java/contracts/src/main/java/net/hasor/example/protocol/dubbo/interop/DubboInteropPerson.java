/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.dubbo.interop;
import java.io.Serializable;

/** 官方互通的业务对象，不引用 Meteor 或 Dubbo 的运行时类型。 */
public class DubboInteropPerson implements Serializable {
    private static final long   serialVersionUID = 1L;
    private              String name;
    private              int    age;

    public DubboInteropPerson(String name, int age) {
        this.name = name;
        this.age = age;
    }

    public String getName() {
        return this.name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getAge() {
        return this.age;
    }

    public void setAge(int age) {
        this.age = age;
    }
}
