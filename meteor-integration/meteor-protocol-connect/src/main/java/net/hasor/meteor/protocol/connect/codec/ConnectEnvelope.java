/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.protocol.connect.ConnectStatusException;

/** 已解压的数据消息或已解析的流结束消息。 */
public record ConnectEnvelope(byte[] message, ConnectStatusException failure, Map<String, List<String>> metadata, boolean end) {
    //
}
