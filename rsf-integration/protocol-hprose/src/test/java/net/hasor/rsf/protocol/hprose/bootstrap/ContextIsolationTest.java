/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose.bootstrap;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ServiceConfigurationError;
import net.hasor.rsf.RsfBindInfo;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.bootstrap.Configuration;
import net.hasor.rsf.connector.protocol.rsf.codec.CodecAdapterForV1;
import net.hasor.rsf.connector.protocol.rsf.codec.RequestBlock;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.RsfException;
import net.hasor.rsf.domain.RsfRuntimeUtils;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.serialize.SerializeCoder;
import net.hasor.rsf.serialize.coder.JavaSerializeCoder;
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
        for (String coderClass : new String[] { "missing.Coder", ContextIsolationTest.class.getName(), FailingCoder.class.getName(), NoDefaultConstructorCoder.class.getName() }) {
            Configuration settings = new Configuration();
            try (URLClassLoader loader = this.spiLoader(coderClass)) {
                try (RsfContext ignored = settings.buildContext(loader)) {
                    fail("Invalid coder accepted: " + coderClass);
                } catch (RsfException expected) {
                    assertEquals(ProtocolStatus.SerializeError, expected.getStatus());
                    assertTrue(expected.getCause() instanceof IllegalArgumentException);
                    Throwable cause = expected.getCause();
                    if (coderClass.equals("missing.Coder")) {
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
            Configuration settings = new Configuration().setSerializeType("Tagged");
            Class<? extends SerializeCoder> coderClass = tag.equals("first-") ? TaggedCoder.class : SecondTaggedCoder.class;
            try (URLClassLoader loader = this.spiLoader(coderClass.getName()); RsfContext runtime = settings.buildContext(loader)) {
                assertSame(loader, ((TaggedCoder) runtime.getSerializeCoder("Tagged")).initializedLoader);
                assertSame(runtime.getSerializeCoder("Tagged"), runtime.getSerializeCoder("tagged"));
                assertNull(runtime.getSerializeCoder("unknown"));
                RequestPayload request = new RequestPayload();
                request.setRequestID(1);
                request.setSerializeType("tagged");
                request.addParameter("java.lang.String", "value");
                RequestBlock block = new CodecAdapterForV1(runtime).buildRequestBlock(request);
                try {
                    assertEquals(tag + "value", new String(block.readPool((short) (block.getParameters()[0] & 0xFFFF)), StandardCharsets.UTF_8));
                } finally {
                    block.release();
                }
            }
        }
    }

    @Test
    public void runtimesOwnIdentityAndCodersWhenSharingSettings() throws Exception {
        Configuration settings = new Configuration();
        settings.setDataHome(Paths.get("runtime-data"));
        try (URLClassLoader firstLoader = this.spiLoader(TaggedCoder.class.getName()); URLClassLoader secondLoader = this.spiLoader(TaggedCoder.class.getName()); RsfContext first = settings.buildContext(firstLoader); RsfContext second = settings.buildContext(secondLoader)) {
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
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int n;
            while ((n = input.read(buffer)) != -1) {
                output.write(buffer, 0, n);
            }
            bytes = output.toByteArray();
        }
        ClassLoader isolated = new ClassLoader(this.getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(requested)) {
                    return super.loadClass(requested, resolve);
                }
                synchronized (getClassLoadingLock(requested)) {
                    Class<?> type = findLoadedClass(requested);
                    if (type == null) {
                        type = defineClass(requested, bytes, 0, bytes.length);
                    }
                    if (resolve) {
                        resolveClass(type);
                    }
                    return type;
                }
            }
        };
        assertSame(Payload.class, RsfRuntimeUtils.getType(name, this.getClass().getClassLoader()));
        Class<?> other = RsfRuntimeUtils.getType(name, isolated);
        assertNotSame(Payload.class, other);
        assertSame(isolated, other.getClassLoader());
    }

    @Test
    public void spiCodersAreDiscoveredPerContextAndUsedForRemoteCalls() throws Exception {
        Configuration serverSettings = this.configuration().setSerializeType("spi");
        Configuration clientSettings = this.configuration().setSerializeType("sPi");
        try (URLClassLoader serverLoader = this.spiLoader(SpiCoder.class.getName()); URLClassLoader clientLoader = this.spiLoader(SpiCoder.class.getName()); RsfContext server = serverSettings.buildContext(serverLoader); RsfContext client = clientSettings.buildContext(clientLoader)) {
            SpiCoder serverCoder = (SpiCoder) server.getSerializeCoder("SPI");
            SpiCoder clientCoder = (SpiCoder) client.getSerializeCoder("SPI");
            assertSame(serverCoder, server.getSerializeCoder("spi"));
            assertSame(clientCoder, client.getSerializeCoder("sPi"));
            assertNotSame(serverCoder, clientCoder);
            assertSame(serverLoader, serverCoder.loader);
            assertSame(clientLoader, clientCoder.loader);
            assertEquals(1, serverCoder.initializations);
            assertEquals(1, clientCoder.initializations);
            server.publisher().rsfService(Echo.class, new EchoService()).register();
            RsfBindInfo<Echo> service = client.publisher().rsfService(Echo.class).register();
            assertEquals("sPi", service.getSerializeType());
            server.start();
            client.start();
            Echo remote = client.getRsfClient(server.bindAddress("rsf")).getRemote(service);
            assertEquals("SPI 中文", remote.echo("SPI 中文"));
            assertEquals(5, remote.add(2, 3));
        }
    }

}
