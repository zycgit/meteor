/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class OncRpcWireCodecTest {
    @Test
    public void callEncodingMatchesRpcV2WireVector() {
        // 独立十六进制向量按 RFC 5531 的字段顺序给出。
        OncRpcAuth auth = OncRpcAuth.newNone();
        byte[] body = HexFormat.of().parseHex("12345678");
        OncRpcMessage message = OncRpcMessage.newCall(0x0011_2233, 2, 0x2000_0001, 1, 5, auth, auth, body);
        String callIdentity = "001122330000000000000002200000010000000100000005";
        String expected = callIdentity + "0000000000000000000000000000000012345678";

        // 线编码没有 TCP record marking，也没有应用参数的附加长度字段。
        assertArrayEquals(HexFormat.of().parseHex(expected), this.codec().encode(message));
    }

    @Test
    public void callDecodingReadsIndependentWireVector() {
        // CALL 的 XID 和程序号均覆盖 uint32 最高位。
        String vector = "ffffffff0000000000000002fffffffe0000000100000005" + "0000000000000000000000000000000001020304";
        OncRpcMessage message = this.codec().decode(HexFormat.of().parseHex(vector));
        assertTrue(message.isCall());
        assertEquals(0xffff_ffffL, message.getXid());
        assertEquals(0xffff_fffeL, message.getProgram());
        assertEquals(1, message.getVersion());
        assertEquals(5, message.getProcedure());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, message.getBody());
    }

    @Test
    public void successEncodingMatchesAcceptedReplyVector() {
        // SUCCESS 分支的结果是紧跟状态码的应用 XDR 正文。
        OncRpcMessage message = OncRpcMessage.newSuccess(7, OncRpcAuth.newNone(), new byte[] { 0, 0, 0, 42 });
        String expected = "0000000700000001000000000000000000000000000000000000002a";
        assertArrayEquals(HexFormat.of().parseHex(expected), this.codec().encode(message));
    }

    @Test
    public void systemAuthenticationRoundTripsWithoutChangingBody() {
        // UID/GID/附属组由 AUTH_SYS 正文携带。
        OncRpcAuth auth = OncRpcAuth.newSystem(3, "host", 12, 34, new long[] { 56, 78 });
        OncRpcMessage call = OncRpcMessage.newCall(4, 2, 5, 6, 7, auth, OncRpcAuth.newNone(), new byte[0]);
        OncRpcMessage decoded = this.codec().decode(this.codec().encode(call));
        assertEquals(OncRpcAuth.AUTH_SYS, decoded.getCredentials().getFlavor());
        assertArrayEquals(auth.getBody(), decoded.getCredentials().getBody());
        assertEquals(OncRpcAuth.AUTH_OK, decoded.getCredentials().validateCredential());
    }

    @Test
    public void unsupportedAuthenticationRemainsAvailableForRejection() {
        // 读取信封不负责策略判定，不会丢弃未知 flavor 的正文。
        OncRpcAuth auth = new OncRpcAuth(6, new byte[] { 1, 2, 3 });
        OncRpcMessage call = OncRpcMessage.newCall(4, 2, 5, 6, 7, auth, OncRpcAuth.newNone(), new byte[0]);
        OncRpcMessage decoded = this.codec().decode(this.codec().encode(call));
        assertEquals(6, decoded.getCredentials().getFlavor());
        assertArrayEquals(new byte[] { 1, 2, 3 }, decoded.getCredentials().getBody());
    }

    @Test
    public void malformedSystemCredentialDoesNotLoseXid() {
        // 凭证结构损坏仍是完整的 RPC 信封，后续可返回 AUTH_BADCRED。
        OncRpcAuth malformed = new OncRpcAuth(OncRpcAuth.AUTH_SYS, new byte[0]);
        OncRpcMessage call = OncRpcMessage.newCall(19, 2, 5, 6, 7, malformed, OncRpcAuth.newNone(), new byte[0]);
        OncRpcMessage decoded = this.codec().decode(this.codec().encode(call));
        assertEquals(19, decoded.getXid());
        assertEquals(OncRpcAuth.AUTH_BADCRED, decoded.getCredentials().validateCredential());
    }

    @Test
    public void incompatibleRpcVersionNeedsOnlyDiscriminatedPrefix() {
        // 版本不兼容时不可假定后续字段沿用 RPC v2 布局。
        byte[] data = HexFormat.of().parseHex("000000010000000000000003");
        OncRpcMessage decoded = this.codec().decode(data);
        assertEquals(1, decoded.getXid());
        assertEquals(3, decoded.getRpcVersion());
        assertTrue(decoded.isCall());
    }

    @Test
    public void incompatibleRpcVersionPreservesUninterpretedRemainder() {
        // 未知版本可以拥有不同长度的正文。
        byte[] data = HexFormat.of().parseHex("00000001000000000000000301020304");
        OncRpcMessage decoded = this.codec().decode(data);
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, decoded.getBody());
    }

    @Test
    public void programUnavailableRoundTripsAsVoidError() {
        // 程序不存在的接受回执没有额外正文。
        OncRpcMessage reply = this.roundTripError(OncRpcMessage.PROG_UNAVAIL);
        assertEquals(OncRpcMessage.PROG_UNAVAIL, reply.getAcceptStatus());
        assertArrayEquals(new byte[0], reply.getBody());
    }

    @Test
    public void programMismatchPreservesUnsignedRange() {
        // 远端程序版本范围可覆盖完整 uint32。
        int status = OncRpcMessage.PROG_MISMATCH;
        OncRpcMessage reply = OncRpcMessage.newAcceptedError(9, OncRpcAuth.newNone(), status, 1, 0xffff_ffffL);
        OncRpcMessage decoded = this.codec().decode(this.codec().encode(reply));
        assertEquals(OncRpcMessage.PROG_MISMATCH, decoded.getAcceptStatus());
        assertEquals(1, decoded.getLowVersion());
        assertEquals(0xffff_ffffL, decoded.getHighVersion());
    }

    @Test
    public void procedureUnavailableRoundTripsAsVoidError() {
        // 过程号未发布与程序不存在使用不同状态码。
        assertEquals(OncRpcMessage.PROC_UNAVAIL, this.roundTripError(OncRpcMessage.PROC_UNAVAIL).getAcceptStatus());
    }

    @Test
    public void garbageArgumentsRoundTripsAsVoidError() {
        // 参数 XDR 错误不携带服务端异常正文。
        assertEquals(OncRpcMessage.GARBAGE_ARGS, this.roundTripError(OncRpcMessage.GARBAGE_ARGS).getAcceptStatus());
    }

    @Test
    public void systemFailureRoundTripsAsVoidError() {
        // 内部服务错误统一保留标准 SYSTEM_ERR。
        assertEquals(OncRpcMessage.SYSTEM_ERR, this.roundTripError(OncRpcMessage.SYSTEM_ERR).getAcceptStatus());
    }

    @Test
    public void rejectedRpcMismatchMatchesStandardWireVector() {
        // RPC_MISMATCH 的回复不包含 accepted verifier。
        OncRpcMessage reply = OncRpcMessage.newRpcMismatch(8, 2, 2);
        String expected = "000000080000000100000001000000000000000200000002";
        byte[] encoded = this.codec().encode(reply);
        assertArrayEquals(HexFormat.of().parseHex(expected), encoded);
        assertEquals(OncRpcMessage.RPC_MISMATCH, this.codec().decode(encoded).getRejectStatus());
    }

    @Test
    public void rejectedAuthenticationMatchesStandardWireVector() {
        // AUTH_ERROR 的最后一个 int 为认证原因。
        OncRpcMessage reply = OncRpcMessage.newAuthError(8, OncRpcAuth.AUTH_BADCRED);
        String expected = "0000000800000001000000010000000100000001";
        byte[] encoded = this.codec().encode(reply);
        assertArrayEquals(HexFormat.of().parseHex(expected), encoded);
        assertEquals(OncRpcAuth.AUTH_BADCRED, this.codec().decode(encoded).getAuthStatus());
    }

    @Test
    public void successPreservesReplyVerifier() {
        // 客户端可以对成功回执的 verifier 执行独立认证检查。
        OncRpcAuth auth = new OncRpcAuth(2, new byte[] { 7 });
        OncRpcMessage reply = OncRpcMessage.newSuccess(1, auth, new byte[0]);
        OncRpcMessage decoded = this.codec().decode(this.codec().encode(reply));
        assertEquals(2, decoded.getVerifier().getFlavor());
        assertArrayEquals(new byte[] { 7 }, decoded.getVerifier().getBody());
    }

    @Test
    public void decoderRejectsUnknownMessageType() {
        // CALL/REPLY 之外没有合法 union 分支。
        byte[] data = HexFormat.of().parseHex("0000000100000002");
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsUnknownReplyStatus() {
        // reply_stat 只允许 accepted 或 denied。
        byte[] data = HexFormat.of().parseHex("000000010000000100000002");
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsUnknownAcceptedStatus() {
        // 不存在的 accept_stat 不映射成 SYSTEM_ERR。
        byte[] data = HexFormat.of().parseHex("000000010000000100000000000000000000000000000006");
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsUnknownRejectedStatus() {
        // rejected_reply 不能读取未定义的分支。
        byte[] data = HexFormat.of().parseHex("00000001000000010000000100000002");
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsAuthErrorWithSuccessReason() {
        // denied/AUTH_ERROR 不能声明认证成功。
        byte[] data = HexFormat.of().parseHex("0000000100000001000000010000000100000000");
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsTruncatedV2Header() {
        // v2 必须提供完整程序、版本、过程和认证字段。
        byte[] data = HexFormat.of().parseHex("000000010000000000000002");
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsTruncatedAuthenticationBody() {
        // 凭证长度不能大于剩余消息。
        byte[] data = this.codec().encode(this.call());
        ByteBuffer.wrap(data).putInt(28, 4);
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsOversizeAuthenticationBody() {
        // opaque_auth 的 400 字节上限在分配正文前检查。
        byte[] data = this.codec().encode(this.call());
        ByteBuffer.wrap(data).putInt(28, 401);
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsTrailingVoidErrorData() {
        // void 错误分支不得附加应用数据。
        byte[] encoded = this.codec().encode(OncRpcMessage.newAuthError(1, OncRpcAuth.AUTH_BADCRED));
        byte[] data = Arrays.copyOf(encoded, encoded.length + 4);
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsReversedRemoteVersionRange() {
        // 外部错误回执的版本范围也要完整且有序。
        byte[] data = HexFormat.of().parseHex("000000010000000100000001000000000000000300000002");
        assertThrows(IllegalArgumentException.class, () -> this.codec().decode(data));
    }

    @Test
    public void decoderRejectsMessageAboveConfiguredSize() {
        // TCP 和 UDP 都使用相同的信封大小上限。
        byte[] data = Arrays.copyOf(this.codec().encode(this.call()), 44);
        OncRpcWireCodec limited = new OncRpcWireCodec(40);
        assertThrows(IllegalArgumentException.class, () -> limited.decode(data));
    }

    @Test
    public void encoderRejectsMessageAboveConfiguredSize() {
        // 完整 CALL 头为 40 字节，附加参数不能突破上限。
        OncRpcAuth auth = OncRpcAuth.newNone();
        OncRpcMessage call = OncRpcMessage.newCall(1, 2, 3, 4, 5, auth, auth, new byte[4]);
        OncRpcWireCodec limited = new OncRpcWireCodec(40);
        assertThrows(IllegalArgumentException.class, () -> limited.encode(call));
    }

    @Test
    public void wireSizeCannotBeSmallerThanEmptyCall() {
        // 配置不允许连最小 CALL 信封都无法容纳。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcWireCodec(39));
    }

    private OncRpcMessage roundTripError(int status) {
        OncRpcMessage reply = OncRpcMessage.newAcceptedError(9, OncRpcAuth.newNone(), status, 0, 0);
        return this.codec().decode(this.codec().encode(reply));
    }

    private OncRpcMessage call() {
        OncRpcAuth auth = OncRpcAuth.newNone();
        return OncRpcMessage.newCall(1, 2, 3, 4, 5, auth, auth, new byte[0]);
    }

    private OncRpcWireCodec codec() {
        return new OncRpcWireCodec(4096);
    }
}
