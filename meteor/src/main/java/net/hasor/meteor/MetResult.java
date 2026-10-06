/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.io.Serializable;

/**
 * 用于RPC消息模式下,消息发送的返回值。
 * @version : 2015年1月8日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetResult extends Serializable {
    /**返回操作是否成功。*/
    boolean isSuccess();

    /**获取操作返回码。*/
    int getErrorCode();

    /**获取操作状态描述。*/
    String getErrorMessage();

    /**获取消息ID*/
    long getMessageID();
}