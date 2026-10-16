/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.rpc.websocket;
import java.util.ArrayList;
import java.util.List;

/** 示例 JSON 信封；参数和结果仍由公开 Json 序列化器按方法类型编解码。 */
public final class WebSocketEnvelope {
    public String       type;
    public long         id;
    public String       group;
    public String       name;
    public String       version;
    public String       method;
    public List<String> parameterTypes = new ArrayList<>();
    public List<byte[]> arguments      = new ArrayList<>();
    public int          timeout;
    public short        flags;
    public boolean      message;
    public short        status;
    public String       returnType;
    public byte[]       result;
}
