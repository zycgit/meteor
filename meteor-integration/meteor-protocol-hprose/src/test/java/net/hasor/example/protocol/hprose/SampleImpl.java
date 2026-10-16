/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hprose;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class SampleImpl implements Sample {
    public String echo(String value) {
        return value;
    }

    public int add(int a, int b) {
        return a + b;
    }

    public byte[] bytes(byte[] value) {
        return value;
    }

    public Date date(Date value) {
        return value;
    }

    public Map<String, Object> map(Map<String, Object> value) {
        return value;
    }

    public Person person(Person value) {
        value.setAge(value.getAge() + 1);
        return value;
    }

    public List<Person> people(List<Person> value) {
        // 沿用单个对象的业务修改，让列表场景验证元素的类型还原。
        value.forEach(this::person);
        return value;
    }

    public String fail() {
        throw new IllegalStateException("business failure 中文");
    }

    public void accept(String value) {
    }

    public void mutate(List<String> value) {
        value.add("after");
    }

    public String delay(String value, int milliseconds) throws InterruptedException {
        // 延迟仅用于制造批次内的完成顺序差异。
        TimeUnit.MILLISECONDS.sleep(milliseconds);
        return value;
    }
}
