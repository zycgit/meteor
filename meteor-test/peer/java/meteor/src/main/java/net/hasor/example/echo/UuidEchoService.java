/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.example.echo;
import java.util.UUID;
import net.hasor.meteor.annotation.MetParam;

/** UUID 原值回声，使用普通 Java 契约独立验证原生 UUID 线类型。 */
public interface UuidEchoService {
    UUID echoUuid(@MetParam(id = 1) UUID value);
}
