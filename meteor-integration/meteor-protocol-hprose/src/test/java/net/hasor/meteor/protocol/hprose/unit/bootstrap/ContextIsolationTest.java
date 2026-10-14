/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.unit.bootstrap;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ServiceConfigurationError;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.protocol.rsf.codec.CodecAdapterForV1;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.hprose.scenario.bootstrap.BootstrapTestSupport;
import net.hasor.meteor.protocol.hprose.scenario.example.SerializerExamples.*;
import net.hasor.meteor.serialize.SerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextIsolationTest extends BootstrapTestSupport {
    @Test
    public void missingSpiCoderIsReportedAsASerializationError() throws Exception {
        // SPI 声明一个不存在的编码器，构建时应报告序列化组件发现失败。
        String coderClass = "missing.Coder";

        MetConfiguration settings = new MetConfiguration();
        try (URLClassLoader loader = this.spiLoader(coderClass)) {
            // 沿实际构建入口触发加载，检查包装后的错误码与原始 SPI 原因。
            try (MetContext ignored = settings.buildContext(loader)) {
                fail("Invalid coder accepted: " + coderClass);
            } catch (MetException expected) {
                assertEquals(ProtocolStatus.SerializeError, expected.getStatus());
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
                Throwable cause = expected.getCause();
                assertTrue(cause.getCause() instanceof ServiceConfigurationError);
                cause = cause.getCause();
                assertTrue(cause.getMessage().contains(coderClass));
            }
        }
    }

    @Test
    public void wrongSpiTypeIsReportedAsASerializationError() throws Exception {
        // 已存在但未实现 SerializeCoder 的类也不能成为 SPI 提供者。
        String coderClass = ContextIsolationTest.class.getName();

        MetConfiguration settings = new MetConfiguration();
        try (URLClassLoader loader = this.spiLoader(coderClass)) {
            // 构建失败保留提供者类名，便于定位配置了错误类型的 SPI 声明。
            try (MetContext ignored = settings.buildContext(loader)) {
                fail("Invalid coder accepted: " + coderClass);
            } catch (MetException expected) {
                assertEquals(ProtocolStatus.SerializeError, expected.getStatus());
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
                Throwable cause = expected.getCause();
                assertTrue(cause.getCause() instanceof ServiceConfigurationError);
                cause = cause.getCause();
                assertTrue(cause.getMessage().contains(coderClass));
            }
        }
    }

    @Test
    public void failingSpiInitializationIsReportedAsASerializationError() throws Exception {
        // 编码器可以被发现，但初始化自身失败时仍应阻止上下文构建。
        String coderClass = FailingCoder.class.getName();

        MetConfiguration settings = new MetConfiguration();
        try (URLClassLoader loader = this.spiLoader(coderClass)) {
            // 初始化异常应转换为序列化错误，并保留具体编码器信息。
            try (MetContext ignored = settings.buildContext(loader)) {
                fail("Invalid coder accepted: " + coderClass);
            } catch (MetException expected) {
                assertEquals(ProtocolStatus.SerializeError, expected.getStatus());
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
                Throwable cause = expected.getCause();
                assertTrue(cause.getMessage().contains(coderClass));
            }
        }
    }

    @Test
    public void missingSpiConstructorIsReportedAsASerializationError() throws Exception {
        // SPI 提供者缺少默认构造方法，服务加载器无法实例化它。
        String coderClass = NoDefaultConstructorCoder.class.getName();

        MetConfiguration settings = new MetConfiguration();
        try (URLClassLoader loader = this.spiLoader(coderClass)) {
            // 构建层不能丢失 ServiceConfigurationError 及失败类名。
            try (MetContext ignored = settings.buildContext(loader)) {
                fail("Invalid coder accepted: " + coderClass);
            } catch (MetException expected) {
                assertEquals(ProtocolStatus.SerializeError, expected.getStatus());
                assertTrue(expected.getCause() instanceof IllegalArgumentException);
                Throwable cause = expected.getCause();
                assertTrue(cause.getCause() instanceof ServiceConfigurationError);
                cause = cause.getCause();
                assertTrue(cause.getMessage().contains(coderClass));
            }
        }
    }

    @Test
    public void firstRuntimeCoderEncodesProtocolArguments() throws Exception {
        // 第一套 SPI 加载器提供带 first 标记的编码器。
        String tag = "first-";

        MetConfiguration settings = new MetConfiguration();
        Class<? extends SerializeCoder> coderClass = TaggedCoder.class;
        try (URLClassLoader loader = this.spiLoader(coderClass.getName()); //
             MetContext runtime = settings.buildContext(loader)) {
            // 编码器属于当前加载器，名称查询仍按大小写不敏感处理。
            TaggedCoder coder = (TaggedCoder) runtime.getSerializeCoder("Tagged");
            assertSame(loader, coder.getInitializedLoader());
            assertSame(runtime.getSerializeCoder("Tagged"), runtime.getSerializeCoder("tagged"));
            assertNull(runtime.getSerializeCoder("unknown"));

            // 通过 RSF 完整报文编解码，确认参数使用当前上下文发现的编码器。
            RequestPayload request = new RequestPayload();
            request.setRequestID(1);
            request.setSerializeType("tagged");
            request.addParameter("java.lang.String", "value");
            CodecAdapterForV1 codec = new CodecAdapterForV1(runtime);
            byte[] frame = codec.encode(request);
            String wireText = new String(frame, StandardCharsets.ISO_8859_1);
            assertTrue(wireText.contains(tag + "value"));

            // 同一上下文解码后恢复参数，报文中的标记由对应编码器去除。
            RequestPayload decoded = (RequestPayload) codec.decode(frame);
            assertEquals("value", decoded.getParameterValues().get(0));
        }
    }

    @Test
    public void secondRuntimeCoderEncodesProtocolArguments() throws Exception {
        // 第二套 SPI 加载器提供带 second 标记的编码器。
        String tag = "second-";

        MetConfiguration settings = new MetConfiguration();
        Class<? extends SerializeCoder> coderClass = SecondTaggedCoder.class;
        try (URLClassLoader loader = this.spiLoader(coderClass.getName()); //
             MetContext runtime = settings.buildContext(loader)) {
            // 编码器属于当前加载器，名称查询仍按大小写不敏感处理。
            TaggedCoder coder = (TaggedCoder) runtime.getSerializeCoder("Tagged");
            assertSame(loader, coder.getInitializedLoader());
            assertSame(runtime.getSerializeCoder("Tagged"), runtime.getSerializeCoder("tagged"));
            assertNull(runtime.getSerializeCoder("unknown"));

            // 通过 RSF 完整报文编解码，确认参数使用当前上下文发现的编码器。
            RequestPayload request = new RequestPayload();
            request.setRequestID(1);
            request.setSerializeType("tagged");
            request.addParameter("java.lang.String", "value");
            CodecAdapterForV1 codec = new CodecAdapterForV1(runtime);
            byte[] frame = codec.encode(request);
            String wireText = new String(frame, StandardCharsets.ISO_8859_1);
            assertTrue(wireText.contains(tag + "value"));

            // 同一上下文解码后恢复参数，报文中的标记由对应编码器去除。
            RequestPayload decoded = (RequestPayload) codec.decode(frame);
            assertEquals("value", decoded.getParameterValues().get(0));
        }
    }

    @Test
    public void runtimesOwnIdentityAndCodersWhenSharingSettings() throws Exception {
        // 同一份配置经不同加载器构建，运行态对象应各自独立。
        MetConfiguration settings = new MetConfiguration();
        settings.setDataHome(Paths.get("runtime-data"));
        try (URLClassLoader firstLoader = this.spiLoader(TaggedCoder.class.getName()); //
             URLClassLoader secondLoader = this.spiLoader(TaggedCoder.class.getName()); //
             MetContext first = settings.buildContext(firstLoader); //
             MetContext second = settings.buildContext(secondLoader)) {
            assertNotSame(first.getSettings(), second.getSettings());
            TaggedCoder firstTagged = (TaggedCoder) first.getSerializeCoder("Tagged");
            TaggedCoder secondTagged = (TaggedCoder) second.getSerializeCoder("Tagged");
            assertNotSame(firstTagged, secondTagged);
            assertSame(firstLoader, firstTagged.getInitializedLoader());
            assertSame(secondLoader, secondTagged.getInitializedLoader());

            // 实例标识及类加载器分别归属各上下文，共享的目录配置保持一致。
            String firstID = first.getInstanceID();
            String secondID = second.getInstanceID();
            assertFalse(firstID.isEmpty());
            assertFalse(secondID.isEmpty());
            assertNotEquals(firstID, secondID);
            assertSame(firstLoader, first.getClassLoader());
            assertSame(secondLoader, second.getClassLoader());
            assertEquals(Paths.get("runtime-data"), first.getSettings().getDataHome());
            assertEquals(Paths.get("runtime-data"), second.getSettings().getDataHome());

            // 内置编码器也应按上下文缓存，而不能在不同实例间共享。
            SerializeCoder firstCoder = first.getSerializeCoder("Java");
            SerializeCoder secondCoder = second.getSerializeCoder("Java");
            assertNotSame(firstCoder, secondCoder);
            assertSame(firstCoder, first.getSerializeCoder("Java"));
            assertNull(first.getSerializeCoder(null));

            // 关闭一个上下文不影响另一个上下文的编解码器及实例标识。
            first.close();
            assertSame(secondCoder, second.getSerializeCoder("Java"));
            byte[] javaEncoded = secondCoder.encode("active");
            assertEquals("active", secondCoder.decode(javaEncoded, String.class));
            byte[] taggedEncoded = secondTagged.encode("active");
            assertEquals("active", secondTagged.decode(taggedEncoded, String.class));
            assertEquals(firstID, first.getInstanceID());
            assertEquals(secondID, second.getInstanceID());
        }
    }

    @Test
    public void typeResolutionDoesNotReuseAnotherClassLoadersClass() throws Exception {
        // 读取同一类的原始字节，供隔离加载器重新定义类型。
        String name = Payload.class.getName();
        String resource = "/" + name.replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream input = this.getClass().getResourceAsStream(resource)) {
            bytes = input.readAllBytes();
        }

        // 同名类由另一个加载器重新定义，类型缓存不能跨加载器复用。
        ClassLoader isolated = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!StringUtils.equals(name, requested)) {
                    return super.loadClass(requested, resolve);
                }

                // 目标类只由当前加载器定义一次，随后按请求决定是否解析。
                synchronized (this.getClassLoadingLock(requested)) {
                    Class<?> type = this.findLoadedClass(requested);
                    if (type == null) {
                        type = this.defineClass(requested, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        this.resolveClass(type);
                    }
                    return type;
                }
            }
        };

        // 先缓存默认加载器的类型，再检查隔离加载器获得的是另一个 Class。
        assertSame(Payload.class, MetRuntimeUtils.getType(name, this.getClass().getClassLoader()));
        Class<?> other = MetRuntimeUtils.getType(name, isolated);
        assertNotSame(Payload.class, other);
        assertSame(isolated, other.getClassLoader());
    }
}
