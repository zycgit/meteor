/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
/**
 * 请求响应通用。
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetHeader extends MetOptionSet {
    /**获取元信息*/
    MetBindInfo<?> getBindInfo();

    /**请求ID。*/
    long getRequestID();

    /**客户端希望的序列化方式*/
    String getSerializeType();
}