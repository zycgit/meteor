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
 * 声明服务接口或方法使用消息调用。接口上的注解作用于全部方法，方法上的注解只作用于该方法。
 * 调用方等待协议完成消息提交，不等待业务执行结果。
 *
 * <p>RSF 等提供回执的协议等待远端入队确认；HTTP 协议可以使用空正文的 202 或 204 响应。
 * 原生不提供回执的 notification 协议在消息完整写出后完成提交，此时不保证远端已经接收或执行。
 * 业务返回值和执行异常不会作为最终响应发回调用方。</p>
 *
 * <p>提示：可以配合异步调用，使消息发布本身也不阻塞当前线程。</p>
 * @version : 2014年11月12日
 * @author 赵永春 (zyc@hasor.net)
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface MetMessage {
}