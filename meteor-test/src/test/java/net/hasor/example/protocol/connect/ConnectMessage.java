/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.connect;
import java.io.Serializable;

/** Connect 示例中直接作为参数和返回值的业务消息。 */
public class ConnectMessage implements Serializable {
    private static final long   serialVersionUID = 1L;
    private              String text;
    private              int    number;

    public ConnectMessage() {
    }

    public static ConnectMessage of(String text, int number) {
        ConnectMessage message = new ConnectMessage();
        message.text = text;
        message.number = number;
        return message;
    }

    public String getText() {
        return this.text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public int getNumber() {
        return this.number;
    }

    public void setNumber(int number) {
        this.number = number;
    }
}
