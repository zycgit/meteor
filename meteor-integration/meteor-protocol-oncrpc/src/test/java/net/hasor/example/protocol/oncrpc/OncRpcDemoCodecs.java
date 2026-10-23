/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.oncrpc;
import net.hasor.meteor.protocol.oncrpc.binding.OncRpcServiceResource;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodecs;
import net.hasor.meteor.protocol.oncrpc.codec.XdrInput;
import net.hasor.meteor.protocol.oncrpc.codec.XdrOutput;

/** 应用按 IDL 定义字段顺序，资源只在本地绑定，未通过网络传输。 */
public final class OncRpcDemoCodecs implements XdrCodec<OncRpcSample> {
    private static final XdrCodec<OncRpcOptional> OPTIONAL = new XdrCodec<>() {
        @Override
        public OncRpcOptional read(XdrInput input) {
            if (!input.readBoolean()) {
                return null;
            }
            OncRpcOptional value = new OncRpcOptional();
            value.setValue(input.readInt());
            value.setNext(input.read(this));
            return value;
        }

        @Override
        public void write(XdrOutput output, OncRpcOptional value) {
            output.writeBoolean(value != null);
            if (value != null) {
                output.writeInt(value.getValue());
                output.write(this, value.getNext());
            }
        }
    };

    public OncRpcDemoCodecs() {
    }

    public static OncRpcServiceResource resource() {
        return new OncRpcServiceResource(OncRpcDemoService.class).codec(OncRpcSample.class, new OncRpcDemoCodecs());
    }

    @Override
    public OncRpcSample read(XdrInput input) {
        // 固定字段与变长字段严格保持 IDL 声明顺序。
        OncRpcSample value = new OncRpcSample();
        value.setSignedValue(input.readInt());
        value.setUnsignedValue(input.readUnsignedInt());
        value.setSignedHyper(input.readLong());
        value.setUnsignedHyper(input.readUnsignedLong());
        value.setFloatValue(input.readFloat());
        value.setDoubleValue(input.readDouble());
        value.setBoolValue(input.readBoolean());
        value.setText(input.readString(4096));
        value.setFixedOpaque(input.readFixedOpaque(5));
        value.setBytes(input.readOpaque(65536));
        value.setFixedNumbers(input.read(XdrCodecs.fixedArray(XdrCodecs.INT, 3)));
        value.setNumbers(input.read(XdrCodecs.list(XdrCodecs.INT, 128)));
        value.setColour(input.readInt());

        // 判别联合只读取当前分支；默认分支不携带额外数据。
        OncRpcChoice choice = new OncRpcChoice();
        choice.setTag(input.readInt());
        if (choice.getTag() == 1) {
            choice.setNumber(input.readInt());
        } else if (choice.getTag() == 2) {
            choice.setText(input.readString(4096));
        }
        value.setChoice(choice);
        value.setOptional(input.read(OPTIONAL));
        return value;
    }

    @Override
    public void write(XdrOutput output, OncRpcSample value) {
        // 应用选择无符号整数和定长数组，不能依赖 Java 类型推断 IDL 语义。
        output.writeInt(value.getSignedValue());
        output.writeUnsignedInt(value.getUnsignedValue());
        output.writeLong(value.getSignedHyper());
        output.writeUnsignedLong(value.getUnsignedHyper());
        output.writeFloat(value.getFloatValue());
        output.writeDouble(value.getDoubleValue());
        output.writeBoolean(value.getBoolValue());
        output.writeString(value.getText(), 4096);
        output.writeFixedOpaque(value.getFixedOpaque(), 5);
        output.writeOpaque(value.getBytes(), 65536);
        output.write(XdrCodecs.fixedArray(XdrCodecs.INT, 3), value.getFixedNumbers());
        output.write(XdrCodecs.list(XdrCodecs.INT, 128), value.getNumbers());
        output.writeInt(value.getColour());

        // 联合与可选递归数据通过当前 XDR 容器的深度限制进行读取和写入。
        OncRpcChoice choice = value.getChoice();
        output.writeInt(choice.getTag());
        if (choice.getTag() == 1) {
            output.writeInt(choice.getNumber());
        } else if (choice.getTag() == 2) {
            output.writeString(choice.getText(), 4096);
        }
        output.write(OPTIONAL, value.getOptional());
    }
}
