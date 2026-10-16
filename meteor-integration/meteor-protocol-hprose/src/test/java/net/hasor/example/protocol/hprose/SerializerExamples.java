/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.hprose;
import java.nio.charset.StandardCharsets;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;

public final class SerializerExamples {
    private SerializerExamples() {
    }

    // 用可见前缀区分同名编码器的替换与初始化结果。
    public static class TaggedCoder implements SerializeCoder {
        private ClassLoader initializedLoader;

        public ClassLoader getInitializedLoader() {
            return this.initializedLoader;
        }

        public String name() {
            return "Tagged";
        }

        protected String tag() {
            return "first-";
        }

        public void initCoder(ClassLoader loader) {
            this.initializedLoader = loader;
        }

        public byte[] encode(Object value) {
            String taggedValue = this.tag() + value;
            return taggedValue.getBytes(StandardCharsets.UTF_8);
        }

        public Object decode(byte[] value, Class<?> type) {
            String taggedValue = new String(value, StandardCharsets.UTF_8);
            return taggedValue.substring(this.tag().length());
        }
    }

    // 不同实现分别模拟替换、初始化失败和缺少无参构造方法。
    public static class SecondTaggedCoder extends TaggedCoder {
        @Override
        protected String tag() {
            return "second-";
        }
    }

    public static class FailingCoder extends TaggedCoder {
        @Override
        public void initCoder(ClassLoader loader) {
            throw new IllegalStateException("init failed");
        }
    }

    public static class NoDefaultConstructorCoder extends TaggedCoder {
        public NoDefaultConstructorCoder(String tag) {
        }
    }

    public static class Payload {
    }

    // 保留正式 Java 序列化行为，只记录 SPI 实际使用的类加载器和初始化次数。
    public static class SpiCoder extends JavaSerializeCoder {
        private ClassLoader loader;
        private int         initializations;

        public ClassLoader getLoader() {
            return this.loader;
        }

        public int getInitializations() {
            return this.initializations;
        }

        @Override
        public String name() {
            return "SPI";
        }

        @Override
        public void initCoder(ClassLoader loader) {
            super.initCoder(loader);
            this.loader = loader;
            this.initializations++;
        }
    }
}
