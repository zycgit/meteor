/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.rsf;

/** 当前 Meteor 与独立 Hasor RSF SDK 共用的普通 Java 回声接口。 */
public interface RsfEchoService {
    String echo(String text);
}
