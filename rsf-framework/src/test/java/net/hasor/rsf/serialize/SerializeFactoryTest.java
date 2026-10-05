/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.serialize;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.ServiceConfigurationError;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SerializeFactoryTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void builtinsWorkWithoutContainer() throws Exception {
        SerializeFactory factory = SerializeFactory.createFactory();
        for (String name : new String[] { "Java", "Json", "Hessian", "Hprose" }) {
            SerializeCoder coder = factory.getSerializeCoder(name);
            assertNotNull(name, coder);
            assertEquals(name, coder.name());
            assertSame(coder, factory.getSerializeCoder(name.toLowerCase(Locale.ROOT)));
            assertSame(coder, factory.getSerializeCoder(name.toUpperCase(Locale.ROOT)));
            assertEquals("hello", coder.decode(coder.encode("hello"), String.class));
        }
        assertNull(factory.getSerializeCoder("unknown"));
        assertNull(factory.getSerializeCoder(null));
        assertSame(factory.getSerializeCoder("Json"), factory.getSerializeCoder("jSoN"));
    }

    @Test
    public void emptyRegistryDoesNotRegisterBuiltins() {
        assertNull(new SerializeFactory().getSerializeCoder("Java"));
    }

    @Test
    public void customRegistrationInitializesBeforeUse() {
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
        };
        SerializeFactory factory = new SerializeFactory(loader);
        CustomCoder coder = new CustomCoder();
        factory.registerSerializeCoder("CuStOm", coder);
        assertSame(loader, coder.loader);
        assertSame(coder, factory.getSerializeCoder("custom"));
        CustomCoder replacement = new CustomCoder();
        factory.registerSerializeCoder("custom", replacement);
        assertSame(loader, replacement.loader);
        assertSame(replacement, factory.getSerializeCoder("CUSTOM"));
        assertSame(replacement, factory.getSerializeCoder("CuStOm"));
    }

    @Test
    public void failedInitializationKeepsExistingCoder() {
        SerializeFactory factory = new SerializeFactory();
        CustomCoder original = new CustomCoder();
        factory.registerSerializeCoder("custom", original);
        try {
            factory.registerSerializeCoder("CUSTOM", new FailingCoder());
            fail("Expected initialization failure");
        } catch (IllegalStateException expected) {
            assertSame(original, factory.getSerializeCoder("custom"));
            assertSame(original, factory.getSerializeCoder("CUSTOM"));
        }
    }

    @Test
    public void spiRegistersProviderNamesAndKeepsInstancesLocalToEachFactory() throws Exception {
        try (URLClassLoader loader = this.spiLoader(CustomCoder.class.getName())) {
            SerializeFactory first = SerializeFactory.createFactory(loader);
            SerializeFactory second = SerializeFactory.createFactory(loader);
            CustomCoder firstCoder = (CustomCoder) first.getSerializeCoder("custom-spi");
            CustomCoder secondCoder = (CustomCoder) second.getSerializeCoder("custom-spi");
            assertNotSame(firstCoder, secondCoder);
            assertSame(loader, firstCoder.loader);
            assertSame(loader, secondCoder.loader);
            assertEquals(1, firstCoder.initializations);
            assertEquals(1, secondCoder.initializations);
            assertEquals("hello", firstCoder.decode(firstCoder.encode("hello"), String.class));
            assertNotNull(first.getSerializeCoder("Java"));
            assertSame(firstCoder, first.getSerializeCoder("CUSTOM-SPI"));
        }
    }

    @Test
    public void spiUsesThreadContextClassLoader() throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader loader = this.spiLoader(CustomCoder.class.getName())) {
            thread.setContextClassLoader(loader);
            CustomCoder coder = (CustomCoder) SerializeFactory.createFactory().getSerializeCoder("custom-spi");
            assertSame(loader, coder.loader);
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    public void noServiceDescriptorsMeansNoImplicitRegistrations() {
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (name.equals("META-INF/services/" + SerializeCoder.class.getName())) {
                    return Collections.enumeration(Collections.<URL>emptyList());
                }
                return super.getResources(name);
            }
        };
        assertNull(SerializeFactory.createFactory(loader).getSerializeCoder("Java"));
    }

    @Test
    public void conflictingSpiNamesReportBothProviders() throws Exception {
        for (Class<?> duplicate : new Class<?>[] { DuplicateCoder.class, CaseVariantCoder.class }) {
            try (URLClassLoader loader = this.spiLoader(CustomCoder.class.getName(), duplicate.getName())) {
                try {
                    SerializeFactory.createFactory(loader);
                    fail("Conflicting SPI names accepted");
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().toLowerCase(Locale.ROOT).contains("custom-spi"));
                    assertTrue(expected.getMessage().contains(CustomCoder.class.getName()));
                    assertTrue(expected.getMessage().contains(duplicate.getName()));
                }
            }
        }
    }

    @Test
    public void nameMatchingIsIndependentOfDefaultLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            SerializeFactory factory = SerializeFactory.createFactory();
            Locale.setDefault(new Locale("tr", "TR"));
            assertSame(factory.getSerializeCoder("Hessian"), factory.getSerializeCoder("HESSIAN"));
            CustomCoder coder = new CustomCoder();
            factory.registerSerializeCoder("MIXED", coder);
            Locale.setDefault(Locale.ENGLISH);
            assertSame(coder, factory.getSerializeCoder("mixed"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void invalidSpiNamesAreRejectedBeforeInitialization() throws Exception {
        for (Class<?> provider : new Class<?>[] { BlankNameCoder.class, NullNameCoder.class }) {
            try (URLClassLoader loader = this.spiLoader(provider.getName())) {
                try {
                    SerializeFactory.createFactory(loader);
                    fail("Invalid SPI name accepted: " + provider.getName());
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getMessage().contains(provider.getName()));
                    assertTrue(expected.getCause() instanceof IllegalArgumentException);
                }
            }
        }
    }

    @Test
    public void spiInitializationFailureReportsNameAndCause() throws Exception {
        try (URLClassLoader loader = this.spiLoader(FailingCoder.class.getName())) {
            try {
                SerializeFactory.createFactory(loader);
                fail("Expected SPI initialization failure");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("failing-spi"));
                assertTrue(expected.getMessage().contains(FailingCoder.class.getName()));
                assertTrue(expected.getCause() instanceof IllegalStateException);
            }
        }
    }

    @Test
    public void invalidSpiProvidersRetainServiceLoaderFailure() throws Exception {
        for (String className : new String[] { "missing.Coder", SerializeFactoryTest.class.getName(), NoDefaultConstructor.class.getName(), BrokenConstructor.class.getName() }) {
            try (URLClassLoader loader = this.spiLoader(className)) {
                try {
                    SerializeFactory.createFactory(loader);
                    fail("Expected invalid SPI provider: " + className);
                } catch (IllegalArgumentException expected) {
                    assertTrue(expected.getCause() instanceof ServiceConfigurationError);
                    assertTrue(expected.getCause().getMessage().contains(className));
                }
            }
        }
    }

    @Test
    public void rejectsBlankNamesBeforeInitialization() {
        SerializeFactory factory = new SerializeFactory();
        for (String name : new String[] { null, "", "  " }) {
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

    private URLClassLoader spiLoader(String... providers) throws IOException {
        Path root = this.temporary.newFolder().toPath();
        Path resource = root.resolve("META-INF/services/" + SerializeCoder.class.getName());
        Files.createDirectories(resource.getParent());
        Files.write(resource, String.join("\n", providers).getBytes(StandardCharsets.UTF_8));
        return new URLClassLoader(new URL[] { root.toUri().toURL() }, this.getClass().getClassLoader());
    }

    public static class CustomCoder extends JavaSerializeCoder {
        ClassLoader loader;
        int         initializations;

        @Override
        public String name() {
            return "custom-spi";
        }

        @Override
        public void initCoder(ClassLoader classLoader) {
            super.initCoder(classLoader);
            this.loader = classLoader;
            this.initializations++;
        }
    }

    public static class DuplicateCoder extends CustomCoder {
    }

    public static class CaseVariantCoder extends CustomCoder {
        @Override
        public String name() {
            return "CUSTOM-SPI";
        }
    }

    public static class BlankNameCoder extends CustomCoder {
        @Override
        public String name() {
            return "  ";
        }

        @Override
        public void initCoder(ClassLoader classLoader) {
            throw new AssertionError("Invalid name must be rejected before initialization");
        }
    }

    public static class NullNameCoder extends BlankNameCoder {
        @Override
        public String name() {
            return null;
        }
    }

    public static class FailingCoder extends JavaSerializeCoder {
        @Override
        public String name() {
            return "failing-spi";
        }

        @Override
        public void initCoder(ClassLoader classLoader) {
            throw new IllegalStateException("test initialization failure");
        }
    }

    public static class NoDefaultConstructor extends JavaSerializeCoder {
        public NoDefaultConstructor(String ignored) {
        }
    }

    public static class BrokenConstructor extends JavaSerializeCoder {
        public BrokenConstructor() {
            throw new IllegalStateException("test constructor failure");
        }
    }
}
