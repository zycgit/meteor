/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import net.hasor.meteor.domain.payload.RequestPayload;

/** 一个批量报文中的调用，解析失败也保留其响应位置。 */
record HproseCall(RequestPayload request, boolean byReference, Throwable failure) {
}