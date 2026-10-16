/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.codec;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.bootstrap.MetConfiguration;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GrpcAnySiblingImportTest {
    public interface EnvelopeService {
        JsonNode exchange(JsonNode request);
    }

    @Test
    public void anyPayloadResolvesFromEntrySiblingImport() throws Exception {
        // 入口同时导入服务与载荷，服务文件自身只依赖标准 Any 定义。
        URL source = this.getClass().getResource("/proto/any-sibling/aggregator.proto");
        GrpcProtoResource resource = new GrpcProtoResource(source);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode value = mapper.readTree("""
                {"payload":{"@type":"type.googleapis.com/sibling_any.Payload","value":"sibling"}}
                """);
        MetConfiguration configuration = new MetConfiguration();
        configuration.setDefaultProtocol("grpc");
        configuration.connector("http").protocol("grpc");

        try (MetContext context = configuration.buildContext()) {
            MetBindInfo<EnvelopeService> service = context.publisher() //
                    .rsfService(EnvelopeService.class) //
                    .aliasName("grpc", "sibling_any.EnvelopeService") //
                    .serialize("Protobuf") //
                    .bindResource(resource) //
                    .register();
            ProtocolConfig protocol = ProtocolConfig.newConfig("grpc", "grpc", Map.of("contextPath", "/"));
            GrpcHttpCodec codec = new GrpcHttpCodec(protocol, context);
            RequestPayload request = new RequestPayload();
            request.setServiceGroup(service.getBindGroup());
            request.setServiceName(service.getBindName());
            request.setServiceVersion(service.getBindVersion());
            request.setTargetMethod("exchange");
            request.setSerializeType("Protobuf");
            request.addParameter(JsonNode.class.getName(), value);

            // 编码和解码均需从入口的兄弟依赖中解析 Any 的具体载荷类型。
            byte[] frame = codec.encode(request).body();
            byte[] message = codec.readMessage(new ByteArrayInputStream(frame), null);
            Object decoded = codec.decodeValue(request, JsonNode.class, message, true);
            assertEquals(value, decoded);
        }
    }
}
