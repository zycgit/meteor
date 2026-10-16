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

    public static byte[] reencodeBody(byte[] frame) throws IOException {
        // 按原始结构重新编码，始终释放解码得到的数据池。
        RsfV1ResponseCodec codec = new RsfV1ResponseCodec();
        RsfV1ResponseFrame response = codec.decode(RsfV1WireBuffer.input(frame));
        try {
            RsfV1WireBuffer output = RsfV1WireBuffer.output();
            codec.encode(response, output);
            return output.toByteArray();
        } finally {
            response.release();
        }
    }

    public static int returnDataSize(byte[] frame) throws IOException {
        RsfV1ResponseFrame response = new RsfV1ResponseCodec().decode(RsfV1WireBuffer.input(frame));
        try {
            return response.getValue(response.getReturnDataIndex()).length;
        } finally {
            response.release();
        }
    }

    public static byte[] poolWithLength(int length) {
        RsfV1WireBuffer output = RsfV1WireBuffer.output();
        output.writeShort(1).writeInt(length);
        return output.toByteArray();
    }

    public static final class Pool implements AutoCloseable {
        private final RsfV1AttributePool pool = new RsfV1AttributePool();

        public void push(byte[] data) {
            this.pool.addValue(data);
        }

        public byte[] read(short index) {
            return this.pool.getValue(index);
        }

        public byte[] encode() {
            RsfV1WireBuffer output = RsfV1WireBuffer.output();
            this.pool.writeTo(output);
            return output.toByteArray();
        }

        public void decode(byte[] data) {
            this.pool.readFrom(RsfV1WireBuffer.input(data));
        }

        public void release() {
            this.pool.release();
        }

        @Override
        public void close() {
            this.pool.release();
        }
    }
}
