/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import hprose.io.HproseWriter;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetPublisher;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.domain.MetException;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseRequestTest {
    public interface Echo {
        String echo(String value);

        void pair(List<String> first, List<String> second);

        String under_score(String value);
    }

    @Test
    public void argumentListPreservesSharedReferencesAndGenericTypes() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).register();

            // 同一个列表作为两个参数，报文中的第二个参数是对第一个参数的引用。
            List<String> shared = new ArrayList<>(List.of("中文", "shared"));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            HproseWriter writer = new HproseWriter(body);
            writer.writeString("echo_pair");
            writer.reset();
            writer.writeArray(new Object[] { shared, shared });
            body.write('z');

            ByteArrayInputStream input = new ByteArrayInputStream(body.toByteArray());
            RequestPayload call = HproseUtils.doCall(context, input, "/rpc", null);
            List<Object> arguments = call.getParameterValues();
            assertEquals(shared, arguments.get(0));
            assertSame(arguments.get(0), arguments.get(1));
        }
    }

    @Test
    public void methodNameAndParametersUseIndependentReferenceTables() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<Echo> service = context.publisher().rsfService(Echo.class).register();
            RequestPayload request = new RequestPayload();
            request.setServiceGroup(service.getBindGroup());
            request.setServiceName(service.getBindName());
            request.setServiceVersion(service.getBindVersion());
            request.setTargetMethod("echo");
            request.addParameter(String.class.getName(), "echo_echo");

            // 方法名与参数内容相同也不能让参数引用已经重置的方法名表。
            ByteArrayInputStream input = new ByteArrayInputStream(HproseUtils.encodeRequest(context, request));
            assertEquals('C', input.read());
            RequestPayload decoded = HproseUtils.doCall(context, input, "/rpc", "origin");
            assertEquals("echo_echo", decoded.getParameterValues().get(0));
            assertEquals("/rpc", decoded.getOption("Location"));
            assertEquals("origin", decoded.getOption("Origin"));
        }
    }

    @Test
    public void malformedNamesBatchCallsAndTrailingBytesAreRejected() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class).register();
            // 分别覆盖无方法名、批量调用、引用回传和结束符后的多余数据。
            List<String> invalid = new ArrayList<>();
            invalid.add("s4\"echo\"z");
            invalid.add("s9\"echo_echo\"a1{n}C");
            invalid.add("s9\"echo_echo\"a1{n}t");
            invalid.add("s9\"echo_echo\"a1{n}zx");
            for (String frame : invalid) {
                ByteArrayInputStream input = new ByteArrayInputStream(frame.getBytes(StandardCharsets.UTF_8));
                MetException failure = assertThrows(MetException.class, () -> HproseUtils.doCall(context, input, "/rpc", null));
                assertEquals(ProtocolStatus.ProtocolError, failure.getStatus());
            }

            // 缺少终止符属于解码失败，不能将半包交给业务执行。
            byte[] truncated = "s9\"echo_echo\"a1{n}".getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream input = new ByteArrayInputStream(truncated);
            assertThrows(IOException.class, () -> HproseUtils.doCall(context, input, "/rpc", null));
        }
    }

    @Test
    public void methodNamesAndServiceAliasesMayContainUnderscores() throws Exception {
        MetConfiguration configuration = new MetConfiguration();
        try (MetContext context = configuration.buildContext()) {
            MetPublisher.LinkedBuilder<Echo> declaration = context.publisher().rsfService(Echo.class);
            declaration.aliasName("Hprose", "test_echo");
            MetBindInfo<Echo> service = declaration.register();
            RequestPayload request = new RequestPayload();
            request.setServiceGroup(service.getBindGroup());
            request.setServiceName(service.getBindName());
            request.setServiceVersion(service.getBindVersion());
            request.setTargetMethod("under_score");
            request.addParameter(String.class.getName(), "value");

            // 服务别名与方法名同时包含下划线，仍按已注册别名确定方法边界。
            byte[] body = HproseUtils.encodeRequest(context, request);
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            assertEquals('C', input.read());
            RequestPayload decoded = HproseUtils.doCall(context, input, "/rpc", null);
            assertEquals("under_score", decoded.getTargetMethod());
            assertEquals("value", decoded.getParameterValues().get(0));
        }
    }
}
