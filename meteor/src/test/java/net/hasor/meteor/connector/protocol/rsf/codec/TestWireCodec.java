/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.connector.protocol.rsf.codec;
import java.io.IOException;

/** 测试源码中的薄访问适配器，只转发包内报文和数据池操作。 */
public final class TestWireCodec {
    private TestWireCodec() {
    }

    public static byte[] reencodeResponse(byte[] frame) throws IOException {
        // 按原始结构重新编码，始终释放解码得到的数据池。
        RpcResponseProtocolV1 protocol = new RpcResponseProtocolV1();
        ResponseBlock response = protocol.decode(WireBuffer.input(frame));
        try {
            WireBuffer output = WireBuffer.output();
            protocol.encode(response, output);
            return output.toByteArray();
        } finally {
            response.release();
        }
    }

    public static int returnDataSize(byte[] frame) throws IOException {
        ResponseBlock response = new RpcResponseProtocolV1().decode(WireBuffer.input(frame));
        try {
            return response.readPool(response.getReturnData()).length;
        } finally {
            response.release();
        }
    }

    public static byte[] poolWithLength(int length) {
        WireBuffer output = WireBuffer.output();
        output.writeShort(1).writeInt(length);
        return output.toByteArray();
    }

    public static final class Pool implements AutoCloseable {
        private final PoolBlock block = new PoolBlock();

        public void push(byte[] data) {
            this.block.pushData(data);
        }

        public byte[] read(short index) {
            return this.block.readPool(index);
        }

        public byte[] encode() {
            WireBuffer output = WireBuffer.output();
            this.block.fillTo(output);
            return output.toByteArray();
        }

        public void decode(byte[] data) {
            this.block.fillFrom(WireBuffer.input(data));
        }

        public void release() {
            this.block.release();
        }

        @Override
        public void close() {
            this.block.release();
        }
    }
}
