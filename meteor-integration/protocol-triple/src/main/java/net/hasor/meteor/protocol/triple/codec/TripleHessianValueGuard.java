/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.triple.codec;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 在反序列化前验证一个 Hessian 2 对象文档，限制声明长度、引用与嵌套深度。 */
final class TripleHessianValueGuard {
    private static final int           MAX_DEPTH = 64;
    private final        byte[]        body;
    private final        List<Integer> classes   = new ArrayList<>();
    private              int           offset;
    private              int           references;
    private              int           types;

    private TripleHessianValueGuard(byte[] body) {
        this.body = body;
    }

    static void validate(byte[] body) throws IOException {
        TripleHessianValueGuard guard = new TripleHessianValueGuard(body);
        guard.value(0);
        if (guard.offset != body.length) {
            throw guard.invalid("Triple wrapper must contain exactly one Hessian value");
        }
    }

    private void value(int depth) throws IOException {
        this.depth(depth);
        int code = this.take();

        // 原生库递归读取连续类型定义，类型定义同样占用深度额度。
        int definitions = 0;
        while (code == 'C') {
            this.depth(depth + ++definitions);
            this.typeName();
            int fields = this.integer();
            this.count(fields);
            for (int index = 0; index < fields; index++) {
                this.string();
            }
            this.classes.add(fields);
            code = this.take();
        }
        depth += definitions;

        if (code <= 0x1f || code >= 0x30 && code <= 0x33 || code == 'S' || code == 'R') {
            this.string(code, null);
        } else if (code >= 0x20 && code <= 0x2f || code >= 0x34 && code <= 0x37 || code == 'A' || code == 'B') {
            this.binary(code);
        } else if (code >= 0x80 && code <= 0xbf || code >= 0xd8 && code <= 0xef) {
            // 紧凑整数与长整数无需额外字节。
        } else if (code >= 0xc0 && code <= 0xcf || code >= 0xf0) {
            this.skip(1);
        } else if (code >= 0xd0 && code <= 0xd7 || code >= 0x38 && code <= 0x3f) {
            this.skip(2);
        } else if (code >= 0x60 && code <= 0x6f) {
            this.object(code - 0x60, depth + 1);
        } else if (code >= 0x70 && code <= 0x77) {
            this.type();
            this.list(code - 0x70, depth + 1);
        } else if (code >= 0x78 && code <= 0x7f) {
            this.list(code - 0x78, depth + 1);
        } else {
            this.compound(code, depth);
        }
    }

    private void compound(int code, int depth) throws IOException {
        switch (code) {
            case 'N', 'T', 'F', 0x5b, 0x5c -> {
            }
            case 'I', 0x59, 0x5f, 0x4b -> this.skip(4);
            case 'L', 'D', 0x4a -> this.skip(8);
            case 0x5d -> this.skip(1);
            case 0x5e -> this.skip(2);
            case 'Q' -> this.reference(this.integer(), this.references);
            case 'O' -> this.object(this.integer(), depth + 1);
            case 'U' -> {
                this.type();
                this.list(-1, depth + 1);
            }
            case 'V' -> {
                this.type();
                int length = this.integer();
                this.count(length);
                this.list(length, depth + 1);
            }
            case 'W' -> this.list(-1, depth + 1);
            case 'X' -> {
                int length = this.integer();
                this.count(length);
                this.list(length, depth + 1);
            }
            case 'M' -> {
                this.type();
                this.map(depth + 1);
            }
            case 'H' -> this.map(depth + 1);
            default -> throw this.invalid("Invalid Hessian 2 value tag");
        }
    }

    private void object(int index, int depth) throws IOException {
        this.reference(index, this.classes.size());
        int fields = this.classes.get(index);
        this.count(fields);
        this.references++;
        for (int field = 0; field < fields; field++) {
            this.value(depth);
        }
    }

    private void list(int length, int depth) throws IOException {
        this.references++;
        if (length == -1) {
            while (this.peek() != 'Z') {
                this.value(depth);
            }
            this.expect('Z');
        } else {
            this.count(length);
            for (int index = 0; index < length; index++) {
                this.value(depth);
            }
        }
    }

    private void map(int depth) throws IOException {
        this.references++;
        while (this.peek() != 'Z') {
            this.value(depth);
            this.value(depth);
        }
        this.expect('Z');
    }

    private void type() throws IOException {
        int code = this.peek();
        if (code <= 0x1f || code >= 0x30 && code <= 0x33 || code == 'R' || code == 'S') {
            this.typeName();
            this.types++;
        } else {
            this.reference(this.integer(), this.types);
        }
    }

    private int integer() throws IOException {
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

    private void string() throws IOException {
        this.string(this.take(), null);
    }

    private void typeName() throws IOException {
        StringBuilder name = new StringBuilder();
        this.string(this.take(), name);
        int dimensions = 0;
        while (dimensions < name.length() && name.charAt(dimensions) == '[') {
            dimensions++;
        }
        if (dimensions > MAX_DEPTH) {
            throw this.invalid("Hessian array type exceeds 64 dimensions");
        }
    }

    private void string(int code, StringBuilder name) throws IOException {
        while (code == 'R') {
            this.utf(this.unsignedShort(), name);
            code = this.take();
        }
        if (code <= 0x1f) {
            this.utf(code, name);
        } else if (code >= 0x30 && code <= 0x33) {
            this.utf((code - 0x30) * 256 + this.take(), name);
        } else if (code == 'S') {
            this.utf(this.unsignedShort(), name);
        } else {
            throw this.invalid("Expected Hessian string");
        }
    }

    private void binary(int code) throws IOException {
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

    private void utf(int characters, StringBuilder name) throws IOException {
        this.count(characters);
        for (int index = 0; index < characters; index++) {
            int first = this.take();
            int value;
            if (first < 0x80) {
                value = first;
            } else if (first >= 0xc2 && first <= 0xdf) {
                value = (first & 0x1f) << 6 | this.continuation() & 0x3f;
            } else if (first >= 0xe0 && first <= 0xef) {
                // Hessian 按 UTF-16 单元计数，代理字符对分别编码为两个三字节单元。
                int second = this.continuation();
                if (first == 0xe0 && second < 0xa0) {
                    throw this.invalid("Overlong Hessian UTF-8 sequence");
                }
                value = (first & 0x0f) << 12 | (second & 0x3f) << 6 | this.continuation() & 0x3f;
            } else {
                throw this.invalid("Invalid Hessian UTF-8 sequence");
            }
            if (name != null) {
                name.append((char) value);
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
