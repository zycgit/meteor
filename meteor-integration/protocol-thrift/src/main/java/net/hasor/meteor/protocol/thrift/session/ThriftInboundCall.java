/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.session;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.thrift.codec.ThriftMessage;

/** 入站调用的本地请求身份及回应时必须恢复的线上信封。 */
record ThriftInboundCall(RequestPayload request, ThriftMessage message) {
}
