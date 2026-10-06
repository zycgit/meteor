/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor;
import java.lang.reflect.Method;
import java.util.Enumeration;
import net.hasor.meteor.address.InterAddress;

/**
 * 调用请求
 * @version : 2014年10月25日
 * @author 赵永春 (zyc@hasor.net)
 */
public interface MetRequest extends MetHeader {
    /**获取属性*/
    Object getAttribute(String attrKey);

    /**保存属性,属性会在请求完毕之后丢失。特性和 web 下的 request 属性类似。*/
    void setAttribute(String attrKey, Object attrValue);

    /**删除属性*/
    void removeAttribute(String attrKey);

    /**获取所有属性名。*/
    Enumeration<String> getAttributeNames();

    /**请求是否为本地发起的。*/
    boolean isLocal();

    /** 请求是否为消息类请求,对于消息类请求返回值是无效的。*/
    boolean isMessage();

    /**获取要调用的目标方法。*/
    Method getMethod();

    /**获取上下文。*/
    MetContext getContext();

    /**请求到达时间（如果是本地发起的请求，该值为当前时间）。*/
    long getReceiveTime();

    /**超时时间。*/
    int getTimeout();

    /**获取请求参数类型。*/
    Class<?>[] getParameterTypes();

    /**获取请求参数值。*/
    Object[] getParameterObject();

    /**获取发送请求的远程服务器使用的地址和端口，如果是本地发起的该地址则是本地RSF的地址。*/
    InterAddress getRemoteAddress();

    /**获取请求准备发送的目标地址（如果是分布式调用该方法会返回null）*/
    InterAddress getTargetAddress();

    /**判断当前请求是否为点对点定向调用。*/
    boolean isP2PCalls();
}