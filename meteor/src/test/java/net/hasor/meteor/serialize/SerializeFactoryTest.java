/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.serialize;
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
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SerializeFactoryTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void javaBuiltinWorksWithoutContainer() throws Exception {
        // 通过真实 SPI 工厂取得 Java，独立完成一次编码与解码。
        SerializeFactory factory = SerializeFactory.createFactory();
        SerializeCoder coder = factory.getSerializeCoder("Java");
        assertNotNull(coder);
        assertEquals("Java", coder.name());
        assertSame(coder, factory.getSerializeCoder("java"));
        assertSame(coder, factory.getSerializeCoder("JAVA"));
        byte[] encoded = coder.encode("hello");
        assertEquals("hello", coder.decode(encoded, String.class));
    }

    @Test
    public void jsonBuiltinWorksWithoutContainer() throws Exception {
        // 通过真实 SPI 工厂取得 Json，独立完成一次编码与解码。
        SerializeFactory factory = SerializeFactory.createFactory();
        SerializeCoder coder = factory.getSerializeCoder("Json");
        assertNotNull(coder);
        assertEquals("Json", coder.name());
        assertSame(coder, factory.getSerializeCoder("json"));
        assertSame(coder, factory.getSerializeCoder("JSON"));
        byte[] encoded = coder.encode("hello");
        assertEquals("hello", coder.decode(encoded, String.class));
    }

    @Test
    public void absentCodersReturnNull() {
        // 不存在的名称不会隐式安装协议插件或回退到其他编码器。
        SerializeFactory factory = SerializeFactory.createFactory();
        assertNull(factory.getSerializeCoder("Hessian"));
        assertNull(factory.getSerializeCoder("Hprose"));
        assertNull(factory.getSerializeCoder("unknown"));
        assertNull(factory.getSerializeCoder(null));
    }

    @Test
    public void mixedCaseLookupReturnsSameCoder() {
        // 同一个编码器名称允许混合大小写。
        SerializeFactory factory = SerializeFactory.createFactory();
        assertSame(factory.getSerializeCoder("Json"), factory.getSerializeCoder("jSoN"));
    }

    @Test
    public void emptyRegistryDoesNotRegisterBuiltins() {
        // 显式创建空工厂，不执行 SPI 注册。
        assertNull(new SerializeFactory(this.getClass().getClassLoader()).getSerializeCoder("Java"));
    }

    @Test
    public void customRegistrationInitializesBeforeUse() {
        // 自定义编码器完成业务加载器初始化后才可查询。
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
        };
        SerializeFactory factory = new SerializeFactory(loader);
        CustomCoder coder = new CustomCoder();
        factory.registerSerializeCoder("CuStOm", coder);
        assertSame(loader, coder.loader);
        assertSame(coder, factory.getSerializeCoder("custom"));
    }

    @Test
    public void customRegistrationReplacesSameName() {
        // 相同名称不区分大小写，成功的新注册替换旧实例。
        ClassLoader loader = this.getClass().getClassLoader();
        SerializeFactory factory = new SerializeFactory(loader);
        factory.registerSerializeCoder("CuStOm", new CustomCoder());
        CustomCoder replacement = new CustomCoder();
        factory.registerSerializeCoder("custom", replacement);
        assertSame(loader, replacement.loader);
        assertSame(replacement, factory.getSerializeCoder("CUSTOM"));
        assertSame(replacement, factory.getSerializeCoder("CuStOm"));
    }

    @Test
    public void failedInitializationKeepsExistingCoder() {
        // 先注册可用编码器，再尝试用初始化失败的实现替换。
        SerializeFactory factory = new SerializeFactory(this.getClass().getClassLoader());
        CustomCoder original = new CustomCoder();
        factory.registerSerializeCoder("custom", original);

        // 异常传播后仍能按不同大小写查询原实现。
        assertThrows(IllegalStateException.class, () -> factory.registerSerializeCoder("CUSTOM", new FailingCoder()));
        assertSame(original, factory.getSerializeCoder("custom"));
        assertSame(original, factory.getSerializeCoder("CUSTOM"));
    }

    @Test
    public void spiRegistersProviderNamesAndKeepsInstancesLocalToEachFactory() throws Exception {
        // 同一 SPI 描述符为不同工厂创建不同实现，不跨工厂共享状态。
        try (URLClassLoader loader = this.spiLoader(CustomCoder.class.getName())) {
            SerializeFactory first = SerializeFactory.createFactory(loader);
            SerializeFactory second = SerializeFactory.createFactory(loader);
            CustomCoder firstCoder = (CustomCoder) first.getSerializeCoder("custom-spi");
            CustomCoder secondCoder = (CustomCoder) second.getSerializeCoder("custom-spi");
            // 两个实例都恰好初始化一次，仍能完成真实编码。
            assertNotSame(firstCoder, secondCoder);
            assertSame(loader, firstCoder.loader);
            assertSame(loader, secondCoder.loader);
            assertEquals(1, firstCoder.initializations);
            assertEquals(1, secondCoder.initializations);
            byte[] encoded = firstCoder.encode("hello");
            assertEquals("hello", firstCoder.decode(encoded, String.class));
            assertNotNull(first.getSerializeCoder("Java"));
            assertSame(firstCoder, first.getSerializeCoder("CUSTOM-SPI"));
        }
    }

    @Test
    public void spiUsesThreadContextClassLoader() throws Exception {
        // 临时改变当前线程加载器，finally 恢复，避免影响其他测试。
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
        // 类加载器不提供 SPI 描述符时，不隐式安装内置编码器。
        ClassLoader loader = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                if (StringUtils.equals(name, "META-INF/services/" + SerializeCoder.class.getName())) {
                    return Collections.enumeration(Collections.emptyList());
                }
                return super.getResources(name);
            }
        };
        assertNull(SerializeFactory.createFactory(loader).getSerializeCoder("Java"));
    }

    @Test
    public void identicalSpiNamesReportBothProviders() throws Exception {
        // 两个 SPI 实现竞争同一名称，失败信息标识双方实现。
        String original = CustomCoder.class.getName();
        String duplicate = DuplicateCoder.class.getName();
        try (URLClassLoader loader = this.spiLoader(original, duplicate)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            String message = failure.getMessage();
            assertTrue(message.toLowerCase(Locale.ROOT).contains("custom-spi"));
            assertTrue(message.contains(original));
            assertTrue(message.contains(duplicate));
        }
    }

    @Test
    public void caseVariantSpiNamesReportBothProviders() throws Exception {
        // 两个 SPI 实现竞争同一名称，失败信息标识双方实现。
        String original = CustomCoder.class.getName();
        String duplicate = CaseVariantCoder.class.getName();
        try (URLClassLoader loader = this.spiLoader(original, duplicate)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            String message = failure.getMessage();
            assertTrue(message.toLowerCase(Locale.ROOT).contains("custom-spi"));
            assertTrue(message.contains(original));
            assertTrue(message.contains(duplicate));
        }
    }

    @Test
    public void nameMatchingIsIndependentOfDefaultLocale() {
        // 土耳其语的大小写规则不能改变编码器名称匹配。
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            SerializeFactory factory = SerializeFactory.createFactory();
            Locale.setDefault(new Locale("tr", "TR"));
            CustomCoder coder = new CustomCoder();
            factory.registerSerializeCoder("MIXED", coder);
            Locale.setDefault(Locale.ENGLISH);
            assertSame(coder, factory.getSerializeCoder("mixed"));
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void blankSpiNameIsRejectedBeforeInitialization() throws Exception {
        // 非法 SPI 名称在实现初始化之前拒绝，保留具体提供者和原因。
        String provider = BlankNameCoder.class.getName();
        try (URLClassLoader loader = this.spiLoader(provider)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            assertTrue(failure.getMessage().contains(provider));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void nullSpiNameIsRejectedBeforeInitialization() throws Exception {
        // 非法 SPI 名称在实现初始化之前拒绝，保留具体提供者和原因。
        String provider = NullNameCoder.class.getName();
        try (URLClassLoader loader = this.spiLoader(provider)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            assertTrue(failure.getMessage().contains(provider));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
    }

    @Test
    public void spiInitializationFailureReportsNameAndCause() throws Exception {
        // SPI 初始化失败同时保留提供者名称和业务原因。
        String provider = FailingCoder.class.getName();
        try (URLClassLoader loader = this.spiLoader(provider)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            assertTrue(failure.getMessage().contains("failing-spi"));
            assertTrue(failure.getMessage().contains(provider));
            assertTrue(failure.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    public void missingSpiClassRetainsServiceLoaderFailure() throws Exception {
        // 实现类不存在时，保留 ServiceLoader 原因和对应类名。
        String provider = "missing.Coder";
        try (URLClassLoader loader = this.spiLoader(provider)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            Throwable cause = failure.getCause();
            assertTrue(cause instanceof ServiceConfigurationError);
            assertTrue(cause.getMessage().contains(provider));
        }
    }

    @Test
    public void nonCoderSpiClassRetainsServiceLoaderFailure() throws Exception {
        // 实现类未实现编码器接口时，保留 ServiceLoader 原因和对应类名。
        String provider = SerializeFactoryTest.class.getName();
        try (URLClassLoader loader = this.spiLoader(provider)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            Throwable cause = failure.getCause();
            assertTrue(cause instanceof ServiceConfigurationError);
            assertTrue(cause.getMessage().contains(provider));
        }
    }

    @Test
    public void missingSpiConstructorRetainsServiceLoaderFailure() throws Exception {
        // 实现类没有无参构造方法时，保留 ServiceLoader 原因和对应类名。
        String provider = NoDefaultConstructor.class.getName();
        try (URLClassLoader loader = this.spiLoader(provider)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            Throwable cause = failure.getCause();
            assertTrue(cause instanceof ServiceConfigurationError);
            assertTrue(cause.getMessage().contains(provider));
        }
    }

    @Test
    public void failingSpiConstructorRetainsServiceLoaderFailure() throws Exception {
        // 实现类构造过程失败时，保留 ServiceLoader 原因和对应类名。
        String provider = BrokenConstructor.class.getName();
        try (URLClassLoader loader = this.spiLoader(provider)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
                SerializeFactory.createFactory(loader);
            });
            Throwable cause = failure.getCause();
            assertTrue(cause instanceof ServiceConfigurationError);
            assertTrue(cause.getMessage().contains(provider));
        }
    }

    @Test
    public void nullRegistrationNameIsRejectedBeforeInitialization() {
        // 非法名称不能触发编码器初始化或注册。
        SerializeFactory factory = new SerializeFactory(this.getClass().getClassLoader());
        CustomCoder coder = new CustomCoder();
        assertThrows(IllegalArgumentException.class, () -> factory.registerSerializeCoder(null, coder));
        assertNull(coder.loader);
    }

    @Test
    public void emptyRegistrationNameIsRejectedBeforeInitialization() {
        // 非法名称不能触发编码器初始化或注册。
        SerializeFactory factory = new SerializeFactory(this.getClass().getClassLoader());
        CustomCoder coder = new CustomCoder();
        assertThrows(IllegalArgumentException.class, () -> factory.registerSerializeCoder("", coder));
        assertNull(coder.loader);
    }

    @Test
    public void whitespaceRegistrationNameIsRejectedBeforeInitialization() {
        // 非法名称不能触发编码器初始化或注册。
        SerializeFactory factory = new SerializeFactory(this.getClass().getClassLoader());
        CustomCoder coder = new CustomCoder();
        assertThrows(IllegalArgumentException.class, () -> factory.registerSerializeCoder("  ", coder));
        assertNull(coder.loader);
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullCoder() {
        // 空工厂同样拒绝没有实现的注册项。
        new SerializeFactory(this.getClass().getClassLoader()).registerSerializeCoder("missing", null);
    }

    @Test(expected = NullPointerException.class)
    public void rejectsNullClassLoader() {
        // 工厂必须绑定一个业务加载器。
        new SerializeFactory(null);
    }

    @Test
    public void worksWithoutThreadContextLoader() throws IOException {
        // 临时改变当前线程加载器，finally 恢复，避免影响其他测试。
        Thread thread = Thread.currentThread();
        ClassLoader old = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(null);
            SerializeCoder coder = SerializeFactory.createFactory().getSerializeCoder("Java");
            byte[] encoded = coder.encode("fallback");
            assertEquals("fallback", coder.decode(encoded, String.class));
        } finally {
            thread.setContextClassLoader(old);
        }
    }

    private URLClassLoader spiLoader(String... providers) throws IOException {
        // 只准备真实 SPI 描述符，发现和初始化仍由待测工厂执行。
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
