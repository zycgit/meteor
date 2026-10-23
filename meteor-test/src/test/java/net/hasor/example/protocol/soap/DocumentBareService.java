/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 通过 document/literal bare 直接传输独立消息元素的普通 Java 服务。 */
public interface DocumentBareService {
    String join(String first, String second);

    String status();

    void accept(String message);
}
