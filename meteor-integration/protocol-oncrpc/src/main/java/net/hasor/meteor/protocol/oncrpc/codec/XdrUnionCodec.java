/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.codec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** IDL 的判别式 union，只写出所选分支，保留 void 与 default arm。 */
public final class XdrUnionCodec implements XdrCodec<Object> {
    public final Map<Object, XdrPropertyBinding> cases   = new LinkedHashMap<>();
    public final List<XdrPropertyBinding>        members = new ArrayList<>();
    public final XdrStructCodec                  value;
    public       XdrPropertyBinding              discriminator;
    public       XdrPropertyBinding              fallback;
    public       boolean                         defaultDeclared;

    public XdrUnionCodec(Class<?> type) {
        this.value = new XdrStructCodec(type);
    }

    @Override
    public Object read(XdrInput input) {
        Object value = this.value.read(input);
        Object tag = input.read(this.discriminator.codec);
        try {
            // 构造方法里的默认值不能使未选择分支看起来也已赋值。
            for (XdrPropertyBinding member : this.members) {
                member.set(value, null);
            }

            this.discriminator.set(value, tag);
            XdrPropertyBinding selected = this.select(tag);
            if (selected != null) {
                selected.set(value, input.read(selected.codec));
            }
            return value;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot decode XDR union", failure);
        }
    }

    @Override
    public void write(XdrOutput output, Object value) {
        if (value == null) {
            throw new IllegalArgumentException("XDR union cannot be null");
        }

        try {
            Object tag = this.discriminator.get(value);
            XdrPropertyBinding selected = this.select(tag);
            for (XdrPropertyBinding member : this.members) {
                if (member != selected && member.get(value) != null) {
                    throw new IllegalArgumentException("Unselected XDR union member must be null: " + member.name);
                }
            }

            output.write(this.discriminator.codec, tag);
            if (selected != null) {
                output.write(selected.codec, selected.get(value));
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot encode XDR union", failure);
        }
    }

    private XdrPropertyBinding select(Object tag) {
        if (this.cases.containsKey(tag)) {
            return this.cases.get(tag);
        }

        if (this.defaultDeclared) {
            return this.fallback;
        }

        throw new IllegalArgumentException("Unknown XDR union discriminator: " + tag);
    }
}
