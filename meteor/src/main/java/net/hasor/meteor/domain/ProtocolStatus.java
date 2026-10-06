/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
/**
 * Server:Unknown、Message、MovedPermanently、Unauthorized
 * Client:
 * @version : 2014年9月20日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface ProtocolStatus {
    /**未定义*/
    short Unknown            = 0;
    /**序列化类型未定义。*/
    short SerializeForbidden = 210;
    /**不支持的协议版本。*/
    short ProtocolUndefined  = 505;
    /**协议编码解码错误。*/
    short ProtocolError      = 506;
    /**序列化异常。*/
    short SerializeError     = 511;
    /**网络错误。*/
    short NetworkError       = 600;
    /**试图调用受保护的服务。*/
    short Unauthorized       = 401;
    //
    //-----------------------------------------------------Server(Response)
    //
    /**请求已经被接受,服务端正在处理。*/
    short Accept             = 102;
    /**内容正确返回。*/
    short OK                 = 200;
    /**服务资源不可用。*/
    short Forbidden          = 403;
    /**找不到服务。*/
    short NotFound           = 404;
    /**服务资源不可用。*/
    short QueueFull          = 405;
    /**调用服务执行出错，通常是遭到异常抛出。*/
    short InvokeError        = 500;
    //-----------------------------------------------------Client(Request)
    //
    /**达到发送限制。*/
    short SendLimitPolicy    = 501;
    /**超出允许的时间。*/
    short Timeout            = 408;
    //-----------------------------------------------------
}