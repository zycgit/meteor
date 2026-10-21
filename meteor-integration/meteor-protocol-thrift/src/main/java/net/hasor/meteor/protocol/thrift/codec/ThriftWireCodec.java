/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import net.hasor.cobble.StringUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TBase;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.TEndpointTransport;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

/** Standard Thrift protocols with bounded message, collection and nesting sizes. */
public final class ThriftWireCodec {
    private static final ObjectMapper JSON = JsonMapper.builder() //
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION) //
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS) //
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final int maxMessageSize;
    private final int maxContainerSize;
    private final int maxDepth;

    public ThriftWireCodec(int maxMessageSize, int maxContainerSize, int maxDepth) {
        if (maxMessageSize <= 0 || maxContainerSize <= 0 || maxDepth <= 0 || maxDepth > 256) {
            throw new IllegalArgumentException("Thrift limits must be positive and maxDepth must not exceed 256");
        }
        this.maxMessageSize = maxMessageSize;
        this.maxContainerSize = maxContainerSize;
        this.maxDepth = maxDepth;
    }

    public TProtocol createProtocol(String encoding, TTransport transport) throws TException {
        return this.createBoundedProtocol(encoding, transport);
    }

    ThriftBoundedProtocol createBoundedProtocol(String encoding, TTransport transport) throws TException {
        Objects.requireNonNull(transport, "transport");
        TProtocol protocol;
        // libthrift 0.25 supports UUID values but omits their minimum size from its collection guards.
        if (StringUtils.equals(encoding, "binary")) {
            protocol = new TBinaryProtocol(transport, this.maxMessageSize, this.maxContainerSize, false, true) {
                @Override
                public int getMinSerializedSize(byte type) throws TTransportException {
                    return type == TType.UUID ? 16 : super.getMinSerializedSize(type);
                }

                @Override
                protected void checkReadBytesAvailable(TMap map) throws TException {
                    checkContainer(map.size);
                    super.checkReadBytesAvailable(map);
                }

                @Override
                protected void checkReadBytesAvailable(TList list) throws TException {
                    checkContainer(list.size);
                    super.checkReadBytesAvailable(list);
                }

                @Override
                protected void checkReadBytesAvailable(TSet set) throws TException {
                    checkContainer(set.size);
                    super.checkReadBytesAvailable(set);
                }
            };
        } else if (StringUtils.equals(encoding, "compact")) {
            protocol = new TCompactProtocol(transport, this.maxMessageSize, this.maxContainerSize) {
                @Override
                public int getMinSerializedSize(byte type) throws TTransportException {
                    return type == TType.UUID ? 16 : super.getMinSerializedSize(type);
                }
            };
        } else if (StringUtils.equals(encoding, "json")) {
            protocol = new TJSONProtocol(transport) {
                @Override
                public int getMinSerializedSize(byte type) throws TTransportException {
                    return type == TType.UUID ? 2 : super.getMinSerializedSize(type);
                }
            };
        } else {
            throw new TProtocolException("Unsupported Thrift encoding: " + encoding);
        }
        return new ThriftBoundedProtocol(protocol, this.maxMessageSize, this.maxContainerSize, this.maxDepth);
    }

    /** Returns the first message size, or zero while more bytes are needed. */
    public int scan(byte[] bytes, int offset, int length) throws TException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return 0;
        }
        String encoding = this.detectEncoding(bytes[offset]);
        int available = Math.min(length, this.maxMessageSize);
        if (StringUtils.equals(encoding, "json")) {
            int complete = this.jsonLength(bytes, offset, available);
            if (complete == 0) {
                if (length >= this.maxMessageSize) {
                    String message = "Thrift message exceeds maxMessageSize";
                    throw new TProtocolException(TProtocolException.SIZE_LIMIT, message);
                }
                return 0;
            }
            this.validateJson(bytes, offset, complete);
            available = complete;
        }
        ThriftInputTransport input = new ThriftInputTransport(bytes, offset, available, this.configuration());
        TProtocol protocol = this.createProtocol(encoding, input);
        try {
            this.checkMessage(protocol.readMessageBegin());
            TProtocolUtil.skip(protocol, TType.STRUCT, this.maxDepth + 1);
            protocol.readMessageEnd();
            return input.position() - offset;
        } catch (TTransportException failure) {
            if (failure.getType() != TTransportException.END_OF_FILE) {
                throw failure;
            }
            if (length >= this.maxMessageSize || StringUtils.equals(encoding, "json")) {
                String message = "Incomplete or oversized Thrift message";
                throw new TProtocolException(TProtocolException.SIZE_LIMIT, message, failure);
            }
            return 0;
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new TProtocolException(TProtocolException.INVALID_DATA, "Malformed Thrift message", failure);
        }
    }

    public ThriftMessage decode(byte[] bytes) throws TException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > this.maxMessageSize) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Empty or oversized Thrift message");
        }
        int consumed = this.scan(bytes, 0, bytes.length);
        if (consumed != bytes.length) {
            throw new TProtocolException(TProtocolException.INVALID_DATA, "Truncated Thrift message or trailing bytes");
        }
        String encoding = this.detectEncoding(bytes[0]);
        ThriftInputTransport input = new ThriftInputTransport(bytes, 0, bytes.length, this.configuration());
        TMessage message = this.createProtocol(encoding, input).readMessageBegin();
        return new ThriftMessage(encoding, message.name, message.type, message.seqid, bytes);
    }

    public byte[] encode(String encoding, String name, byte type, int sequenceId, TBase<?, ?> body) throws TException {
        Objects.requireNonNull(body, "body");
        return this.write(encoding, new TMessage(name, type, sequenceId), body::write);
    }

    public byte[] encodeException(String encoding, String name, int sequenceId, //
            TApplicationException failure) throws TException {
        Objects.requireNonNull(failure, "failure");
        return this.write(encoding, new TMessage(name, TMessageType.EXCEPTION, sequenceId), failure::write);
    }

    ThriftMessageScanner newScanner() {
        return new ThriftMessageScanner(this, this.configuration());
    }

    private byte[] write(String encoding, TMessage message, BodyWriter writer) throws TException {
        this.checkMessage(message);
        LimitedOutput output = new LimitedOutput(this.configuration());
        TProtocol protocol = this.createProtocol(encoding, output);
        protocol.writeMessageBegin(message);
        writer.write(protocol);
        protocol.writeMessageEnd();
        return output.bytes.toByteArray();
    }

    private TConfiguration configuration() {
        return new TConfiguration(this.maxMessageSize, this.maxMessageSize, this.maxDepth + 1);
    }

    String detectEncoding(byte first) throws TProtocolException {
        if (first == (byte) 0x82) {
            return "compact";
        }
        if (first == '[') {
            return "json";
        }
        if (first == (byte) 0x80 || first >= 0 && first <= 0x7f) {
            return "binary";
        }
        throw new TProtocolException(TProtocolException.BAD_VERSION, "Unknown Thrift message encoding");
    }

    void checkMessage(TMessage message) throws TProtocolException {
        if (message.name == null || message.name.isEmpty()) {
            throw new TProtocolException(TProtocolException.INVALID_DATA, "Thrift method name must not be empty");
        }
        if (message.name.length() > this.maxMessageSize //
                || message.name.getBytes(StandardCharsets.UTF_8).length > this.maxMessageSize) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Thrift method name exceeds maxMessageSize");
        }
        if (message.type < TMessageType.CALL || message.type > TMessageType.ONEWAY) {
            throw new TProtocolException(TProtocolException.INVALID_DATA, "Invalid Thrift message type");
        }
    }

    private void checkContainer(int size) throws TProtocolException {
        if (size < 0 || size > this.maxContainerSize) {
            throw new TProtocolException(TProtocolException.SIZE_LIMIT, "Thrift container exceeds maxContainerSize");
        }
    }

    /** Finds a JSON message boundary without materializing an unbounded object graph. */
    private int jsonLength(byte[] bytes, int offset, int length) throws TProtocolException {
        int nesting = 0;
        boolean string = false;
        boolean escaped = false;
        long syntaxLimit = (long) this.maxDepth * 3 + 4;
        for (int index = offset; index < offset + length; index++) {
            int value = bytes[index] & 0xff;
            if (string) {
                if (escaped) {
                    escaped = false;
                } else if (value == '\\') {
                    escaped = true;
                } else if (value == '"') {
                    string = false;
                } else if (value < 0x20) {
                    throw new TProtocolException("Unescaped control character in Thrift JSON string");
                }
            } else if (value == '"') {
                string = true;
            } else if (value == '[' || value == '{') {
                if (++nesting > syntaxLimit) {
                    String message = "Thrift JSON nesting exceeds maxDepth";
                    throw new TProtocolException(TProtocolException.DEPTH_LIMIT, message);
                }
            } else if (value == ']' || value == '}') {
                if (--nesting == 0) {
                    return index - offset + 1;
                }
            }
        }
        return 0;
    }

    private void validateJson(byte[] bytes, int offset, int length) throws TException {
        JsonNode message;
        try {
            message = JSON.readTree(new ByteArrayInputStream(bytes, offset, length));
        } catch (IOException failure) {
            throw new TProtocolException(TProtocolException.INVALID_DATA, "Malformed Thrift JSON", failure);
        }
        if (message == null || !message.isArray() || message.size() != 5) {
            throw new TProtocolException("Thrift JSON message must contain five elements");
        }
        this.jsonInteger(message.get(0), 1, 1);
        if (!message.get(1).isTextual() || message.get(1).textValue().isEmpty()) {
            throw new TProtocolException("Invalid Thrift JSON method name");
        }
        this.jsonInteger(message.get(2), TMessageType.CALL, TMessageType.ONEWAY);
        this.jsonInteger(message.get(3), Integer.MIN_VALUE, Integer.MAX_VALUE);
        this.jsonStruct(message.get(4), 1);
    }

    private long jsonInteger(JsonNode value, long minimum, long maximum) throws TProtocolException {
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new TProtocolException("Thrift JSON integer must be an exact integer");
        }
        long number = value.longValue();
        if (number < minimum || number > maximum) {
            throw new TProtocolException("Thrift JSON integer is out of range");
        }
        return number;
    }

    private void jsonStruct(JsonNode value, int depth) throws TException {
        this.jsonDepth(depth);
        if (!value.isObject()) {
            throw new TProtocolException("Thrift JSON struct must be an object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            this.jsonKeyInteger(field.getKey(), Short.MIN_VALUE, Short.MAX_VALUE);
            JsonNode typed = field.getValue();
            if (!typed.isObject() || typed.size() != 1) {
                throw new TProtocolException("Thrift JSON field must have exactly one type");
            }
            Map.Entry<String, JsonNode> entry = typed.fields().next();
            this.jsonValue(entry.getKey(), entry.getValue(), depth + 1, false);
        }
    }

    private void jsonDepth(int depth) throws TProtocolException {
        if (depth > this.maxDepth) {
            throw new TProtocolException(TProtocolException.DEPTH_LIMIT, "Thrift nesting exceeds maxDepth");
        }
    }

    private void jsonValue(String type, JsonNode value, int depth, boolean mapKey) throws TException {
        switch (type) {
            case "tf":
                this.jsonNumber(value, 0, 1, mapKey);
                return;
            case "i8":
                this.jsonNumber(value, Byte.MIN_VALUE, Byte.MAX_VALUE, mapKey);
                return;
            case "i16":
                this.jsonNumber(value, Short.MIN_VALUE, Short.MAX_VALUE, mapKey);
                return;
            case "i32":
                this.jsonNumber(value, Integer.MIN_VALUE, Integer.MAX_VALUE, mapKey);
                return;
            case "i64":
                this.jsonNumber(value, Long.MIN_VALUE, Long.MAX_VALUE, mapKey);
                return;
            case "dbl":
                if (value.isNumber() && !mapKey) {
                    if (!Double.isFinite(value.doubleValue())) {
                        throw new TProtocolException("Thrift JSON double overflows");
                    }
                    return;
                }
                if (value.isTextual()) {
                    String text = value.textValue();
                    boolean special = StringUtils.equals(text, "NaN") || StringUtils.equals(text, "Infinity") //
                            || StringUtils.equals(text, "-Infinity");
                    if (special || mapKey && this.jsonDoubleKey(text)) {
                        return;
                    }
                }
                throw new TProtocolException("Invalid Thrift JSON double");
            case "str":
            case "uid":
                if (!value.isTextual()) {
                    throw new TProtocolException("Thrift JSON string must be textual");
                }
                return;
            case "rec":
                if (mapKey) {
                    throw new TProtocolException("Complex Thrift JSON map key is not supported");
                }
                this.jsonStruct(value, depth);
                return;
            case "lst":
            case "set":
                this.jsonDepth(depth);
                if (mapKey || !value.isArray() || value.size() < 2 || !value.get(0).isTextual()) {
                    throw new TProtocolException("Invalid Thrift JSON collection");
                }
                long size = this.jsonInteger(value.get(1), 0, this.maxContainerSize);
                if (size != value.size() - 2L) {
                    throw new TProtocolException("Thrift JSON collection size does not match its elements");
                }
                String elementType = value.get(0).textValue();
                this.jsonType(elementType);
                for (int index = 2; index < value.size(); index++) {
                    this.jsonValue(elementType, value.get(index), depth + 1, false);
                }
                return;
            case "map":
                this.jsonDepth(depth);
                if (mapKey || !value.isArray() || value.size() != 4 || !value.get(0).isTextual() //
                        || !value.get(1).isTextual() || !value.get(3).isObject()) {
                    throw new TProtocolException("Invalid Thrift JSON map");
                }
                long entries = this.jsonInteger(value.get(2), 0, this.maxContainerSize);
                JsonNode map = value.get(3);
                if (entries != map.size()) {
                    throw new TProtocolException("Thrift JSON map size does not match its entries");
                }
                String keyType = value.get(0).textValue();
                String valueType = value.get(1).textValue();
                this.jsonType(keyType);
                this.jsonType(valueType);
                Iterator<Map.Entry<String, JsonNode>> iterator = map.fields();
                while (iterator.hasNext()) {
                    Map.Entry<String, JsonNode> entry = iterator.next();
                    this.jsonValue(keyType, JSON.valueToTree(entry.getKey()), depth + 1, true);
                    this.jsonValue(valueType, entry.getValue(), depth + 1, false);
                }
                return;
            default:
                throw new TProtocolException("Unknown Thrift JSON type: " + type);
        }
    }

    private boolean jsonDoubleKey(String text) {
        try {
            return Double.isFinite(Double.parseDouble(text));
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    private void jsonNumber(JsonNode value, long minimum, long maximum, boolean mapKey) throws TProtocolException {
        if (mapKey && value.isTextual()) {
            this.jsonKeyInteger(value.textValue(), minimum, maximum);
        } else {
            this.jsonInteger(value, minimum, maximum);
        }
    }

    private void jsonKeyInteger(String text, long minimum, long maximum) throws TProtocolException {
        try {
            long value = Long.parseLong(text);
            if (value < minimum || value > maximum || !StringUtils.equals(text, Long.toString(value))) {
                throw new NumberFormatException("out of range or noncanonical integer");
            }
        } catch (NumberFormatException failure) {
            throw new TProtocolException(TProtocolException.INVALID_DATA, "Invalid Thrift JSON integer key", failure);
        }
    }

    private void jsonType(String type) throws TProtocolException {
        switch (type) {
            case "tf":
            case "i8":
            case "i16":
            case "i32":
            case "i64":
            case "dbl":
            case "str":
            case "uid":
            case "rec":
            case "lst":
            case "set":
            case "map":
                return;
            default:
                throw new TProtocolException("Unknown Thrift JSON type: " + type);
        }
    }

    private interface BodyWriter {
        void write(TProtocol protocol) throws TException;
    }

    private static final class LimitedOutput extends TEndpointTransport {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private LimitedOutput(TConfiguration configuration) throws TTransportException {
            super(configuration);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void open() {
        }

        @Override
        public void close() {
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws TTransportException {
            throw new TTransportException("Write-only Thrift transport");
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws TTransportException {
            this.countConsumedMessageBytes(length);
            this.bytes.write(bytes, offset, length);
        }
    }
}
