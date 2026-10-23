/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.peer.protocol.dubbo;
import net.hasor.example.protocol.dubbo.interop.DubboInteropServiceImpl;
import org.apache.dubbo.rpc.RpcContext;

/** 只有附件读取属于官方 Dubbo 运行时，普通业务实现与双端示例共用。 */
public final class DubboPeerService extends DubboInteropServiceImpl {
    @Override
    public String metadata(String value) {
        String attachment = RpcContext.getContext().getAttachment("x-dubbo-test");
        DubboPeer.responseAttachment(attachment);
        return attachment;
    }
}
