/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class OncRpcExceptionTest {
    @Test
    public void acceptedFailureConvertsToStandardReply() {
        // 绑定器的参数错误转换为 accepted/GARBAGE_ARGS。
        OncRpcException failure = OncRpcException.newAcceptedError(OncRpcMessage.GARBAGE_ARGS, "invalid arguments");
        OncRpcMessage reply = failure.toReply(7);
        assertEquals("invalid arguments", failure.getMessage());
        assertEquals(OncRpcMessage.MSG_ACCEPTED, failure.getReplyStatus());
        assertEquals(OncRpcMessage.GARBAGE_ARGS, failure.getAcceptStatus());
        assertEquals(-1, failure.getRejectStatus());
        assertEquals(7, reply.getXid());
        assertEquals(OncRpcMessage.GARBAGE_ARGS, reply.getAcceptStatus());
    }

    @Test
    public void programMismatchConvertsVersionBounds() {
        // 程序版本错误携带服务允许的上下限。
        OncRpcException failure = OncRpcException.newProgramMismatch(2, 5);
        OncRpcMessage reply = failure.toReply(8);
        assertEquals(OncRpcMessage.PROG_MISMATCH, failure.getAcceptStatus());
        assertEquals(2, failure.getLowVersion());
        assertEquals(5, failure.getHighVersion());
        assertEquals(2, reply.getLowVersion());
        assertEquals(5, reply.getHighVersion());
    }

    @Test
    public void rpcMismatchConvertsRejectedVersionBounds() {
        // RPC 协议版本错误走 denied 分支。
        OncRpcException failure = OncRpcException.newRpcMismatch(2, 2);
        OncRpcMessage reply = failure.toReply(9);
        assertEquals(OncRpcMessage.MSG_DENIED, failure.getReplyStatus());
        assertEquals(OncRpcMessage.RPC_MISMATCH, failure.getRejectStatus());
        assertEquals(-1, failure.getAcceptStatus());
        assertEquals(OncRpcMessage.RPC_MISMATCH, reply.getRejectStatus());
        assertEquals(2, reply.getLowVersion());
        assertEquals(2, reply.getHighVersion());
    }

    @Test
    public void authenticationFailureConvertsRejectionReason() {
        // 认证策略传递准确的标准认证错误码。
        OncRpcException failure = OncRpcException.newAuthError(OncRpcAuth.AUTH_TOOWEAK, "system credential required");
        OncRpcMessage reply = failure.toReply(10);
        assertEquals(OncRpcAuth.AUTH_TOOWEAK, failure.getAuthStatus());
        assertEquals(OncRpcMessage.AUTH_ERROR, failure.getRejectStatus());
        assertEquals(OncRpcMessage.AUTH_ERROR, reply.getRejectStatus());
        assertEquals(OncRpcAuth.AUTH_TOOWEAK, reply.getAuthStatus());
    }
}
