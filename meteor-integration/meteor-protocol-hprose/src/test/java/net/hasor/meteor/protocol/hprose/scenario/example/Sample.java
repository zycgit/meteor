/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.scenario.example;
import java.util.Date;
import java.util.List;
import java.util.Map;

public interface Sample {
    String echo(String value);

    int add(int a, int b);

    byte[] bytes(byte[] value);

    Date date(Date value);

    Map<String, Object> map(Map<String, Object> value);

    Person person(Person value);

    List<Person> people(List<Person> value);

    String fail();

    void accept(String value);

    void mutate(List<String> value);

    String delay(String value, int milliseconds) throws InterruptedException;
}
