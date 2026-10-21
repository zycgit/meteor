/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;

/** Applies the same collection, value and nesting limits to generated readers and writers. */
final class ThriftBoundedProtocol extends TProtocolDecorator {
    private final int maxMessageSize;
    private final int maxContainerSize;
    private final int maxDepth;
    private       int depth;

    ThriftBoundedProtocol(TProtocol protocol, int maxMessageSize, int maxContainerSize, int maxDepth) {
        super(protocol);
        this.maxMessageSize = maxMessageSize;
        this.maxContainerSize = maxContainerSize;
        this.maxDepth = maxDepth;
    }

    int checkpoint() {
        return this.depth;
    }

    void restoreDepth(int depth) {
        this.depth = depth;
    }

    private void begin() throws TProtocolException {
        if (++this.depth > this.maxDepth) {
            throw new TProtocolException(TProtocolException.DEPTH_LIMIT, "Thrift nesting exceeds maxDepth");
        }
    }

    @Override
    public TStruct readStructBegin() throws TException {
        this.begin();
        return super.readStructBegin();
    }

    @Override
    public void readStructEnd() throws TException {
        super.readStructEnd();
        --this.depth;
    }

    @Override
    public TMap readMapBegin() throws TException {
        this.begin();
        TMap map = super.readMapBegin();
        checkContainer(map.size);
        if (map.size != 0) {
            checkType(map.keyType);
            checkType(map.valueType);
        }
        return map;
    }

    @Override
    public void readMapEnd() throws TException {
        super.readMapEnd();
        --this.depth;
    }

    @Override
    public TList readListBegin() throws TException {
        this.begin();
        TList list = super.readListBegin();
        checkContainer(list.size);
        checkType(list.elemType);
        return list;
    }

    @Override
    public void readListEnd() throws TException {
        super.readListEnd();
        --this.depth;
    }

    @Override
    public TSet readSetBegin() throws TException {
        this.begin();
        TSet set = super.readSetBegin();
        checkContainer(set.size);
        checkType(set.elemType);
        return set;
    }

    @Override
    public void readSetEnd() throws TException {
        super.readSetEnd();
        --this.depth;
    }

    @Override
    public void writeStructBegin(TStruct struct) throws TException {
        this.begin();
        super.writeStructBegin(struct);
    }

    @Override
    public void writeStructEnd() throws TException {
        super.writeStructEnd();
        --this.depth;
    }

    @Override
    public void writeFieldBegin(TField field) throws TException {
        checkType(field.type);
        super.writeFieldBegin(field);
    }

    @Override
    public void writeMapBegin(TMap map) throws TException {
        this.begin();
        checkContainer(map.size);
        checkType(map.keyType);
        checkType(map.valueType);
        super.writeMapBegin(map);
    }

    @Override
    public void writeMapEnd() throws TException {
        super.writeMapEnd();
        --this.depth;
    }

    @Override
    public void writeListBegin(TList list) throws TException {
        this.begin();
        checkContainer(list.size);
        checkType(list.elemType);
        super.writeListBegin(list);
    }

    @Override
    public void writeListEnd() throws TException {
        super.writeListEnd();
        --this.depth;
    }

    @Override
    public void writeSetBegin(TSet set) throws TException {
        this.begin();
        checkContainer(set.size);
        checkType(set.elemType);
        super.writeSetBegin(set);
    }

    @Override
    public void writeSetEnd() throws TException {
        super.writeSetEnd();
        --this.depth;
    }

    @Override
    public void writeString(String value) throws TException {
        int messageLimit = this.maxMessageSize;
        if (value.length() > messageLimit || value.getBytes(StandardCharsets.UTF_8).length > messageLimit) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Thrift string exceeds maxMessageSize");
        }
        super.writeString(value);
    }

    @Override
    public void writeBinary(ByteBuffer value) throws TException {
        if (value.remaining() > this.maxMessageSize) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Thrift binary exceeds maxMessageSize");
        }
        super.writeBinary(value);
    }

    private void checkContainer(int size) throws TProtocolException {
        if (size < 0 || size > this.maxContainerSize) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Thrift container exceeds maxContainerSize");
        }
    }

    private void checkType(byte type) throws TProtocolException {
        switch (type) {
            case TType.BOOL:
            case TType.BYTE:
            case TType.DOUBLE:
            case TType.I16:
            case TType.I32:
            case TType.I64:
            case TType.STRING:
            case TType.STRUCT:
            case TType.MAP:
            case TType.SET:
            case TType.LIST:
            case TType.UUID:
                return;
            default:
                throw new TProtocolException(TProtocolException.INVALID_DATA, "Invalid Thrift field type: " + type);
        }
    }
}
