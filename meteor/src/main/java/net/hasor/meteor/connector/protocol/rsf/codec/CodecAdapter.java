/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import java.io.IOException;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/**
 * Protocol Interface,for custom network protocol
 * @version : 2014年11月4日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface CodecAdapter {

    /**
     * 将{@link RequestPayload},转换为{@link RequestBlock}。
     */
    RequestBlock buildRequestBlock(RequestPayload info) throws IOException;

    /**
     * 将{@link RequestBlock}写入{@link WireBuffer}。
     */
    void writeRequestBlock(RequestBlock block, WireBuffer out) throws IOException;

    /**
     * 将{@link WireBuffer} 中读取{@link RequestPayload}信息。
     */
    RequestPayload readRequestPayload(WireBuffer frame) throws Throwable;

    /**
     * 将{@link ResponsePayload},转换为{@link ResponseBlock}。
     */
    ResponseBlock buildResponseBlock(ResponsePayload info) throws IOException;

    /**
     * 将{@link ResponseBlock}写入{@link WireBuffer}。
     */
    void writeResponseBlock(ResponseBlock block, WireBuffer out) throws IOException;

    /**
     * 将{@link WireBuffer} 中读取{@link ResponsePayload}信息。
     */
    ResponsePayload readResponsePayload(WireBuffer frame) throws Throwable;
}
