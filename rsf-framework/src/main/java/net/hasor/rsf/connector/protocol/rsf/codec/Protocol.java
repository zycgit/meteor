/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.connector.protocol.rsf.codec;
import java.io.IOException;

/**
 * Protocol Interface,for custom network protocol
 * @version : 2014年11月4日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface Protocol<T> {

    /**
     * encode Message to byte & write to network framework
     */
    void encode(T message, WireBuffer buf) throws IOException;

    /**
     * decode stream to object
     */
    T decode(WireBuffer buf) throws IOException;
}
