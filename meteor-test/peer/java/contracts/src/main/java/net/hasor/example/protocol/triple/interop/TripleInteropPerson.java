/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.triple.interop;
import java.io.Serializable;

/** Java 接口模式传递的普通业务对象。 */
public class TripleInteropPerson implements Serializable {
    private static final long   serialVersionUID = 1L;
    private              String name;
    private              int    age;

    public TripleInteropPerson(String name, int age) {
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
