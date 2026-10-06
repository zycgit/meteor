/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
/**
 * 响应请求
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetResponse extends MetHeader {
    /**最终结果。*/
    Object getData();

    /**返回的类型信息。*/
    Class<?> getReturnType();

    /**返回状态*/
    short getStatus();

    /**发送最终结果(该方法会导致{@link #isResponse()}状态变为 true)。*/
    void sendData(Object returnObject);

    /**
     * 发送最终结果(该方法会导致{@link #isResponse()}状态变为 true)。
     * @see net.hasor.meteor.domain.ProtocolStatus
     */
    void sendStatus(short status);

    /**
     * 发送最终结果(该方法会导致{@link #isResponse()}状态变为 true)。
     * @see net.hasor.meteor.domain.ProtocolStatus
     */
    void sendStatus(short status, String messageBody);

    /**调用的结果是否已经写入客户端。*/
    boolean isResponse();
}