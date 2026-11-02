/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hessian.codec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Validates one RPC envelope before Hessian allocates collections or instantiates wire types. */
final class HessianWireGuard {
    private static final int           MAX_DEPTH = 64;
    private final        byte[]        body;
    private final        List<Integer> classes   = new ArrayList<>();
    private              int           offset;
    private              int           references;
    private              int           types;

    private HessianWireGuard(byte[] body) {
        this.body = body;
    }

    static void validate(byte[] body, int maxMessageSize) throws IOException {
        if (body == null || body.length > maxMessageSize) {
            throw new IOException("Hessian message exceeds maxMessageSize");
        }
        HessianWireGuard guard = new HessianWireGuard(body);
        guard.message();
        if (guard.offset != body.length) {
            throw new IOException("Hessian HTTP body must contain exactly one RPC envelope");
        }
    }

    private void message() throws IOException {
        int code = this.take();
        int major = this.take();
        int minor = this.take();
        if (code == 'H' && major == 2 && minor == 0) {
            this.message2();
            return;
        }

        // c 02 00 still uses Hessian 1 values; only its requested reply version changes.
        boolean version1 = major == 1 && minor == 0;
        boolean callVersion = version1 || major == 2 && minor == 0 || major == 0 && minor == 1;
        if (code == 'c' && callVersion) {
            this.headers1();
            this.expect('m');
            this.utf(this.unsignedShort());
            this.sequence1(0);
        } else if (code == 'r' && version1) {
            this.headers1();
            if (this.peek() == 'f') {
                this.take();
                this.map1(0);
            } else {
                this.value1(0);
            }
            this.expect('z');
        } else {
            throw this.invalid("Unsupported Hessian RPC header");
        }
    }

    private void message2() throws IOException {
        int code = this.take();
        if (code == 'C') {
            this.string2();
            int arguments = this.integer2();
            this.count(arguments);
            for (int index = 0; index < arguments; index++) {
                this.value2(0);
            }
        } else if (code == 'R') {
            this.value2(0);
        } else if (code == 'F') {
            int map = this.peek();
            if (map != 'H' && map != 'M') {
                throw this.invalid("Hessian fault must contain a map");
            }
            this.value2(0);
        } else {
            throw this.invalid("Expected Hessian call, reply or fault");
        }
    }

    private void headers1() throws IOException {
        while (this.peek() == 'H') {
            this.take();
            this.utf(this.unsignedShort());
            this.value1(0);
        }
    }

    private void value1(int depth) throws IOException {
        this.depth(depth);
        int code = this.take();
        switch (code) {
            case 'N', 'T', 'F' -> {
            }
            case 'I' -> this.skip(4);
            case 'L', 'D', 'd' -> this.skip(8);
            case 's', 'S', 'x', 'X' -> this.chunks1(code, true);
            case 'b', 'B' -> this.chunks1(code, false);
            case 'R' -> this.reference(this.integerBytes(), this.references);
            case 'M' -> {
                this.type1();
                this.references++;
                this.map1(depth + 1);
            }
            case 'V' -> {
                this.type1();
                this.references++;
                if (this.peek() == 'l') {
                    this.take();
                    int length = this.integerBytes();
                    this.count(length);
                    for (int index = 0; index < length; index++) {
                        this.value1(depth + 1);
                    }
                    this.expect('z');
                } else {
                    this.sequence1(depth + 1);
                }
            }
            case 'r' -> throw this.invalid("Hessian remote object references are not supported");
            default -> throw this.invalid("Invalid Hessian 1 value tag");
        }
    }

    private void type1() throws IOException {
        if (this.peek() == 't') {
            this.take();
            this.utf(this.unsignedShort());
        }
    }

    private void chunks1(int code, boolean text) throws IOException {
        while (true) {
            int length = this.unsignedShort();
            if (text) {
                this.utf(length);
            } else {
                this.skip(length);
            }
            boolean last = code == 'S' || code == 'X' || code == 'B';
            if (last) {
                return;
            }
            code = this.take();
            boolean valid;
            if (text) {
                valid = code == 's' || code == 'S' || code == 'x' || code == 'X';
            } else {
                valid = code == 'b' || code == 'B';
            }
            if (!valid) {
                throw this.invalid("Invalid Hessian 1 chunk continuation");
            }
        }
    }

