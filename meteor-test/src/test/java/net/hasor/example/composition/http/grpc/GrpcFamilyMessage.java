/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.composition.http.grpc;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetType;

/** 同端口协议组合使用的普通 Java 消息，对应 family.proto 中的消息字段。 */
@MetType(name = "FamilyMessage")
public class GrpcFamilyMessage {
    @MetField(id = 1)
    private String text;
    @MetField(id = 2)
    private int    number;

    public GrpcFamilyMessage() {
    }

    public static GrpcFamilyMessage of(String text, int number) {
        GrpcFamilyMessage message = new GrpcFamilyMessage();
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
