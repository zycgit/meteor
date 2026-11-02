/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OncRpcMessageTest {
    @Test
    public void callPreservesUnsignedIdentityAndBody() {
        // CALL 携带独立的程序、版本、过程和参数正文。
        OncRpcAuth auth = OncRpcAuth.newNone();
        OncRpcMessage message = OncRpcMessage.newCall(0xffff_ffffL, 2, 0xffff_fffeL, 3, 4, auth, auth, new byte[0]);
        assertTrue(message.isCall());
        assertFalse(message.isSuccess());
        assertEquals(OncRpcMessage.CALL, message.getMessageType());
        assertEquals(0xffff_ffffL, message.getXid());
        assertEquals(2, message.getRpcVersion());
        assertEquals(0xffff_fffeL, message.getProgram());
        assertEquals(3, message.getVersion());
        assertEquals(4, message.getProcedure());
        assertSame(auth, message.getCredentials());
        assertEquals(-1, message.getAcceptStatus());
        assertEquals(-1, message.getRejectStatus());
    }

    @Test
    public void messageProtectsApplicationBody() {
        // 消息创建后不再共享可写的应用参数数组。
        byte[] source = { 1, 2, 3, 4 };
        OncRpcAuth auth = OncRpcAuth.newNone();
        OncRpcMessage message = OncRpcMessage.newCall(1, 2, 3, 4, 5, auth, auth, source);
        source[0] = 9;
        message.getBody()[1] = 9;
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, message.getBody());
    }

    @Test
    public void successReplyKeepsVerifier() {
        // 成功回执拥有自己的 verifier 和应用结果正文。
        OncRpcAuth verifier = new OncRpcAuth(99, new byte[] { 7 });
        OncRpcMessage message = OncRpcMessage.newSuccess(1, verifier, new byte[4]);
        assertTrue(message.isSuccess());
        assertFalse(message.isCall());
        assertSame(verifier, message.getVerifier());
        assertEquals(OncRpcMessage.MSG_ACCEPTED, message.getReplyStatus());
        assertEquals(OncRpcMessage.SUCCESS, message.getAcceptStatus());
    }

    @Test
    public void acceptedErrorCarriesVersionRange() {
        // 版本不匹配时，回执携带服务支持的闭区间。
        int status = OncRpcMessage.PROG_MISMATCH;
        OncRpcMessage message = OncRpcMessage.newAcceptedError(2, OncRpcAuth.newNone(), status, 1, 0xffff_ffffL);
        assertFalse(message.isSuccess());
        assertEquals(OncRpcMessage.PROG_MISMATCH, message.getAcceptStatus());
        assertEquals(1, message.getLowVersion());
        assertEquals(0xffff_ffffL, message.getHighVersion());
        assertArrayEquals(new byte[0], message.getBody());
    }

    @Test
    public void rpcMismatchCarriesRejectedRange() {
        // RPC 自身版本错误与远端程序版本错误分开表示。
        OncRpcMessage message = OncRpcMessage.newRpcMismatch(3, 2, 2);
        assertEquals(OncRpcMessage.MSG_DENIED, message.getReplyStatus());
        assertEquals(OncRpcMessage.RPC_MISMATCH, message.getRejectStatus());
        assertEquals(-1, message.getAcceptStatus());
        assertEquals(2, message.getLowVersion());
        assertEquals(2, message.getHighVersion());
    }

    @Test
    public void authenticationFailureCarriesReason() {
        // AUTH_ERROR 只携带认证错误码。
        OncRpcMessage message = OncRpcMessage.newAuthError(4, OncRpcAuth.AUTH_BADCRED);
        assertEquals(OncRpcMessage.AUTH_ERROR, message.getRejectStatus());
        assertEquals(OncRpcAuth.AUTH_BADCRED, message.getAuthStatus());
        assertFalse(message.isSuccess());
    }

    @Test
    public void callRejectsNegativeXid() {
        // XID 使用 long 表示 uint32，而不是有符号 wire int。
        OncRpcAuth auth = OncRpcAuth.newNone();
        assertThrows(IllegalArgumentException.class, () -> {
            OncRpcMessage.newCall(-1, 2, 3, 4, 5, auth, auth, new byte[0]);
        });
    }

    @Test
    public void callRejectsProcedureOverflow() {
        // 过程 ID 不能超出 uint32。
        OncRpcAuth auth = OncRpcAuth.newNone();
        assertThrows(IllegalArgumentException.class, () -> {
            OncRpcMessage.newCall(1, 2, 3, 4, 0x1_0000_0000L, auth, auth, new byte[0]);
        });
    }

    @Test
    public void acceptedErrorRejectsSuccessStatus() {
        // 成功消息必须通过保留结果正文的工厂创建。
        assertThrows(IllegalArgumentException.class, () -> {
            OncRpcMessage.newAcceptedError(1, OncRpcAuth.newNone(), OncRpcMessage.SUCCESS, 0, 0);
        });
    }

    @Test
    public void acceptedErrorRejectsUnknownStatus() {
        // 保留未定义的 discriminant 会导致无法解释的回执。
        assertThrows(IllegalArgumentException.class, () -> {
            OncRpcMessage.newAcceptedError(1, OncRpcAuth.newNone(), 6, 0, 0);
        });
    }

    @Test
    public void acceptedErrorRejectsReversedVersionRange() {
        // 程序版本范围不允许下限超过上限。
        assertThrows(IllegalArgumentException.class, () -> {
            OncRpcMessage.newAcceptedError(1, OncRpcAuth.newNone(), OncRpcMessage.PROG_MISMATCH, 3, 2);
        });
    }

    @Test
    public void rpcMismatchRejectsVersionOverflow() {
        // RPC 版本范围也遵循 uint32 编码。
        assertThrows(IllegalArgumentException.class, () -> OncRpcMessage.newRpcMismatch(1, 0, 0x1_0000_0000L));
    }

    @Test
    public void authenticationErrorCannotCarrySuccessCode() {
        // 认证拒绝不能与 AUTH_OK 同时出现。
        assertThrows(IllegalArgumentException.class, () -> OncRpcMessage.newAuthError(1, OncRpcAuth.AUTH_OK));
    }
}
