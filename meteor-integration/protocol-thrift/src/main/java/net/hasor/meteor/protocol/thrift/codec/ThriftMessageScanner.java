/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.util.ArrayDeque;
import java.util.Deque;
import net.hasor.cobble.StringUtils;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TTransportException;

/** Retains completed official protocol reads across fragments instead of rescanning the whole message. */
final class ThriftMessageScanner {
    private final Deque<ScanTask>       tasks = new ArrayDeque<>();
    private       String                encoding;
    private       ThriftInputTransport  input;
    private       ThriftBoundedProtocol protocol;
    private       boolean               header;
    private       int                   jsonPosition;
    private       int                   jsonNesting;
    private       boolean               jsonString;
    private       boolean               jsonEscaped;

    private final ThriftWireCodec wire;
    private final TConfiguration  configuration;
    private final int             maxMessageSize;
    private final int             maxDepth;

    ThriftMessageScanner(ThriftWireCodec wire, TConfiguration configuration) {
        this.wire = wire;
        this.configuration = configuration;
        this.maxMessageSize = configuration.getMaxMessageSize();
        this.maxDepth = configuration.getRecursionLimit() - 1;
    }

    int scan(byte[] bytes, int offset, int length) throws TException {
        if (length == 0) {
            return 0;
        }
        if (this.encoding == null) {
            this.encoding = this.wire.detectEncoding(bytes[offset]);
        }
        if (StringUtils.equals(this.encoding, "json")) {
            return this.scanJson(bytes, offset, length);
        }
        if (this.input == null) {
            this.input = new ThriftInputTransport(bytes, offset, length, this.configuration);
            this.protocol = this.wire.createBoundedProtocol(this.encoding, this.input);
        } else {
            this.input.update(bytes, offset, length);
        }
        while (true) {
            int position = this.input.position();
            long remaining = this.input.remainingBudget();
            int depth = this.protocol.checkpoint();
            try {
                if (!this.header) {
                    this.wire.checkMessage(this.protocol.readMessageBegin());
                    this.header = true;
                    this.tasks.push(ScanTask.value(TType.STRUCT));
                } else if (this.tasks.isEmpty()) {
                    this.protocol.readMessageEnd();
                    return this.input.position() - offset;
                } else {
                    this.advance();
                }
            } catch (TTransportException failure) {
                if (failure.getType() != TTransportException.END_OF_FILE) {
                    throw failure;
                }
                // Binary and Compact update protocol state only after each atomic read succeeds.
                this.input.restore(position, remaining);
                this.protocol.restoreDepth(depth);
                if (length >= this.maxMessageSize) {
                    String message = "Incomplete or oversized Thrift message";
                    throw new TProtocolException(TProtocolException.SIZE_LIMIT, message);
                }
                return 0;
            } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
                throw new TProtocolException(TProtocolException.INVALID_DATA, "Malformed Thrift message", failure);
            }
        }
    }

    private void advance() throws TException {
        ScanTask task = this.tasks.peek();
        if (task.kind == ScanTask.FIELD_END) {
            this.protocol.readFieldEnd();
            this.tasks.pop();
        } else if (task.kind == ScanTask.FIELDS) {
            TField field = this.protocol.readFieldBegin();
            if (field.type == TType.STOP) {
                this.protocol.readStructEnd();
                this.tasks.pop();
            } else {
                this.tasks.push(ScanTask.fieldEnd());
                this.tasks.push(ScanTask.value(field.type));
            }
        } else if (task.kind == ScanTask.ELEMENTS) {
            if (task.remaining == 0) {
                if (task.type == TType.MAP) {
                    this.protocol.readMapEnd();
                } else if (task.type == TType.SET) {
                    this.protocol.readSetEnd();
                } else {
                    this.protocol.readListEnd();
                }
                this.tasks.pop();
            } else {
                byte type = task.first;
                if (task.type == TType.MAP && task.remaining % 2 != 0) {
                    type = task.second;
                }
                --task.remaining;
                this.tasks.push(ScanTask.value(type));
            }
        } else {
            this.value(task);
        }
    }

    private void value(ScanTask task) throws TException {
        switch (task.type) {
            case TType.STRUCT:
                this.protocol.readStructBegin();
                task.kind = ScanTask.FIELDS;
                return;
            case TType.MAP:
                TMap map = this.protocol.readMapBegin();
                task.elements(map.keyType, map.valueType, map.size * 2L);
                return;
            case TType.LIST:
                TList list = this.protocol.readListBegin();
                task.elements(list.elemType, TType.STOP, list.size);
                return;
            case TType.SET:
                TSet set = this.protocol.readSetBegin();
                task.elements(set.elemType, TType.STOP, set.size);
                return;
            case TType.BOOL:
                this.protocol.readBool();
                break;
            case TType.BYTE:
                this.protocol.readByte();
                break;
            case TType.I16:
                this.protocol.readI16();
                break;
            case TType.I32:
                this.protocol.readI32();
                break;
            case TType.I64:
                this.protocol.readI64();
                break;
            case TType.DOUBLE:
                this.protocol.readDouble();
                break;
            case TType.UUID:
                this.protocol.readUuid();
                break;
            case TType.STRING:
                this.protocol.readBinary();
                break;
            default:
                throw new TProtocolException("Invalid Thrift field type: " + task.type);
        }
        this.tasks.pop();
    }

    private int scanJson(byte[] bytes, int offset, int length) throws TProtocolException {
        long syntaxLimit = (long) this.maxDepth * 3 + 4;
        while (this.jsonPosition < length) {
            int value = bytes[offset + this.jsonPosition++] & 0xff;
            if (this.jsonString) {
                if (this.jsonEscaped) {
                    this.jsonEscaped = false;
                } else if (value == '\\') {
                    this.jsonEscaped = true;
                } else if (value == '"') {
                    this.jsonString = false;
                } else if (value < 0x20) {
                    throw new TProtocolException("Unescaped control character in Thrift JSON string");
                }
            } else if (value == '"') {
                this.jsonString = true;
            } else if (value == '[' || value == '{') {
                if (++this.jsonNesting > syntaxLimit) {
                    String message = "Thrift JSON nesting exceeds maxDepth";
                    throw new TProtocolException(TProtocolException.DEPTH_LIMIT, message);
                }
            } else if (value == ']' || value == '}') {
                if (--this.jsonNesting == 0) {
                    return this.jsonPosition;
                }
            }
        }
        if (length >= this.maxMessageSize) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Thrift message exceeds maxMessageSize");
        }
        return 0;
    }

    private static final class ScanTask {
        private static final int  VALUE     = 0;
        private static final int  FIELDS    = 1;
        private static final int  FIELD_END = 2;
        private static final int  ELEMENTS  = 3;
        private              int  kind;
        private final        byte type;
        private              byte first;
        private              byte second;
        private              long remaining;

        private ScanTask(int kind, byte type) {
            this.kind = kind;
            this.type = type;
        }

        private static ScanTask value(byte type) {
            return new ScanTask(VALUE, type);
        }

        private static ScanTask fieldEnd() {
            return new ScanTask(FIELD_END, TType.STOP);
        }

        private void elements(byte first, byte second, long count) {
            this.kind = ELEMENTS;
            this.first = first;
            this.second = second;
            this.remaining = count;
        }
    }
}
