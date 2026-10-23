/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;

/** Encode RPC v2 envelopes; procedure arguments and results remain application XDR bytes. */
public final class OncRpcWireCodec {
    private final int maxMessageSize;

    public OncRpcWireCodec(int maxMessageSize) {
        if (maxMessageSize < 40) {
            throw new IllegalArgumentException("Maximum ONC RPC message size must be at least 40 bytes");
        }
        this.maxMessageSize = maxMessageSize;
    }

    public byte[] encode(OncRpcMessage message) {
        // 公共前缀使用无符号 XID，避免截断最高位。
        XdrOutput output = new XdrOutput(this.maxMessageSize, OncRpcAuth.MAX_AUTH_BYTES, 8);
        output.writeUnsignedInt(message.getXid());
        output.writeInt(message.getMessageType());
        if (message.isCall()) {
            output.writeUnsignedInt(message.getRpcVersion());
            output.writeUnsignedInt(message.getProgram());
            output.writeUnsignedInt(message.getVersion());
            output.writeUnsignedInt(message.getProcedure());
            this.writeAuth(output, message.getCredentials());
            this.writeAuth(output, message.getVerifier());
            output.writeBytes(message.getBody());
            return output.toByteArray();
        }

        // 成功、接受后失败和拒绝分别遵循各自的 discriminated union 分支。
        output.writeInt(message.getReplyStatus());
        if (message.getReplyStatus() == OncRpcMessage.MSG_ACCEPTED) {
            this.writeAuth(output, message.getVerifier());
            output.writeInt(message.getAcceptStatus());
            if (message.getAcceptStatus() == OncRpcMessage.SUCCESS) {
                output.writeBytes(message.getBody());
            } else if (message.getAcceptStatus() == OncRpcMessage.PROG_MISMATCH) {
                output.writeUnsignedInt(message.getLowVersion());
                output.writeUnsignedInt(message.getHighVersion());
            }
        } else {
            output.writeInt(message.getRejectStatus());
            if (message.getRejectStatus() == OncRpcMessage.RPC_MISMATCH) {
                output.writeUnsignedInt(message.getLowVersion());
                output.writeUnsignedInt(message.getHighVersion());
            } else {
                output.writeInt(message.getAuthStatus());
            }
        }
        return output.toByteArray();
    }

    public OncRpcMessage decode(byte[] data) {
        XdrInput input = new XdrInput(data, this.maxMessageSize, OncRpcAuth.MAX_AUTH_BYTES, 8);
        long xid = input.readUnsignedInt();
        int messageType = input.readInt();
        if (messageType == OncRpcMessage.CALL) {
            return this.readCall(xid, input);
        }
        if (messageType != OncRpcMessage.REPLY) {
            throw new IllegalArgumentException("Invalid ONC RPC message type: " + messageType);
        }

        // 错误回执没有应用正文，要求完整消费以拒绝附加的伪造字段。
        int replyStatus = input.readInt();
        if (replyStatus == OncRpcMessage.MSG_ACCEPTED) {
            return this.readAccepted(xid, input);
        }
        if (replyStatus == OncRpcMessage.MSG_DENIED) {
            return this.readRejected(xid, input);
        }
        throw new IllegalArgumentException("Invalid ONC RPC reply status: " + replyStatus);
    }

    private OncRpcMessage readCall(long xid, XdrInput input) {
        long rpcVersion = input.readUnsignedInt();
        if (rpcVersion != 2) {
            // 不兼容版本的剩余布局未被本协议定义，保留正文交给会话返回 RPC_MISMATCH。
            byte[] body = input.readRemaining();
            return OncRpcMessage.newCall(xid, rpcVersion, 0, 0, 0, OncRpcAuth.newNone(), OncRpcAuth.newNone(), body);
        }
        long program = input.readUnsignedInt();
        long version = input.readUnsignedInt();
        long procedure = input.readUnsignedInt();
        OncRpcAuth credentials = this.readAuth(input);
        OncRpcAuth verifier = this.readAuth(input);
        byte[] body = input.readRemaining();
        return OncRpcMessage.newCall(xid, rpcVersion, program, version, procedure, credentials, verifier, body);
    }

    private OncRpcMessage readAccepted(long xid, XdrInput input) {
        OncRpcAuth verifier = this.readAuth(input);
        int status = input.readInt();
        if (status == OncRpcMessage.SUCCESS) {
            return OncRpcMessage.newSuccess(xid, verifier, input.readRemaining());
        }
        long low = 0;
        long high = 0;
        if (status == OncRpcMessage.PROG_MISMATCH) {
            low = input.readUnsignedInt();
            high = input.readUnsignedInt();
        }
        input.requireEnd();
        return OncRpcMessage.newAcceptedError(xid, verifier, status, low, high);
    }

    private OncRpcMessage readRejected(long xid, XdrInput input) {
        int status = input.readInt();
        OncRpcMessage result;
        if (status == OncRpcMessage.RPC_MISMATCH) {
            long low = input.readUnsignedInt();
            long high = input.readUnsignedInt();
            result = OncRpcMessage.newRpcMismatch(xid, low, high);
        } else if (status == OncRpcMessage.AUTH_ERROR) {
            result = OncRpcMessage.newAuthError(xid, input.readInt());
        } else {
            throw new IllegalArgumentException("Invalid ONC RPC rejection status: " + status);
        }
        input.requireEnd();
        return result;
    }

    private OncRpcAuth readAuth(XdrInput input) {
        int flavor = input.readInt();
        return new OncRpcAuth(flavor, input.readOpaque(OncRpcAuth.MAX_AUTH_BYTES));
    }

    private void writeAuth(XdrOutput output, OncRpcAuth authentication) {
        output.writeInt(authentication.getFlavor());
        output.writeOpaque(authentication.getBody(), OncRpcAuth.MAX_AUTH_BYTES);
    }
}
