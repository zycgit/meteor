/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;

/** The discriminated CALL/REPLY envelope defined by RFC 5531. */
public final class OncRpcMessage {
    public static final int        CALL          = 0;
    public static final int        REPLY         = 1;
    public static final int        MSG_ACCEPTED  = 0;
    public static final int        MSG_DENIED    = 1;
    public static final int        SUCCESS       = 0;
    public static final int        PROG_UNAVAIL  = 1;
    public static final int        PROG_MISMATCH = 2;
    public static final int        PROC_UNAVAIL  = 3;
    public static final int        GARBAGE_ARGS  = 4;
    public static final int        SYSTEM_ERR    = 5;
    public static final int        RPC_MISMATCH  = 0;
    public static final int        AUTH_ERROR    = 1;
    private final       long       xid;
    private final       int        messageType;
    private final       long       rpcVersion;
    private final       long       program;
    private final       long       version;
    private final       long       procedure;
    private final       OncRpcAuth credentials;
    private final       OncRpcAuth verifier;
    private final       int        replyStatus;
    private final       int        status;
    private final       int        authStatus;
    private final       long       lowVersion;
    private final       long       highVersion;
    private final       byte[]     body;

    private OncRpcMessage(long xid, int messageType, long rpcVersion, // message envelope
            long program, long version, long procedure, // CALL identity
            OncRpcAuth credentials, OncRpcAuth verifier, // authentication
            int replyStatus, int status, int authStatus, // reply discriminants
            long lowVersion, long highVersion, byte[] body) {
        this.xid = xid;
        this.messageType = messageType;
        this.rpcVersion = rpcVersion;
        this.program = program;
        this.version = version;
        this.procedure = procedure;
        this.credentials = credentials;
        this.verifier = verifier;
        this.replyStatus = replyStatus;
        this.status = status;
        this.authStatus = authStatus;
        this.lowVersion = lowVersion;
        this.highVersion = highVersion;
        this.body = body.clone();
    }

    public static OncRpcMessage newCall(long xid, long rpcVersion, // message envelope
            long program, long version, long procedure, // CALL identity
            OncRpcAuth credentials, OncRpcAuth verifier, byte[] body) {
        requireUnsigned(xid);
        requireUnsigned(rpcVersion);
        requireUnsigned(program);
        requireUnsigned(version);
        requireUnsigned(procedure);
        return new OncRpcMessage(xid, CALL, rpcVersion, program, version, procedure, // CALL identity
                credentials, verifier, -1, -1, -1, 0, 0, body);
    }

    public static OncRpcMessage newSuccess(long xid, OncRpcAuth verifier, byte[] body) {
        requireUnsigned(xid);
        return new OncRpcMessage(xid, REPLY, 0, 0, 0, 0, null, verifier, MSG_ACCEPTED, SUCCESS, -1, 0, 0, body);
    }

    public static OncRpcMessage newAcceptedError(long xid, OncRpcAuth verifier, int status, long low, long high) {
        requireUnsigned(xid);
        if (status < PROG_UNAVAIL || status > SYSTEM_ERR) {
            throw new IllegalArgumentException("Invalid ONC RPC acceptance error: " + status);
        }
        if (status == PROG_MISMATCH) {
            requireRange(low, high);
        }
        return new OncRpcMessage(xid, REPLY, 0, 0, 0, 0, null, verifier, // accepted reply
                MSG_ACCEPTED, status, -1, low, high, new byte[0]);
    }

    public static OncRpcMessage newRpcMismatch(long xid, long low, long high) {
        requireUnsigned(xid);
        requireRange(low, high);
        return new OncRpcMessage(xid, REPLY, 0, 0, 0, 0, null, null, // rejected RPC version
                MSG_DENIED, RPC_MISMATCH, -1, low, high, new byte[0]);
    }

    public static OncRpcMessage newAuthError(long xid, int status) {
        requireUnsigned(xid);
        if (status <= OncRpcAuth.AUTH_OK) {
            throw new IllegalArgumentException("Authentication failure must use a nonzero status");
        }
        return new OncRpcMessage(xid, REPLY, 0, 0, 0, 0, null, null, MSG_DENIED, AUTH_ERROR, status, 0, 0, new byte[0]);
    }

    private static void requireUnsigned(long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("ONC RPC field must be an unsigned 32-bit integer: " + value);
        }
    }

    private static void requireRange(long low, long high) {
        requireUnsigned(low);
        requireUnsigned(high);
        if (low > high) {
            throw new IllegalArgumentException("ONC RPC version range is reversed");
        }
    }

    public long getXid() {
        return this.xid;
    }

    public int getMessageType() {
        return this.messageType;
    }

    public long getRpcVersion() {
        return this.rpcVersion;
    }

    public long getProgram() {
        return this.program;
    }

    public long getVersion() {
        return this.version;
    }

    public long getProcedure() {
        return this.procedure;
    }

    public OncRpcAuth getCredentials() {
        return this.credentials;
    }

    public OncRpcAuth getVerifier() {
        return this.verifier;
    }

    public int getReplyStatus() {
        return this.replyStatus;
    }

    public int getAcceptStatus() {
        return this.replyStatus == MSG_ACCEPTED ? this.status : -1;
    }

    public int getRejectStatus() {
        return this.replyStatus == MSG_DENIED ? this.status : -1;
    }

    public int getAuthStatus() {
        return this.authStatus;
    }

    public long getLowVersion() {
        return this.lowVersion;
    }

    public long getHighVersion() {
        return this.highVersion;
    }

    public byte[] getBody() {
        return this.body.clone();
    }

    public boolean isCall() {
        return this.messageType == CALL;
    }

    public boolean isSuccess() {
        return this.messageType == REPLY && this.replyStatus == MSG_ACCEPTED && this.status == SUCCESS;
    }
}
