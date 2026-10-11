/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.bootstrap;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.protocol.rsf.codec.CodecAdapterForV1;
import net.hasor.meteor.connector.protocol.rsf.codec.RequestBlock;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.MetRuntimeUtils;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.serialize.SerializeCoder;
import net.hasor.meteor.serialize.coder.JavaSerializeCoder;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContextIsolationTest extends BootstrapTestSupport {
    public static class TaggedCoder implements SerializeCoder {
        private final String tag;
        ClassLoader initializedLoader;

        public TaggedCoder() {
            this("first-");
        }

        protected TaggedCoder(String tag) {
            this.tag = tag;
        }

        public String name() {
            return "Tagged";
        }

        public void initCoder(ClassLoader loader) {
            this.initializedLoader = loader;
        }

        public byte[] encode(Object value) {
            return (this.tag + value).getBytes(StandardCharsets.UTF_8);
        }

        public Object decode(byte[] value, Class<?> type) {
            return new String(value, StandardCharsets.UTF_8).substring(this.tag.length());
        }
    }

    public static class SecondTaggedCoder extends TaggedCoder {
        public SecondTaggedCoder() {
            super("second-");
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
            super(tag);
        }
    }

    public static class Payload {
    }

    public static class SpiCoder extends JavaSerializeCoder {
        private ClassLoader loader;
        private int         initializations;

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

    @Test
    public void invalidSpiCoderIsReportedAsAnRsfSerializationError() throws Exception {
        // 分别验证类缺失、类型不符、初始化失败及构造方法不符合 SPI 要求。
        List<String> invalidProviders = new ArrayList<>();
        invalidProviders.add("missing.Coder");
        invalidProviders.add(ContextIsolationTest.class.getName());
        invalidProviders.add(FailingCoder.class.getName());
        invalidProviders.add(NoDefaultConstructorCoder.class.getName());
        for (String coderClass : invalidProviders) {
            MetConfiguration settings = new MetConfiguration();
            try (URLClassLoader loader = this.spiLoader(coderClass)) {
                try (MetContext ignored = settings.buildContext(loader)) {
                    fail("Invalid coder accepted: " + coderClass);
                } catch (MetException expected) {
                    assertEquals(ProtocolStatus.SerializeError, expected.getStatus());
                    assertTrue(expected.getCause() instanceof IllegalArgumentException);
                    Throwable cause = expected.getCause();
                    if (StringUtils.equals(coderClass, "missing.Coder")) {
                        assertTrue(cause.getCause() instanceof ServiceConfigurationError);
                    }
                    if (cause.getCause() instanceof ServiceConfigurationError) {
                        cause = cause.getCause();
                    }
                    assertTrue(cause.getMessage().contains(coderClass));
                }
            }
        }
    }

    @Test
    public void protocolAdaptersUseTheirOwnRuntimeSpiCoders() throws Exception {
        for (String tag : new String[] { "first-", "second-" }) {
            MetConfiguration settings = new MetConfiguration();
            Class<? extends SerializeCoder> coderClass = TaggedCoder.class;
            if (StringUtils.equals(tag, "second-")) {
                coderClass = SecondTaggedCoder.class;
            }
            try (URLClassLoader loader = this.spiLoader(coderClass.getName()); //
                 MetContext runtime = settings.buildContext(loader)) {
                TaggedCoder coder = (TaggedCoder) runtime.getSerializeCoder("Tagged");
                assertSame(loader, coder.initializedLoader);
                assertSame(runtime.getSerializeCoder("Tagged"), runtime.getSerializeCoder("tagged"));
                assertNull(runtime.getSerializeCoder("unknown"));

                // 检查 RSF 实际报文池中的字节，确认使用当前上下文发现的编码器。
                RequestPayload request = new RequestPayload();
                request.setRequestID(1);
                request.setSerializeType("tagged");
                request.addParameter("java.lang.String", "value");
                RequestBlock block = new CodecAdapterForV1(runtime).buildRequestBlock(request);
                try {
                    short valueIndex = (short) (block.getParameters()[0] & 0xFFFF);
                    byte[] encoded = block.readPool(valueIndex);
                    assertEquals(tag + "value", new String(encoded, StandardCharsets.UTF_8));
                } finally {
                    block.release();
                }
            }
        }
    }

    @Test
    public void runtimesOwnIdentityAndCodersWhenSharingSettings() throws Exception {
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
            assertSame(firstLoader, firstTagged.initializedLoader);
            assertSame(secondLoader, secondTagged.initializedLoader);
            String firstID = first.getInstanceID();
            String secondID = second.getInstanceID();
            assertFalse(firstID.isEmpty());
            assertFalse(secondID.isEmpty());
            assertNotEquals(firstID, secondID);
            assertSame(firstLoader, first.getClassLoader());
            assertSame(secondLoader, second.getClassLoader());
            assertEquals(Paths.get("runtime-data"), first.getSettings().getDataHome());
            assertEquals(Paths.get("runtime-data"), second.getSettings().getDataHome());
            SerializeCoder firstCoder = first.getSerializeCoder("Java");
            SerializeCoder secondCoder = second.getSerializeCoder("Java");
            assertNotSame(firstCoder, secondCoder);
            assertSame(firstCoder, first.getSerializeCoder("Java"));
            assertNull(first.getSerializeCoder(null));

            // 关闭一个上下文不影响另一个上下文的编解码器及实例标识。
            first.close();
            assertSame(secondCoder, second.getSerializeCoder("Java"));
            assertEquals("active", secondCoder.decode(secondCoder.encode("active"), String.class));
            assertEquals("active", secondTagged.decode(secondTagged.encode("active"), String.class));
            assertEquals(firstID, first.getInstanceID());
            assertEquals(secondID, second.getInstanceID());
        }
    }

    @Test
    public void typeResolutionDoesNotReuseAnotherClassLoadersClass() throws Exception {
        String name = Payload.class.getName();
        byte[] bytes;
        try (InputStream input = this.getClass().getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            bytes = input.readAllBytes();
        }

        // 同名类由另一个加载器重新定义，类型缓存不能跨加载器复用。
        ClassLoader isolated = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!StringUtils.equals(name, requested)) {
                    return super.loadClass(requested, resolve);
                }
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
        assertSame(Payload.class, MetRuntimeUtils.getType(name, this.getClass().getClassLoader()));
        Class<?> other = MetRuntimeUtils.getType(name, isolated);
        assertNotSame(Payload.class, other);
        assertSame(isolated, other.getClassLoader());
    }

    @Test
    public void spiCodersAreDiscoveredPerContextAndUsedForRemoteCalls() throws Exception {
        MetConfiguration serverSettings = new MetConfiguration();
        MetConfiguration clientSettings = new MetConfiguration();
        serverSettings.connector("tcp").bind("127.0.0.1", 0).protocol("rsf").option("serializeType", "spi");
        clientSettings.connector("tcp").protocol("rsf").option("serializeType", "sPi");
        try (URLClassLoader serverLoader = this.spiLoader(SpiCoder.class.getName()); //
             URLClassLoader clientLoader = this.spiLoader(SpiCoder.class.getName()); //
             MetContext server = serverSettings.buildContext(serverLoader); //
             MetContext client = clientSettings.buildContext(clientLoader)) {
            SpiCoder serverCoder = (SpiCoder) server.getSerializeCoder("SPI");
            SpiCoder clientCoder = (SpiCoder) client.getSerializeCoder("SPI");
            assertSame(serverCoder, server.getSerializeCoder("spi"));
            assertSame(clientCoder, client.getSerializeCoder("sPi"));
            assertNotSame(serverCoder, clientCoder);
            assertSame(serverLoader, serverCoder.loader);
            assertSame(clientLoader, clientCoder.loader);
            assertEquals(1, serverCoder.initializations);
            assertEquals(1, clientCoder.initializations);

            // 用真实网络调用确认客户端和服务端使用各自的 SPI 编解码器。
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            MetBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            assertNull(service.getSerializeType());

            server.start();
            client.start();

            Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            assertEquals("SPI 中文", remote.echo("SPI 中文"));
            assertEquals(5, remote.add(2, 3));
        }
    }

}
