/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;

/** A standard RPC failure that can be returned without leaking an application exception. */
public final class OncRpcException extends RuntimeException {
    private final int  replyStatus;
    private final int  status;
    private final int  authStatus;
    private final long lowVersion;
    private final long highVersion;

    private OncRpcException(String message, int replyStatus, int status, int authStatus, long low, long high) {
        super(message);
        this.replyStatus = replyStatus;
        this.status = status;
        this.authStatus = authStatus;
        this.lowVersion = low;
        this.highVersion = high;
    }

    public static OncRpcException newAccepted(int status, long low, long high, String message) {
        return new OncRpcException(message, OncRpcMessage.MSG_ACCEPTED, status, -1, low, high);
    }

    public static OncRpcException newAcceptedError(int status, String message) {
        return newAccepted(status, 0, 0, message);
    }

    public static OncRpcException newProgramMismatch(long low, long high) {
        return newAccepted(OncRpcMessage.PROG_MISMATCH, low, high, "ONC RPC program version is not supported");
    }

    public static OncRpcException newRpcMismatch(long low, long high) {
        String message = "ONC RPC protocol version is not supported";
        return new OncRpcException(message, OncRpcMessage.MSG_DENIED, OncRpcMessage.RPC_MISMATCH, -1, low, high);
    }

    public static OncRpcException newAuthError(int status, String message) {
        return new OncRpcException(message, OncRpcMessage.MSG_DENIED, OncRpcMessage.AUTH_ERROR, status, 0, 0);
    }

    public OncRpcMessage toReply(long xid) {
        if (this.replyStatus == OncRpcMessage.MSG_ACCEPTED) {
            OncRpcAuth verifier = OncRpcAuth.newNone();
            return OncRpcMessage.newAcceptedError(xid, verifier, this.status, this.lowVersion, this.highVersion);
        }
        if (this.status == OncRpcMessage.RPC_MISMATCH) {
            return OncRpcMessage.newRpcMismatch(xid, this.lowVersion, this.highVersion);
        }
        return OncRpcMessage.newAuthError(xid, this.authStatus);
    }

    public int getReplyStatus() {
        return this.replyStatus;
    }

    public int getAcceptStatus() {
        return this.replyStatus == OncRpcMessage.MSG_ACCEPTED ? this.status : -1;
    }

    public int getRejectStatus() {
        return this.replyStatus == OncRpcMessage.MSG_DENIED ? this.status : -1;
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
}
