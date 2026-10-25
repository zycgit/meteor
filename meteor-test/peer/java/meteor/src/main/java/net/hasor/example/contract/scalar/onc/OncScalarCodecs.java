/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.scalar.onc;
import java.math.BigDecimal;
import java.util.Date;
import net.hasor.example.contract.scalar.ScalarDecimal;
import net.hasor.example.contract.scalar.ScalarKind;
import net.hasor.example.contract.scalar.ScalarMoment;
import net.hasor.example.contract.scalar.ScalarValue;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodecs;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;

/** 按 values.x 逐字段编码；ASCII 文本最多 256 字节，note 用布尔 presence 保留 null。 */
public final class OncScalarCodecs {
    private static final XdrCodec<String>        TEXT    = XdrCodecs.string(256);
    private static final XdrCodec<String>        NOTE    = XdrCodecs.optional(TEXT);
    private static final XdrCodec<ScalarKind>    KIND    = XdrCodecs.enumeration(wire -> switch (wire) {
        case 0 -> ScalarKind.RETAIL;
        case 1 -> ScalarKind.WHOLESALE;
        default -> throw new IllegalArgumentException("Unknown scalar kind: " + wire);
    }, kind -> switch (kind) {
        case RETAIL -> 0;
        case WHOLESALE -> 1;
    });
    private static final XdrCodec<ScalarValue>   VALUE   = new XdrCodec<>() {
        @Override
        public ScalarValue read(XdrInput input) {
            ScalarValue value = new ScalarValue();
            value.setFlag(input.readBoolean());
            value.setInteger(input.readLong());
            value.setSingle(input.readFloat());
            value.setDecimal(input.readDouble());
            value.setKind(input.read(KIND));
            value.setNote(input.read(NOTE));
            value.setReceipt(input.read(TEXT));
            return value;
        }

        @Override
        public void write(XdrOutput output, ScalarValue value) {
            output.writeBoolean(value.isFlag());
            output.writeLong(value.getInteger());
            output.writeFloat(value.getSingle());
            output.writeDouble(value.getDecimal());
            output.write(KIND, value.getKind());
            output.write(NOTE, value.getNote());
            output.write(TEXT, value.getReceipt());
        }
    };
    private static final XdrCodec<ScalarMoment>  MOMENT  = new XdrCodec<>() {
        @Override
        public ScalarMoment read(XdrInput input) {
            ScalarMoment value = new ScalarMoment();
            value.setInstant(new Date(input.readLong()));
            value.setReceipt(input.read(TEXT));
            return value;
        }

        @Override
        public void write(XdrOutput output, ScalarMoment value) {
            // Date 使用有符号 epoch 毫秒，不经过时区或秒级截断。
            output.writeLong(value.getInstant().getTime());
            output.write(TEXT, value.getReceipt());
        }
    };
    private static final XdrCodec<ScalarDecimal> DECIMAL = new XdrCodec<>() {
        @Override
        public ScalarDecimal read(XdrInput input) {
            ScalarDecimal value = new ScalarDecimal();
            value.setAmount(new BigDecimal(input.read(TEXT)));
            value.setReceipt(input.read(TEXT));
            return value;
        }

        @Override
        public void write(XdrOutput output, ScalarDecimal value) {
            // 十进制字符串保留 precision 和 scale，包括负 scale 的指数写法。
            output.write(TEXT, value.getAmount().toString());
            output.write(TEXT, value.getReceipt());
        }
    };

    private OncScalarCodecs() {
    }

    public static OncRpcServiceResource resource() {
        return new OncRpcServiceResource(OncScalarService.class) //
                .codec(ScalarValue.class, VALUE) //
                .codec(ScalarMoment.class, MOMENT) //
                .codec(ScalarDecimal.class, DECIMAL);
    }
}
