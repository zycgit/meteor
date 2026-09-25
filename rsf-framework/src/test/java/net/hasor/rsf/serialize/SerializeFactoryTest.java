/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.serialize;

import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class SerializeFactoryTest {
    @Test
    public void builtinsWorkWithoutContainer() throws Exception {
        SerializeFactory factory = SerializeFactory.createFactory();
        for (String name : new String[] {"Java", "Json", "Hessian", "Hprose"}) {
            SerializeCoder coder = factory.getSerializeCoder(name);
            assertNotNull(name, coder);
            assertEquals("hello", coder.decode(coder.encode("hello"), String.class));
        }
        assertNull(factory.getSerializeCoder("unknown"));
        assertNull(factory.getSerializeCoder(null));
        assertNull(factory.getSerializeCoder("java"));
    }

    @Test
    public void emptyRegistryDoesNotRegisterBuiltins() {
        assertNull(new SerializeFactory().getSerializeCoder("Java"));
    }

    @Test
    public void customRegistrationInitializesBeforeUse() {
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {};
        SerializeFactory factory = new SerializeFactory(loader);
        CustomCoder coder = new CustomCoder();
        factory.registerSerializeCoder("custom", coder);
        assertSame(loader, coder.loader);
        assertSame(coder, factory.getSerializeCoder("custom"));
        CustomCoder replacement = new CustomCoder();
        factory.registerSerializeCoder("custom", replacement);
        assertSame(loader, replacement.loader);
        assertSame(replacement, factory.getSerializeCoder("custom"));
    }

    @Test
    public void failedInitializationKeepsExistingCoder() {
        SerializeFactory factory = new SerializeFactory();
        CustomCoder original = new CustomCoder();
        factory.registerSerializeCoder("custom", original);
        try {
            factory.registerSerializeCoder("custom", new FailingCoder());
            fail("Expected initialization failure");
        } catch (IllegalStateException expected) {
            assertSame(original, factory.getSerializeCoder("custom"));
        }
    }

    @Test
    public void readsExplicitMappingsWithGivenLoader() {
        final boolean[] loaded = {false};
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals(CustomCoder.class.getName())) {
                    loaded[0] = true;
                }
                return super.loadClass(name);
            }
        };
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("configured", "  " + CustomCoder.class.getName() + "  ");
        SerializeFactory factory = SerializeFactory.createFactory(mappings, loader);
        assertTrue(loaded[0]);
        assertSame(loader, ((CustomCoder) factory.getSerializeCoder("configured")).loader);
        assertNull(factory.getSerializeCoder("Java"));
    }

    @Test
    public void invalidConfiguredCodersReportNameAndCause() {
        for (String className : new String[] {"missing.Coder", String.class.getName(), NoDefaultConstructor.class.getName(),
                FailingCoder.class.getName(), BrokenConstructor.class.getName(), null}) {
            try {
                SerializeFactory.createFactory(Collections.singletonMap("broken", className), getClass().getClassLoader());
                fail("Expected failure for " + className);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("broken"));
                assertNotNull(expected.getCause());
            }
        }
    }

    @Test
    public void rejectsBlankNamesBeforeInitialization() {
        SerializeFactory factory = new SerializeFactory();
        for (String name : new String[] {null, "", "  "}) {
            CustomCoder coder = new CustomCoder();
            try {
                factory.registerSerializeCoder(name, coder);
                fail("Blank name accepted");
            } catch (IllegalArgumentException expected) {
                assertNull(coder.loader);
            }
        }
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullCoder() {
        new SerializeFactory().registerSerializeCoder("missing", null);
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullClassLoader() {
        new SerializeFactory(null);
    }

    @Test
    public void worksWithoutThreadContextLoader() throws IOException {
        Thread thread = Thread.currentThread();
        ClassLoader old = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(null);
            SerializeCoder coder = SerializeFactory.createFactory().getSerializeCoder("Java");
            assertEquals("fallback", coder.decode(coder.encode("fallback"), String.class));
        } finally {
            thread.setContextClassLoader(old);
        }
    }

    public static class CustomCoder extends JavaSerializeCoder {
        ClassLoader loader;
        @Override
        public void initCoder(ClassLoader classLoader) {
            super.initCoder(classLoader);
            this.loader = classLoader;
        }
    }

    public static class FailingCoder extends JavaSerializeCoder {
        @Override
        public void initCoder(ClassLoader classLoader) {
            throw new IllegalStateException("test initialization failure");
        }
    }

    public static class NoDefaultConstructor extends JavaSerializeCoder {
        public NoDefaultConstructor(String ignored) {}
    }

    public static class BrokenConstructor extends JavaSerializeCoder {
        public BrokenConstructor() { throw new IllegalStateException("test constructor failure"); }
    }
}
