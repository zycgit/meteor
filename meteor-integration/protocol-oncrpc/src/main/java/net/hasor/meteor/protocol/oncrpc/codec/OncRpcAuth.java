/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.util.*;

/** RFC 5531 opaque authentication data; AUTH_SYS describes identity rather than proving it. */
public final class OncRpcAuth {
    public static final int    AUTH_NONE         = 0;
    public static final int    AUTH_SYS          = 1;
    public static final int    AUTH_OK           = 0;
    public static final int    AUTH_BADCRED      = 1;
    public static final int    AUTH_REJECTEDCRED = 2;
    public static final int    AUTH_BADVERF      = 3;
    public static final int    AUTH_REJECTEDVERF = 4;
    public static final int    AUTH_TOOWEAK      = 5;
    public static final int    AUTH_INVALIDRESP  = 6;
    public static final int    AUTH_FAILED       = 7;
    public static final int    MAX_AUTH_BYTES    = 400;
    private final       int    flavor;
    private final       byte[] body;

    public OncRpcAuth(int flavor, byte[] body) {
        if (body.length > MAX_AUTH_BYTES) {
            throw new IllegalArgumentException("ONC RPC authentication body exceeds 400 bytes");
        }
        this.flavor = flavor;
        this.body = body.clone();
    }

    public static OncRpcAuth newNone() {
        return new OncRpcAuth(AUTH_NONE, new byte[0]);
    }

    public static OncRpcAuth newSystem(long stamp, String machineName, long userId, long groupId, long[] groups) {
        if (groups.length > 16) {
            throw new IllegalArgumentException("AUTH_SYS supports at most 16 supplementary groups");
        }

        // AUTH_SYS 的机器名、用户和组均使用规范定义的 XDR 类型。
        XdrOutput output = new XdrOutput(MAX_AUTH_BYTES, 16, 4);
        output.writeUnsignedInt(stamp);
        output.writeString(machineName, 255);
        output.writeUnsignedInt(userId);
        output.writeUnsignedInt(groupId);
        output.writeUnsignedInt(groups.length);
        for (long group : groups) {
            output.writeUnsignedInt(group);
        }
        return new OncRpcAuth(AUTH_SYS, output.toByteArray());
    }

    public int getFlavor() {
        return this.flavor;
    }

    public byte[] getBody() {
        return this.body.clone();
    }

    /** Check supported credentials without replacing or discarding the received opaque body. */
    public int validateCredential() {
        if (this.flavor == AUTH_NONE) {
            return AUTH_OK;
        }
        if (this.flavor != AUTH_SYS) {
            return AUTH_REJECTEDCRED;
        }

        // 身份正文在请求校验阶段解析，畸形凭证可以获得标准 AUTH_ERROR 回执。
        try {
            this.getSystemIdentity();
            return AUTH_OK;
        } catch (IllegalArgumentException malformed) {
            return AUTH_BADCRED;
        }
    }

    /** Decode the bounded AUTH_SYS identity for application metadata after credential validation. */
    public Map<String, Object> getSystemIdentity() {
        if (this.flavor != AUTH_SYS) {
            throw new IllegalArgumentException("Credential does not contain an AUTH_SYS identity");
        }
        XdrInput input = new XdrInput(this.body, MAX_AUTH_BYTES, 16, 4);
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("stamp", input.readUnsignedInt());
        identity.put("machineName", input.readString(255));
        identity.put("uid", input.readUnsignedInt());
        identity.put("gid", input.readUnsignedInt());
        long groupCount = input.readUnsignedInt();
        if (groupCount > 16) {
            throw new IllegalArgumentException("AUTH_SYS supports at most 16 supplementary groups");
        }
        List<Long> groups = new ArrayList<>((int) groupCount);
        for (int index = 0; index < groupCount; index++) {
            groups.add(input.readUnsignedInt());
        }
        input.requireEnd();
        identity.put("groups", Collections.unmodifiableList(groups));
        return Collections.unmodifiableMap(identity);
    }

    /** This implementation uses the null verifier for both supported credential flavors. */
    public int validateVerifier() {
        if (this.flavor == AUTH_NONE) {
            return AUTH_OK;
        }
        return AUTH_REJECTEDVERF;
    }
}
