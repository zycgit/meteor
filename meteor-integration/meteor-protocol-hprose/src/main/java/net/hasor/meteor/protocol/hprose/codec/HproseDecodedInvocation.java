/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import java.util.List;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;

/** 完整解码后的调用批次，或由协议直接处理的发现与预检响应。 */
public final class HproseDecodedInvocation {
    private final List<HproseDecodedCall> calls;
    private final HttpResponse            immediate;

    HproseDecodedInvocation(List<HproseDecodedCall> calls, HttpResponse immediate) {
        this.calls = List.copyOf(calls);
        this.immediate = immediate;
    }

    public List<HproseDecodedCall> calls() {
        return this.calls;
    }

    public HttpResponse immediate() {
        return this.immediate;
    }
}
