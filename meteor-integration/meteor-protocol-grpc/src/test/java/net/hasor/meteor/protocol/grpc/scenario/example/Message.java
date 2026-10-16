/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.scenario.example;
import java.io.Serializable;

public class Message implements Serializable {
    private static final long   serialVersionUID = 1L;
    private              String value;
    private              int    count;

    public Message() {
    }

    public static Message of(String value, int count) {
        Message message = new Message();
        message.value = value;
        message.count = count;
        return message;
    }

    public String getValue() {
        return this.value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public int getCount() {
        return this.count;
    }

    public void setCount(int count) {
        this.count = count;
    }
}
