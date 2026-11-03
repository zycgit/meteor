/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.matrix.standard;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.test.nxn.NxnMatrix;
import net.hasor.realmeteor.environment.execution.JUnitJobCondition;
import net.hasor.realmeteor.integration.FrameworkRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** 每个参数对应计划中的一个提供者与消费者组合，不在执行阶段重新匹配。 */
@ParameterizedClass(name = "{0}", quoteTextArguments = false)
@MethodSource("configurations")
public class StandardMatrixTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String id;
    private final JsonNode testCase;

    public StandardMatrixTest(String id, Map<String, Object> testCase) {
        this.id = id;
        this.testCase = JSON.valueToTree(testCase);
    }

    public static List<Object[]> configurations() throws Exception {
        return NxnMatrix.arguments(StandardMatrixTest.class.getName());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void savesAndFindsOrder() throws Exception {
        // 请求及双方身份都来自同一份纯计划，产物随本轮分组保留。
        String caseId = this.testCase.required("caseId").textValue();
        JUnitJobCondition.phase(caseId, "preparing");
        Path plan = Path.of(System.getProperty("meteor.test.matrix.plan")).toAbsolutePath();
        Path output = Files.createDirectories(plan.getParent().resolve("standard-cases"));
        Path directory = Files.createTempDirectory(output, "case-");
        JSON.writeValue(directory.resolve("case.json").toFile(), this.testCase);
        JsonNode request = this.testCase.required("request");
        Path requestFile = directory.resolve("request.json");
        JSON.writeValue(requestFile.toFile(), request);
        JsonNode providerEndpoint = this.testCase.required("provider");
        JsonNode consumerEndpoint = this.testCase.required("consumer");
        String discovery = this.testCase.required("discovery").textValue();

        // 注册场景只传连接配置；提供者的业务地址仅用于直连场景。
        FrameworkRegistry environment = null;
        if (!StringUtils.equals("direct", discovery)) {
            environment = FrameworkRegistry.open(discovery, directory.resolve("registry"));
        }
        try (FrameworkRegistry registry = environment) {
            Map<String, String> providerVariables = variables( //
                    directory, requestFile, providerEndpoint, registry, "provider");
            try (StandardPeerProcess provider = StandardPeerProcess.start( //
                    providerEndpoint, directory.resolve("provider"), requestFile, request, providerVariables)) {
                JsonNode ready = provider.awaitReady();
                assertFrameworkIdentity(providerEndpoint, ready, "Provider");
                Map<String, String> consumerVariables = variables( //
                        directory, requestFile, consumerEndpoint, registry, "consumer");
                if (StringUtils.equals("direct", discovery)) {
                    String target = ready.required("target").textValue();
                    URI address = URI.create(target);
                    assertNotNull(address.getScheme(), this.id);
                    assertNotNull(address.getHost(), this.id);
                    assertTrue(address.getPort() > 0, this.id);
                    consumerVariables.put("${target}", target);
                }

                // 每次执行只有一次保存与查询，结果必须来自真实对端的正常退出。
                try (StandardPeerProcess consumer = StandardPeerProcess.start( //
                        consumerEndpoint, directory.resolve("consumer"), requestFile, request, consumerVariables)) {
                    JUnitJobCondition.phase(caseId, "testing");
                    JsonNode result = consumer.awaitResult();
                    assertFrameworkIdentity(consumerEndpoint, result, "Consumer");
                    assertOrder(request, result.required("saved"));
                    assertOrder(request, result.required("order"));
                    provider.assertRunning();
                    // 仅成功路径进入清理阶段，调用失败后的次生关闭异常不覆盖原失败阶段。
                    JUnitJobCondition.phase(caseId, "cleanup");
                }
            }
        }
    }

    private Map<String, String> variables(Path directory, Path requestFile, JsonNode endpoint,
            FrameworkRegistry registry, String role) throws Exception {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("${requestFile}", requestFile.toString());
        variables.put("${result}", directory.resolve(role + "-result.json").toString());
        if (registry != null) {
            String framework = endpoint.required("framework").textValue();
            Properties settings = registry.settings(framework, role);
            Path file = directory.resolve(role + ".properties");
            try (OutputStream stream = Files.newOutputStream(file)) {
                settings.store(stream, this.id);
            }
            variables.put("${settings}", file.toString());
        }
        return variables;
    }

    private void assertFrameworkIdentity(JsonNode endpoint, JsonNode event, String role) {
        String framework = endpoint.path("framework").asText();
        if (!framework.isBlank()) {
            assertEquals(role, event.required("role").textValue(), this.id);
            String application = framework + "-" + role.toLowerCase(Locale.ROOT);
            assertEquals(application, event.required("application").textValue(), this.id);
        }
    }

    private void assertOrder(JsonNode expected, JsonNode actual) {
        JsonNode actualId = actual.required("id");
        assertTrue(actualId.isIntegralNumber() && actualId.canConvertToInt(), this.id);
        assertEquals(expected.required("id").intValue(), actual.required("id").intValue(), this.id);
        String expectedDescription = expected.required("description").textValue();
        assertEquals(expectedDescription, actual.required("description").textValue(), this.id);
        byte[] expectedBytes = Base64.getDecoder().decode(expected.required("attachment").textValue());
        byte[] actualBytes = Base64.getDecoder().decode(actual.required("attachment").textValue());
        assertArrayEquals(expectedBytes, actualBytes, this.id);
    }
}
