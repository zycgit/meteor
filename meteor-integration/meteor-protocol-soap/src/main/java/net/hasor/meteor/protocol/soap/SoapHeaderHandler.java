/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.soap;
import java.util.Set;
import net.hasor.meteor.domain.payload.Payload;
import org.w3c.dom.Element;

/** SOAP Header 扩展处理器；request 明确区分请求和响应两个方向，实现必须支持不同连接并发调用。 */
public interface SoapHeaderHandler {
    /** 当前节点承担的额外 actor/role URI；SOAP next 和最终接收方角色由协议自动提供。 */
    Set<String> roles();

    /** 处理发给当前节点的一个 Header block，只有完成其语义处理后才返回 true。 */
    boolean read(Element header, Payload message, boolean request);

    /** 将出站 Header blocks 写入 Header 容器；没有写入时不发送空容器。 */
    void write(Element header, Payload message, boolean request);
}