    private void sequence1(int depth) throws IOException {
        while (this.peek() != 'z') {
            this.value1(depth);
        }
        this.expect('z');
    }

    private void map1(int depth) throws IOException {
        while (this.peek() != 'z') {
            this.value1(depth);
            this.value1(depth);
        }
        this.expect('z');
    }

    private void value2(int depth) throws IOException {
        this.depth(depth);
        int code = this.take();

        // Caucho reads consecutive class definitions recursively before the next value.
        int definitions = 0;
        while (code == 'C') {
            this.depth(depth + ++definitions);
            this.string2();
            int fields = this.integer2();
            this.count(fields);
            for (int index = 0; index < fields; index++) {
                this.string2();
            }
            this.classes.add(fields);
            code = this.take();
        }
        depth += definitions;

        if (code <= 0x1f || code >= 0x30 && code <= 0x33 || code == 'S' || code == 'R') {
            this.string2(code);
        } else if (code >= 0x20 && code <= 0x2f || code >= 0x34 && code <= 0x37 || code == 'A' || code == 'B') {
            this.binary2(code);
        } else if (code >= 0x80 && code <= 0xbf || code >= 0xd8 && code <= 0xef) {
            // Compact integer or long with no additional bytes.
        } else if (code >= 0xc0 && code <= 0xcf || code >= 0xf0) {
            this.skip(1);
        } else if (code >= 0xd0 && code <= 0xd7 || code >= 0x38 && code <= 0x3f) {
            this.skip(2);
        } else if (code >= 0x60 && code <= 0x6f) {
            this.object2(code - 0x60, depth + 1);
        } else if (code >= 0x70 && code <= 0x77) {
            this.type2();
            this.list2(code - 0x70, depth + 1);
        } else if (code >= 0x78 && code <= 0x7f) {
            this.list2(code - 0x78, depth + 1);
        } else {
            this.compound2(code, depth);
        }
    }

    private void compound2(int code, int depth) throws IOException {
        switch (code) {
            case 'N', 'T', 'F', 0x5b, 0x5c -> {
            }
            case 'I', 0x59, 0x5f, 0x4b -> this.skip(4);
            case 'L', 'D', 0x4a -> this.skip(8);
            case 0x5d -> this.skip(1);
            case 0x5e -> this.skip(2);
            case 'Q' -> this.reference(this.integer2(), this.references);
            case 'O' -> this.object2(this.integer2(), depth + 1);
            case 'U' -> {
                this.type2();
                this.list2(-1, depth + 1);
            }
            case 'V' -> {
                this.type2();
                int length = this.integer2();
                this.count(length);
                this.list2(length, depth + 1);
            }
            case 'W' -> this.list2(-1, depth + 1);
            case 'X' -> {
                int length = this.integer2();
                this.count(length);
                this.list2(length, depth + 1);
            }
            case 'M' -> {
                this.type2();
                this.map2(depth + 1);
            }
            case 'H' -> this.map2(depth + 1);
            default -> throw this.invalid("Invalid Hessian 2 value tag");
        }
    }

    private void object2(int index, int depth) throws IOException {
        this.reference(index, this.classes.size());
        int fields = this.classes.get(index);
        this.count(fields);
        this.references++;
        for (int field = 0; field < fields; field++) {
            this.value2(depth);
        }
    }

    private void list2(int length, int depth) throws IOException {
        this.references++;
        if (length == -1) {
            while (this.peek() != 'Z') {
                this.value2(depth);
            }
            this.expect('Z');
        } else {
            this.count(length);
            for (int index = 0; index < length; index++) {
                this.value2(depth);
            }
        }
    }

    private void map2(int depth) throws IOException {
        this.references++;
        while (this.peek() != 'Z') {
            this.value2(depth);
            this.value2(depth);
        }
        this.expect('Z');
    }

