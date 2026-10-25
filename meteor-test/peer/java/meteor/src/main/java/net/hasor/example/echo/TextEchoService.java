/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.echo;
import net.hasor.meteor.annotation.MetParam;

/** 普通文本回声接口，可绑定不同协议而不依赖任何客户端 SDK。 */
public interface TextEchoService {
    String echo(@MetParam(id = 1) String text);
}
