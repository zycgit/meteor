/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.dubbo.codec;
import java.io.IOException;
import java.nio.ByteBuffer;

/** 每条连接持有一个增量解码器，先验证帧头，再按正文长度收集分片。 */
public final class DubboFrameDecoder {
    private final int    maximum;
    private final byte[] header = new byte[16];
    private       int    headerOffset;
    private       byte[] body;
    private       int    bodyOffset;

    public DubboFrameDecoder(int maximum) {
        if (maximum <= 0) {
            throw new IllegalArgumentException("maxFrameSize must be positive");
        }
        this.maximum = maximum;
    }

    /** 消耗本次网络数据；不足一帧时保留分片，完整后返回一帧。 */
    public DubboFrame readFrame(ByteBuffer input) throws IOException {
        if (this.headerOffset < this.header.length) {
            int count = Math.min(input.remaining(), this.header.length - this.headerOffset);
            input.get(this.header, this.headerOffset, count);
            this.headerOffset += count;
            if (this.headerOffset < this.header.length) {
                return null;
            }

            // 声明长度在分配正文数组之前检查，畸形帧不能要求超额内存。
            if ((this.header[0] & 0xff) != 0xda || (this.header[1] & 0xff) != 0xbb) {
                throw new IOException("Invalid Dubbo magic");
            }
            int size = ByteBuffer.wrap(this.header, 12, 4).getInt();
            if (size < 0 || size > this.maximum) {
                throw new IOException("Dubbo body exceeds configured maxFrameSize");
            }
            this.body = new byte[size];
        }

        // 粘包只取当前正文，调用方继续读取同一个 ByteBuffer 获取下一帧。
        int count = Math.min(input.remaining(), this.body.length - this.bodyOffset);
        input.get(this.body, this.bodyOffset, count);
        this.bodyOffset += count;
        if (this.bodyOffset < this.body.length) {
            return null;
        }
        long requestId = ByteBuffer.wrap(this.header, 4, 8).getLong();
        DubboFrame frame = new DubboFrame(this.header[2], this.header[3], requestId, this.body);
        this.reset();
        return frame;
    }

    public void reset() {
        this.headerOffset = 0;
        this.body = null;
        this.bodyOffset = 0;
    }
}
