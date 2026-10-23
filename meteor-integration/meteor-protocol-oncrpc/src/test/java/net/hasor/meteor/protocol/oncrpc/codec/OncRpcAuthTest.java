/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class OncRpcAuthTest {
    @Test
    public void nullAuthenticationUsesEmptyBody() {
        // 默认凭证和 verifier 均不携带身份数据。
        OncRpcAuth auth = OncRpcAuth.newNone();
        assertEquals(OncRpcAuth.AUTH_NONE, auth.getFlavor());
        assertArrayEquals(new byte[0], auth.getBody());
        assertEquals(OncRpcAuth.AUTH_OK, auth.validateCredential());
        assertEquals(OncRpcAuth.AUTH_OK, auth.validateVerifier());
    }

    @Test
    public void nullAuthenticationAcceptsUndefinedOpaqueData() {
        // RFC 5531 只推荐 NONE 正文为空，并未规定非空正文非法。
        OncRpcAuth auth = new OncRpcAuth(OncRpcAuth.AUTH_NONE, new byte[] { 1, 2, 3 });
        assertEquals(OncRpcAuth.AUTH_OK, auth.validateCredential());
        assertEquals(OncRpcAuth.AUTH_OK, auth.validateVerifier());
    }

    @Test
    public void unknownCredentialRemainsOpaqueForStandardRejection() {
        // 不支持的 flavor 被保留，交由调用链返回认证失败。
        OncRpcAuth auth = new OncRpcAuth(6, new byte[] { 7, 8 });
        assertEquals(6, auth.getFlavor());
        assertArrayEquals(new byte[] { 7, 8 }, auth.getBody());
        assertEquals(OncRpcAuth.AUTH_REJECTEDCRED, auth.validateCredential());
    }

    @Test
    public void unsupportedVerifierIsRejected() {
        // AUTH_SYS 是凭证格式，不能作为本实现的 verifier。
        OncRpcAuth auth = OncRpcAuth.newSystem(1, "host", 2, 3, new long[0]);
        assertEquals(OncRpcAuth.AUTH_REJECTEDVERF, auth.validateVerifier());
    }

    @Test
    public void systemIdentityPreservesAllUnsignedFields() {
        // 高位 UID、GID 和时间戳不转换成负数。
        long[] groups = { 0, 0xffff_ffffL };
        OncRpcAuth auth = OncRpcAuth.newSystem(0xffff_ffffL, "node-1", 0xffff_fffeL, 0xffff_fffdL, groups);

        // 正文按标准结构恢复身份信息。
        Map<String, Object> identity = auth.getSystemIdentity();
        assertEquals(OncRpcAuth.AUTH_OK, auth.validateCredential());
        assertEquals(0xffff_ffffL, identity.get("stamp"));
        assertEquals("node-1", identity.get("machineName"));
        assertEquals(0xffff_fffeL, identity.get("uid"));
        assertEquals(0xffff_fffdL, identity.get("gid"));
        assertEquals(Arrays.asList(0L, 0xffff_ffffL), identity.get("groups"));
    }

    @Test
    public void systemIdentitySupportsMaximumHostnameAndGroups() {
        // 机器名上限为 255 个 ASCII 字符，附属组上限为 16 个。
        OncRpcAuth auth = OncRpcAuth.newSystem(1, "a".repeat(255), 2, 3, new long[16]);
        assertEquals(OncRpcAuth.AUTH_OK, auth.validateCredential());
        assertTrue(auth.getBody().length <= 400);
    }

    @Test
    public void systemIdentityAllowsNoSupplementaryGroup() {
        // 空附属组仍保留主组字段。
        OncRpcAuth auth = OncRpcAuth.newSystem(1, "node", 2, 3, new long[0]);
        assertEquals(Collections.emptyList(), auth.getSystemIdentity().get("groups"));
    }

    @Test
    public void systemFactoryRejectsTooManyGroups() {
        // 写入端也遵循 AUTH_SYS 的组数边界。
        assertThrows(IllegalArgumentException.class, () -> OncRpcAuth.newSystem(1, "node", 2, 3, new long[17]));
    }

    @Test
    public void systemFactoryRejectsHostnameAboveLimit() {
        // 机器名不能借助外层 400 字节限制绕过 255 字节上限。
        assertThrows(IllegalArgumentException.class, () -> OncRpcAuth.newSystem(1, "a".repeat(256), 2, 3, new long[0]));
    }

    @Test
    public void systemFactoryRejectsNonAsciiHostname() {
        // XDR string 在这个标准配置中按 ASCII 编码。
        assertThrows(IllegalArgumentException.class, () -> OncRpcAuth.newSystem(1, "主机", 2, 3, new long[0]));
    }

    @Test
    public void systemFactoryRejectsUnsignedUidOverflow() {
        // 无符号字段不接受超出 32 位的应用值。
        assertThrows(IllegalArgumentException.class, () -> {
            OncRpcAuth.newSystem(1, "node", 0x1_0000_0000L, 3, new long[0]);
        });
    }

    @Test
    public void malformedSystemBodyReturnsBadCredential() {
        // flavor 合法，但正文缺少 AUTH_SYS 必须的字段。
        OncRpcAuth auth = new OncRpcAuth(OncRpcAuth.AUTH_SYS, new byte[4]);
        assertEquals(OncRpcAuth.AUTH_BADCRED, auth.validateCredential());
    }

    @Test
    public void malformedSystemGroupCountReturnsBadCredential() {
        // 读取端限制组数，拒绝声明 17 组的外部凭证。
        XdrOutput output = this.systemPrefix();
        output.writeUnsignedInt(17);
        OncRpcAuth auth = new OncRpcAuth(OncRpcAuth.AUTH_SYS, output.toByteArray());
        assertEquals(OncRpcAuth.AUTH_BADCRED, auth.validateCredential());
    }

    @Test
    public void truncatedSupplementaryGroupReturnsBadCredential() {
        // 组数声明为一组，但没有组 ID。
        XdrOutput output = this.systemPrefix();
        output.writeUnsignedInt(1);
        OncRpcAuth auth = new OncRpcAuth(OncRpcAuth.AUTH_SYS, output.toByteArray());
        assertEquals(OncRpcAuth.AUTH_BADCRED, auth.validateCredential());
    }

    @Test
    public void trailingSystemDataReturnsBadCredential() {
        // 身份正文不允许在已完成的组数组后附加字段。
        XdrOutput output = this.systemPrefix();
        output.writeUnsignedInt(0);
        output.writeInt(99);
        OncRpcAuth auth = new OncRpcAuth(OncRpcAuth.AUTH_SYS, output.toByteArray());
        assertEquals(OncRpcAuth.AUTH_BADCRED, auth.validateCredential());
    }

    @Test
    public void opaqueAuthenticationAcceptsExactlyFourHundredBytes() {
        // 外层 opaque_auth 的限制独立于具体认证方式。
        OncRpcAuth auth = new OncRpcAuth(99, new byte[400]);
        assertEquals(400, auth.getBody().length);
    }

    @Test
    public void opaqueAuthenticationRejectsAboveFourHundredBytes() {
        // 超长认证正文不会进入 RPC 消息。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcAuth(99, new byte[401]));
    }

    @Test
    public void authenticationDoesNotExposeMutableBody() {
        // 创建后修改原数组，或修改 getter 的返回值，都不影响凭证。
        byte[] source = { 1, 2 };
        OncRpcAuth auth = new OncRpcAuth(99, source);
        source[0] = 9;
        auth.getBody()[1] = 9;
        assertArrayEquals(new byte[] { 1, 2 }, auth.getBody());
    }

    @Test
    public void systemIdentityCannotBeRequestedFromNullAuthentication() {
        // 非 AUTH_SYS 凭证没有系统身份字段。
        assertThrows(IllegalArgumentException.class, () -> OncRpcAuth.newNone().getSystemIdentity());
    }

    @Test
    public void decodedSystemIdentityIsReadOnly() {
        // 身份快照不允许上层修改，避免后续治理误用可变认证元数据。
        OncRpcAuth auth = OncRpcAuth.newSystem(1, "host", 2, 3, new long[0]);
        Map<String, Object> identity = auth.getSystemIdentity();
        assertThrows(UnsupportedOperationException.class, () -> identity.put("uid", 9L));
    }

    private XdrOutput systemPrefix() {
        XdrOutput output = new XdrOutput(400, 16, 4);
        output.writeUnsignedInt(1);
        output.writeString("node", 255);
        output.writeUnsignedInt(2);
        output.writeUnsignedInt(3);
        return output;
    }
}
