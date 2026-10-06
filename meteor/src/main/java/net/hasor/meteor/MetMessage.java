/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 宣布此接口为消息接口,RSF在处理该接口的 RPC 调用请求时会忽略远程机器的 response 。
 * 消息接口的工作方模式下,当确认 rpc 数据包投递到远程机器并进入调用队列之后，请求方会收到一个 ack 确认方法会立即返回成功。
 *
 * <p>提示: 您可以配合 RSF 的异步调用,使消息发布变为异步消息。</p>
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MetMessage {
}