    private void type2() throws IOException {
        int code = this.peek();
        if (code <= 0x1f || code >= 0x30 && code <= 0x33 || code == 'R' || code == 'S') {
            this.string2();
            this.types++;
        } else {
            this.reference(this.integer2(), this.types);
        }
    }

    private int integer2() throws IOException {
        int code = this.take();
        if (code >= 0x80 && code <= 0xbf) {
            return code - 0x90;
        }
        if (code >= 0xc0 && code <= 0xcf) {
            return ((code - 0xc8) << 8) + this.take();
        }
        if (code >= 0xd0 && code <= 0xd7) {
            return ((code - 0xd4) << 16) + this.unsignedShort();
        }
        if (code == 'I') {
            return this.integerBytes();
        }
        throw this.invalid("Expected Hessian integer");
    }

    private void string2() throws IOException {
        this.string2(this.take());
    }

    private void string2(int code) throws IOException {
        while (code == 'R') {
            this.utf(this.unsignedShort());
            code = this.take();
        }
        if (code <= 0x1f) {
            this.utf(code);
        } else if (code >= 0x30 && code <= 0x33) {
            this.utf((code - 0x30) * 256 + this.take());
        } else if (code == 'S') {
            this.utf(this.unsignedShort());
        } else {
            throw this.invalid("Expected Hessian string");
        }
    }

    private void binary2(int code) throws IOException {
        while (code == 'A') {
            this.skip(this.unsignedShort());
            code = this.take();
        }
        if (code >= 0x20 && code <= 0x2f) {
            this.skip(code - 0x20);
        } else if (code >= 0x34 && code <= 0x37) {
            this.skip((code - 0x34) * 256 + this.take());
        } else if (code == 'B') {
            this.skip(this.unsignedShort());
        } else {
            throw this.invalid("Expected Hessian binary chunk");
        }
    }

    private void utf(int characters) throws IOException {
        this.count(characters);
        for (int index = 0; index < characters; index++) {
            int first = this.take();
            if (first < 0x80) {
                continue;
            }
            // Hessian counts UTF-16 units and encodes a surrogate pair as two three-byte units.
            if (first >= 0xc2 && first <= 0xdf) {
                this.continuation();
            } else if (first >= 0xe0 && first <= 0xef) {
                int second = this.continuation();
                if (first == 0xe0 && second < 0xa0) {
                    throw this.invalid("Overlong Hessian UTF-8 sequence");
                }
                this.continuation();
            } else {
                throw this.invalid("Invalid Hessian UTF-8 sequence");
            }
        }
    }

    private int continuation() throws IOException {
        int value = this.take();
        if (value < 0x80 || value > 0xbf) {
            throw this.invalid("Invalid Hessian UTF-8 continuation");
        }
        return value;
    }

    private void reference(int index, int available) throws IOException {
        if (index < 0 || index >= available) {
            throw this.invalid("Invalid Hessian reference");
        }
    }

    private void depth(int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw this.invalid("Hessian value nesting exceeds 64 levels");
        }
    }

    private void count(int count) throws IOException {
        if (count < 0 || count > this.body.length - this.offset) {
            throw this.invalid("Hessian declared length exceeds remaining message bytes");
        }
    }

    private int integerBytes() throws IOException {
        return (this.take() << 24) | (this.take() << 16) | (this.take() << 8) | this.take();
    }

    private int unsignedShort() throws IOException {
        return (this.take() << 8) | this.take();
    }

    private void skip(int count) throws IOException {
        this.count(count);
        this.offset += count;
    }

    private void expect(int code) throws IOException {
        if (this.take() != code) {
            throw this.invalid("Unexpected Hessian token");
        }
    }

    private int take() throws IOException {
        int value = this.peek();
        this.offset++;
        return value;
    }

    private int peek() throws IOException {
        if (this.offset == this.body.length) {
            throw this.invalid("Truncated Hessian message");
        }
        return this.body[this.offset] & 0xff;
    }

    private IOException invalid(String message) {
        return new IOException(message + " at byte " + this.offset);
    }
}
