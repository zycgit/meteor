/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import hprose.io.HproseReader;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.annotation.MetIgnore;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ConnectorConfig;
import net.hasor.meteor.connector.RouteMountIndex;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import net.hasor.meteor.domain.*;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;
import net.hasor.meteor.protocol.hprose.HproseProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class HproseHttpCodecTest {
    @Test
    public void providerPreparesTheAliasedOperationDescriptionTogetherWithTheBinding() {
        // Hprose 的线上名称包含服务别名，服务描述应复用同一份已准备的操作表。
        ServiceDomain<PersonEcho> service = new ServiceDomain<>(PersonEcho.class);
        service.setBindName("people");
        service.putAliasName("Hprose", "People");
        ProtocolConfig protocol = ProtocolConfig.newConfig("hprose", "hprose", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, null, Map.of());
        ProtocolBinding binding = new HproseProtocol().prepareBinding(protocol, null, meta);

        // 返回时定义与描述均已就绪，不需要单独导出或设置描述。
        Map<?, ?> definition = (Map<?, ?>) binding.getDefinition();
        String operationName = binding.getDescription().path("methods").get(0).path("name").asText();
        assertTrue(definition.containsValue(operationName));
        assertTrue(operationName.startsWith("People_"));
        assertEquals("Hprose", binding.getDescription().path("serializeType").asText());
    }

    @Test
    public void inheritedParameterUsesThePublishedDtoType() throws Exception {
        // 父接口的 T 由具体服务绑定为 Person，线数据只携带普通 Map。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<?> service = context.publisher()//
                    .rsfService(PersonEcho.class, value -> value).name("Generic").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig config = endpoint.protocol("hprose");
            HproseHttpCodec codec = new HproseHttpCodec(config, this.routes(context, config), context);
            RequestPayload request = this.genericRequest(service, Map.of("name", "Ada"));

            // 不依赖报文中的 Java 类名，按公共描述恢复具体 DTO。
            HproseDecodedInvocation decoded = codec.decodeRequest(codec.encodeRequest(request));
            assertNull(decoded.calls().get(0).failure());
            Object value = decoded.calls().get(0).request().getParameterValues().get(0);
            assertEquals("Ada", ((Person) value).name);
        }
    }

    @Test
    public void inheritedContainerParameterRetainsItsDtoElements() throws Exception {
        // 父接口的 T 在此服务中绑定为 List<Person>。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(PeopleEcho.class, value -> value) //
                    .name("Generic").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig config = endpoint.protocol("hprose");
            HproseHttpCodec codec = new HproseHttpCodec(config, this.routes(context, config), context);
            RequestPayload request = this.genericRequest(service, List.of(Map.of("name", "Ada")));

            // 转为 Hprose Type 时保留容器参数，元素不能退化为普通 Map。
            HproseDecodedInvocation decoded = codec.decodeRequest(codec.encodeRequest(request));
            assertNull(decoded.calls().get(0).failure());
            List<?> values = (List<?>) decoded.calls().get(0).request().getParameterValues().get(0);
            assertEquals("Ada", ((Person) values.get(0)).name);
        }
    }

    @Test
    public void inheritedReturnUsesTheConsumerDtoType() throws Exception {
        // 消费者的返回值继承自泛型父接口，远端响应为普通 Map。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(PersonEcho.class).name("Generic").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig config = endpoint.protocol("hprose");
            HproseHttpCodec codec = new HproseHttpCodec(config, this.routes(context, config), context);
            RequestPayload request = this.genericRequest(service, null);
            ResponsePayload result = new ResponsePayload();
            result.setStatus(ProtocolStatus.OK);
            result.setReturnData(Map.of("name", "Ada"));
            HproseDecodedCall call = new HproseDecodedCall(request, false, null);

            // 请求仍保留 JVM 擦除签名，返回数据按消费者的实际泛型恢复。
            ResponsePayload decoded = codec.decodeResponse(request, this.response(codec, call, result));
            assertEquals("Ada", ((Person) decoded.getReturnData()).name);
        }
    }

    @Test
    public void inheritedContainerReturnRetainsItsDtoElements() throws Exception {
        // 消费者绑定 List<Person>，响应中不携带服务端 Java 类型。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(PeopleEcho.class).name("Generic").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig config = endpoint.protocol("hprose");
            HproseHttpCodec codec = new HproseHttpCodec(config, this.routes(context, config), context);
            RequestPayload request = this.genericRequest(service, null);
            ResponsePayload result = new ResponsePayload();
            result.setStatus(ProtocolStatus.OK);
            result.setReturnData(List.of(Map.of("name", "Ada")));
            HproseDecodedCall call = new HproseDecodedCall(request, false, null);

            // 返回容器的元素类型来自公共方法描述。
            ResponsePayload decoded = codec.decodeResponse(request, this.response(codec, call, result));
            List<?> values = (List<?>) decoded.getReturnData();
            assertEquals("Ada", ((Person) values.get(0)).name);
        }
    }

    @Test
    public void inheritedGenericArrayParameterRetainsContainerElements() throws Exception {
        // T 绑定为 List<Person>[]，数组元素的泛型也必须传递给 Hprose。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(PeopleArrayEcho.class, value -> value) //
                    .name("Generic").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig config = endpoint.protocol("hprose");
            HproseHttpCodec codec = new HproseHttpCodec(config, this.routes(context, config), context);
            Object[] values = { List.of(Map.of("name", "Ada")) };
            RequestPayload request = this.genericRequest(service, values);

            // 数组保持 List[]，每个 List 内的成员按公共描述转换为 Person。
            HproseDecodedInvocation decoded = codec.decodeRequest(codec.encodeRequest(request));
            assertNull(decoded.calls().get(0).failure());
            List<?>[] result = (List<?>[]) decoded.calls().get(0).request().getParameterValues().get(0);
            assertEquals("Ada", ((Person) result[0].get(0)).name);
        }
    }

    @Test
    public void inheritedGenericArrayReturnRetainsContainerElements() throws Exception {
        // 响应数组的元素仍为未携带 Java 类名的列表与 Map。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<?> service = context.publisher().rsfService(PeopleArrayEcho.class).name("Generic").register();
            ConnectorConfig endpoint = context.getSettings().getConnectorConfigs().iterator().next();
            ProtocolConfig config = endpoint.protocol("hprose");
            HproseHttpCodec codec = new HproseHttpCodec(config, this.routes(context, config), context);
            RequestPayload request = this.genericRequest(service, null);
            ResponsePayload result = new ResponsePayload();
            result.setStatus(ProtocolStatus.OK);
            result.setReturnData(new Object[] { List.of(Map.of("name", "Ada")) });
            HproseDecodedCall call = new HproseDecodedCall(request, false, null);

            // 完整返回类型保留数组、列表及 DTO 三层声明。
            ResponsePayload decoded = codec.decodeResponse(request, this.response(codec, call, result));
            List<?>[] values = (List<?>[]) decoded.getReturnData();
            assertEquals("Ada", ((Person) values[0].get(0)).name);
        }
    }

    private RequestPayload genericRequest(MetBindInfo<?> service, Object value) {
        RequestPayload request = new RequestPayload();
        request.setServiceGroup(service.getBindGroup());
        request.setServiceName(service.getBindName());
        request.setServiceVersion(service.getBindVersion());
        request.setTargetMethod("echo");
        request.addParameter(Object.class.getName(), value);
        return request;
    }

    public interface GenericEcho<T> {
        T echo(T value);
    }

    public interface PersonEcho extends GenericEcho<Person> {
    }

    public interface PeopleEcho extends GenericEcho<List<Person>> {
    }

    public interface PeopleArrayEcho extends GenericEcho<List<Person>[]> {
    }

    public static class Person {
        public String name;
    }

    public interface Parent {
        @MetIgnore
        default String inheritedHidden() {
            return "hidden";
        }
    }

    public interface Echo extends Parent {
        String echo(String value);

        @MetIgnore
        default String echo() {
            return "hidden overload";
        }

        @MetIgnore
        default String hidden() {
            return "hidden";
        }

        static String helper() {
            return "helper";
        }
    }

    private MetConfiguration configuration() {
        MetConfiguration configuration = new MetConfiguration();
        configuration.connector("http").protocol("hprose").contextPath("/rpc");
        return configuration;
    }

    private RouteMountIndex routes(MetContext context, ProtocolConfig protocol) {
        String endpointName = context.getSettings().getConnectorConfigs().iterator().next().name();
        return new RouteMountIndex(endpointName, protocol);
    }

    @Test
    public void decodeLeavesRequestIdForSession() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 编解码器只恢复调用内容，请求关联标识由会话接管。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            request.addParameter("java.lang.String", "中文🙂");
            HttpRequest wire = protocol.encodeRequest(request);
            HproseDecodedInvocation decoded = protocol.decodeRequest(wire);

            assertEquals(0, decoded.calls().get(0).request().getRequestID());
        }
    }

    @Test
    public void decodedCallPreservesDeclaredArgumentType() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 解码时同时恢复参数值和服务方法声明的 Java 类型。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            request.addParameter("java.lang.String", "中文🙂");
            HttpRequest wire = protocol.encodeRequest(request);
            RequestPayload decoded = protocol.decodeRequest(wire).calls().get(0).request();
            assertEquals("中文🙂", decoded.getParameterValues().get(0));
            assertEquals("java.lang.String", decoded.getParameterTypes().get(0));
        }
    }

    @Test
    public void responseUsesTheCallersRequestId() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 编码服务端响应，再以调用方请求 ID 解码同一条响应。
            HttpRequest wire = protocol.encodeRequest(this.request(1));
            HproseDecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            ResponsePayload response = new ResponsePayload();
            response.setRequestID(call.request().getRequestID());
            response.setStatus(ProtocolStatus.OK);
            response.setReturnData("结果🙂");
            HttpResponse responseWire = this.response(protocol, call, response);
            ResponsePayload decoded = protocol.decodeResponse(this.request(91), responseWire);
            assertEquals(91, decoded.getRequestID());
            assertEquals("结果🙂", decoded.getReturnData());
        }
    }

    @Test
    public void functionListOmitsServicesNotPublishedThroughHprose() throws Exception {
        // 两个实际端点同时存在，服务只向 RSF 暴露。
        MetConfiguration configuration = this.configuration();
        configuration.connector("tcp").protocol("rsf");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).protocol("rsf").register();
            ProtocolConfig mount = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/hprose"));
            HproseHttpCodec codec = new HproseHttpCodec(mount, this.routes(context, mount), context);

            // 请求函数列表，确认只发布到 RSF 的服务没有泄漏到 Hprose。
            HttpRequest request = HttpRequest.newBuffered("POST", "/hprose", Map.of(), new byte[] { 'z' }, 1, Map.of());
            byte[] body = codec.decodeRequest(request).immediate().body();
            ByteArrayInputStream functionData = new ByteArrayInputStream(body, 1, body.length - 1);
            HproseReader reader = new HproseReader(functionData);
            assertArrayEquals(new String[0], reader.unserialize(String[].class));
        }
    }

    @Test
    public void functionDiscoveryOmitsIgnoredAndStaticMethods() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 函数发现只列出允许发布的方法，忽略方法和静态方法都不可见。
            byte[] discoveryBody = { 'z' };
            Map<String, List<String>> headers = Collections.emptyMap();
            HttpRequest listRequest = HttpRequest.newBuffered("POST", "/rpc", headers, discoveryBody, 1, Map.of());
            HproseDecodedInvocation functions = protocol.decodeRequest(listRequest);
            assertTrue(functions.calls().isEmpty());
            assertEquals('F', functions.immediate().body()[0]);
            byte[] body = functions.immediate().body();
            byte[] functionData = Arrays.copyOfRange(body, 1, body.length);
            HproseReader reader = new HproseReader(new ByteArrayInputStream(functionData));
            List<String> names = Arrays.asList(reader.unserialize(String[].class));
            assertTrue(names.contains("echo_echo"));
            assertFalse(names.contains("echo_hidden"));
            assertFalse(names.contains("echo_inheritedHidden"));
            assertFalse(names.contains("echo_helper"));
        }
    }

    @Test
    public void similarPathPrefixDoesNotMatchTheMount() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            byte[] discoveryBody = { 'z' };
            // 相近前缀不能被当作当前挂载路径。
            Map<String, List<String>> headers = Collections.emptyMap();
            HttpRequest otherPath = HttpRequest.newBuffered("POST", "/rpc-other", headers, discoveryBody, 1, Map.of());
            HproseDecodedInvocation unmatched = protocol.decodeRequest(otherPath);
            assertEquals(404, unmatched.immediate().status());
        }
    }

    @Test
    public void ignoredMethodCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 构造被忽略方法的调用，确认按不存在的方法处理。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("hidden");
            String alias = context.getServiceInfo("wire", "Echo", "1.0.0").getAliasName("Hprose");
            byte[] body = HproseWireCodec.encodeRequest(alias + "_" + request.getTargetMethod(), request);
            HttpRequest wire = HttpRequest.newBuffered("POST", "/rpc", Map.of(), body, 1, Map.of());
            HproseDecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void inheritedIgnoredMethodCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 构造父接口中被忽略方法的调用，确认继承关系不会绕过限制。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("inheritedHidden");
            String alias = context.getServiceInfo("wire", "Echo", "1.0.0").getAliasName("Hprose");
            byte[] body = HproseWireCodec.encodeRequest(alias + "_" + request.getTargetMethod(), request);
            HttpRequest wire = HttpRequest.newBuffered("POST", "/rpc", Map.of(), body, 1, Map.of());
            HproseDecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void ignoredOverloadCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 调用被忽略的无参重载，确认不会误选已发布的带参方法。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("echo");
            String alias = context.getServiceInfo("wire", "Echo", "1.0.0").getAliasName("Hprose");
            byte[] body = HproseWireCodec.encodeRequest(alias + "_" + request.getTargetMethod(), request);
            HttpRequest wire = HttpRequest.newBuffered("POST", "/rpc", Map.of(), body, 1, Map.of());
            HproseDecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void staticMethodCannotBeInvoked() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 尝试调用接口静态方法，确认辅助方法不能作为 RPC 执行。
            RequestPayload request = new RequestPayload();
            request.setServiceGroup("wire");
            request.setServiceName("Echo");
            request.setServiceVersion("1.0.0");
            request.setTargetMethod("helper");
            String alias = context.getServiceInfo("wire", "Echo", "1.0.0").getAliasName("Hprose");
            byte[] body = HproseWireCodec.encodeRequest(alias + "_" + request.getTargetMethod(), request);
            HttpRequest wire = HttpRequest.newBuffered("POST", "/rpc", Map.of(), body, 1, Map.of());
            HproseDecodedCall call = protocol.decodeRequest(wire).calls().get(0);
            MetException failure = (MetException) call.failure();
            assertEquals(ProtocolStatus.NotFound, failure.getStatus());
        }
    }

    @Test
    public void unicodeBusinessErrorsRemainFailedResponses() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 编码含 Unicode 文本的业务错误，确认状态和消息完整恢复。
            ResponsePayload error = new ResponsePayload();
            error.setStatus(ProtocolStatus.NotFound);
            error.addOption("message", "不存在🙂");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            HproseWireCodec.writeResponse(7, error, bytes);
            HttpResponse wire = HttpResponse.newBuffered(200, Collections.emptyMap(), bytes.toByteArray(), Map.of());
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void unstructuredRemoteErrorPreservesItsText() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 非结构化错误仍通过协议错误帧返回，保留原始文本。
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse plainError = protocol.encodeErrorResponse(errorRequest, new IOException("plain error"));
            ResponsePayload plain = protocol.decodeResponse(this.request(8), plainError);
            assertEquals(ProtocolStatus.InvokeError, plain.getStatus());
            assertEquals("plain error", plain.getOption("message"));
        }
    }

    @Test
    public void numericStructuredStatusIsPreserved() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 使用数字类型的结构化状态，确认按该状态恢复远端错误。
            String json = "{\"status\":404,\"message\":\"不存在🙂\",\"extra\":true}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void stringStructuredStatusIsPreserved() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 使用字符串类型的结构化状态，确认兼容解析并保留错误消息。
            String json = "{\"status\":\"404\",\"message\":\"不存在🙂\",\"extra\":true}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.NotFound, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void okStatusInAnErrorFrameRemainsFailure() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 错误帧中即使声明 OK，也不能被解码为调用成功。
            String json = "{\"status\":200,\"message\":\"不存在🙂\",\"extra\":true}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void acceptStatusInAnErrorFrameRemainsFailure() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 错误帧中即使声明 Accept，也必须保持调用失败。
            int accepted = ProtocolStatus.Accept;
            String json = "{\"status\":" + accepted + ",\"message\":\"不存在🙂\",\"extra\":true}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(json));
            ResponsePayload decoded = protocol.decodeResponse(this.request(7), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals("不存在🙂", decoded.getOption("message"));
        }
    }

    @Test
    public void malformedJsonErrorRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 不完整 JSON 作为普通错误文本保留，不丢失远端诊断信息。
            String error = "{";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void arrayJsonErrorRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // JSON 数组不是结构化错误对象，仍原样返回错误文本。
            String error = "[]";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void nullJsonErrorRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // JSON null 不提供错误结构，保留其原始文本。
            String error = "null";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void nullStructuredStatusRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 结构化状态为 null 时，按普通调用错误保留完整文本。
            String error = "{\"status\":null}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void invalidStructuredStatusRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 状态无法解析成数值时，不得吞掉原始错误消息。
            String error = "{\"status\":\"bad\"}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void outOfRangeStructuredStatusRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 状态超出协议取值范围时，回退到普通调用错误。
            String error = "{\"status\":99999}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void multipleStructuredErrorsRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 多个相邻 JSON 对象不是单个错误结构，应保留整段文本。
            String error = "{\"status\":404} {\"status\":200}";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void ordinaryErrorTextRemainsReadable() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 普通 Unicode 错误文本不需要 JSON 包装，解码后保持可读。
            String error = "ordinary error 中文🙂";
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse wire = protocol.encodeErrorResponse(errorRequest, new IOException(error));
            ResponsePayload decoded = protocol.decodeResponse(this.request(9), wire);
            assertEquals(ProtocolStatus.InvokeError, decoded.getStatus());
            assertEquals(error, decoded.getOption("message"));
        }
    }

    @Test
    public void emptySuccessfulResponseIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 成功 HTTP 响应没有协议正文，必须拒绝解码。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[0], Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void truncatedSuccessfulResponseIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 成功帧缺少结束标记，必须报告截断错误。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { 'R', 'n' }, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void trailingSuccessfulResponseIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 结束标记之后仍有数据，必须拒绝多余尾部。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { 'R', 'n', 'z', 'x' }, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void unknownSuccessfulResponseTagIsRejected() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 未知响应标签不能被当作合法返回值。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { '?' }, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), wire));
        }
    }

    @Test
    public void terminatedNullResponseIsSuccessful() throws Exception {
        // 发布用于编解码的服务，并创建当前挂载点的编解码器。
        MetConfiguration configuration = this.configuration().setDefaultGroup("wire");
        try (MetContext context = configuration.buildContext()) {
            context.publisher().rsfService(Echo.class, value -> value).name("Echo").register();
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 带完整结束标记的 null 响应是合法成功结果。
            HttpResponse wire = HttpResponse.newBuffered(200, Map.of(), new byte[] { 'R', 'n', 'z' }, Map.of());
            assertNull(protocol.decodeResponse(this.request(1), wire).getReturnData());
        }
    }

    @Test
    public void nonSuccessHttpStatusPreservesHeadersWithoutDecodingBody() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 网关错误正文不必是 Hprose，HTTP 状态及追踪头仍应反馈调用方。
            Map<String, List<String>> gatewayHeaders = Map.of("trace-id", List.of("gateway"));
            HttpResponse gateway = HttpResponse.newBuffered(503, gatewayHeaders, new byte[] { '?' }, Map.of());
            ResponsePayload response = protocol.decodeResponse(this.request(42), gateway);
            assertEquals(42, response.getRequestID());
            assertEquals(503, response.getStatus());
            assertEquals("HTTP status 503", response.getOption("message"));
            assertEquals("gateway", response.getOption("trace-id"));
            assertNull(response.getReturnData());
        }
    }

    @Test
    public void truncatedErrorResponseIsRejected() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 先生成完整错误帧，再移除结束标记模拟截断。
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse valid = protocol.encodeErrorResponse(errorRequest, new IOException("remote failure"));
            byte[] body = valid.body();

            byte[] invalid = Arrays.copyOf(body, body.length - 1);
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), invalid, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), response));
        }
    }

    @Test
    public void trailingErrorResponseIsRejected() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 先生成完整错误帧，再追加尾部数据模拟损坏。
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse valid = protocol.encodeErrorResponse(errorRequest, new IOException("remote failure"));
            byte[] body = valid.body();

            byte[] invalid = Arrays.copyOf(body, body.length + 1);
            HttpResponse response = HttpResponse.newBuffered(200, Map.of(), invalid, Map.of());
            assertThrows(IOException.class, () -> protocol.decodeResponse(this.request(1), response));
        }
    }

    @Test
    public void terminatedErrorResponsePreservesRemoteFailure() throws Exception {
        // 创建当前挂载点的编解码器，错误响应解码不依赖已发布服务。
        MetConfiguration configuration = this.configuration();
        try (MetContext context = configuration.buildContext()) {
            ProtocolConfig config = ProtocolConfig.newConfig("hprose", "hprose", Map.of("contextPath", "/rpc"));
            HproseHttpCodec protocol = new HproseHttpCodec(config, this.routes(context, config), context);

            // 完整错误帧应恢复原始远端错误消息。
            HttpRequest errorRequest = HttpRequest.newBuffered("POST", "/", Map.of(), new byte[0], 1, Map.of());
            HttpResponse valid = protocol.encodeErrorResponse(errorRequest, new IOException("remote failure"));
            byte[] body = valid.body();

            ResponsePayload decoded = protocol.decodeResponse(this.request(1), valid);
            assertEquals("remote failure", decoded.getOption("message"));
        }
    }

    private RequestPayload request(long id) {
        // 构造当前测试服务的调用描述，调用方自行决定请求 ID。
        RequestPayload request = new RequestPayload();
        request.setRequestID(id);
        request.setServiceGroup("wire");
        request.setServiceName("Echo");
        request.setServiceVersion("1.0.0");
        request.setTargetMethod("echo");
        request.addParameter(String.class.getName(), null);
        return request;
    }

    private HttpResponse response(HproseHttpCodec codec, HproseDecodedCall call, //
            ResponsePayload response) throws Exception {
        // 单条结果编码后补充 Hprose 结束标记，组成完整 HTTP 响应正文。
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(codec.encodeResponse(call, response));
        bytes.write('z');
        return HttpResponse.newBuffered(200, Map.of(), bytes.toByteArray(), Map.of());
    }
}
