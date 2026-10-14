/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.util.List;
import java.util.Map;
import net.hasor.meteor.connector.transport.http.HttpResponse;

/** 完整解码后的 HTTP 调用批次，或协议自己处理的发现与预检响应。 */
record HproseInvocation(List<HproseCall> calls, Map<String, List<String>> headers, HttpResponse immediate) {
}
