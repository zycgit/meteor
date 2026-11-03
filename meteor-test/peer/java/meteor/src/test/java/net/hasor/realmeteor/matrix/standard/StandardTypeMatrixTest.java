/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.realmeteor.matrix.standard;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.complex.ComplexCustomer;
import net.hasor.example.contract.complex.ComplexDocument;
import net.hasor.example.contract.complex.ComplexIndex;
import net.hasor.example.contract.complex.ComplexLine;
import net.hasor.example.contract.types.TypeValue;
import net.hasor.meteor.test.nxn.NxnMatrix;
import net.hasor.realmeteor.environment.execution.JUnitJobCondition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** 基础类型与复杂结构共用标准进程控制，每条计划只调用所属场景的一个具体类型方法。 */
@ParameterizedClass(name = "{0}", quoteTextArguments = false)
@MethodSource("configurations")
public class StandardTypeMatrixTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String id;
    private final JsonNode testCase;

    public StandardTypeMatrixTest(String id, Map<String, Object> testCase) {
        this.id = id;
        this.testCase = JSON.valueToTree(testCase);
    }

    public static List<Object[]> configurations() {
        return NxnMatrix.arguments(StandardTypeMatrixTest.class.getName());
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void typedApiPreservesDeclaredValues() throws Exception {
        // 端点、协议与请求均来自本次计划，初始化失败仍关联到同一个矩阵单元。
        String caseId = this.testCase.required("caseId").textValue();
        JUnitJobCondition.phase(caseId, "preparing");
        assertEquals("direct", this.testCase.required("discovery").textValue(), this.id);
        Path plan = Path.of(System.getProperty("meteor.test.matrix.plan")).toAbsolutePath();
        Path output = Files.createDirectories(plan.getParent().resolve("standard-type-cases"));
        Path directory = Files.createTempDirectory(output, "case-");
        JSON.writeValue(directory.resolve("case.json").toFile(), this.testCase);
        JsonNode request = this.testCase.required("request");
        Path requestFile = directory.resolve("request.json");
        JSON.writeValue(requestFile.toFile(), request);
        JsonNode providerEndpoint = this.testCase.required("provider");
        JsonNode consumerEndpoint = this.testCase.required("consumer");

        // 提供者与消费者在独立进程和网络节点运行，消费者只有远程代理。
        try (StandardPeerProcess provider = StandardPeerProcess.start( //
                providerEndpoint, directory.resolve("provider"), requestFile, request, Map.of())) {
            JsonNode ready = provider.awaitReady();
            String target = ready.required("target").textValue();
            URI address = URI.create(target);
            assertNotNull(address.getScheme(), this.id);
            assertNotNull(address.getHost(), this.id);
            assertTrue(address.getPort() > 0, this.id);
            Map<String, String> variables = Map.of("${target}", target);
            try (StandardPeerProcess consumer = StandardPeerProcess.start( //
                    consumerEndpoint, directory.resolve("consumer"), requestFile, request, variables)) {
                JUnitJobCondition.phase(caseId, "testing");
                JsonNode response = consumer.awaitResult().required("value");

                // 每条计划对应唯一的强类型 RPC；业务收据证明响应由服务端读取字段后生成。
                String api = this.testCase.required("api").textValue();
                String scenario = this.testCase.required("scenario").textValue();
                if (StringUtils.equals("scalar-v1", api) && StringUtils.equals("echo", scenario)) {
                    assertScalar(request, response);
                } else if (StringUtils.equals("complex-v1", api) && StringUtils.equals("document", scenario)) {
                    assertDocument(request.required("document"), response);
                } else if (StringUtils.equals("complex-v1", api) && StringUtils.equals("index", scenario)) {
                    assertIndex(request.required("index"), response);
                } else {
                    fail("Unknown standard type API/scenario: " + api + "/" + scenario);
                }
                provider.assertRunning();
                JUnitJobCondition.phase(caseId, "cleanup");
            }
        }
    }

    private void assertScalar(JsonNode request, JsonNode response) throws Exception {
        assertTrue(response.required("booleanValue").isBoolean(), this.id);
        JsonNode integer = response.required("int32Value");
        assertTrue(integer.isIntegralNumber() && integer.canConvertToInt(), this.id);
        assertTrue(response.required("textValue").isTextual(), this.id);
        assertTrue(response.required("binaryValue").isTextual(), this.id);
        TypeValue expected = JSON.treeToValue(request, TypeValue.class);
        TypeValue actual = JSON.treeToValue(response, TypeValue.class);
        assertEquals(expected.getCaseId(), actual.getCaseId(), this.id);
        assertEquals(expected.isBooleanValue(), actual.isBooleanValue(), this.id);
        assertEquals(expected.getInt32Value(), actual.getInt32Value(), this.id);
        assertEquals(expected.getTextValue(), actual.getTextValue(), this.id);
        assertArrayEquals(expected.getBinaryValue(), actual.getBinaryValue(), this.id);
        assertEquals("accepted:" + expected.getCaseId(), actual.getReceipt(), this.id);
    }

    private void assertDocument(JsonNode request, JsonNode response) throws Exception {
        ComplexDocument expected = JSON.treeToValue(request, ComplexDocument.class);
        ComplexDocument actual = JSON.treeToValue(response, ComplexDocument.class);
        assertEquals(expected.getDocumentId(), actual.getDocumentId(), this.id);
        ComplexCustomer customer = expected.getCustomer();
        ComplexCustomer actualCustomer = actual.getCustomer();
        assertNotNull(actualCustomer, this.id);
        assertEquals(customer.getCode(), actualCustomer.getCode(), this.id);
        assertEquals(customer.getName(), actualCustomer.getName(), this.id);
        assertEquals(customer.isVerified(), actualCustomer.isVerified(), this.id);
        assertEquals(customer.getRating(), actualCustomer.getRating(), 0.0, this.id);
        assertEquals(expected.getRoute(), actual.getRoute(), this.id);
        assertEquals(expected.getLines().size(), actual.getLines().size(), this.id);
        int quantity = 0;
        for (int index = 0; index < expected.getLines().size(); index++) {
            ComplexLine line = expected.getLines().get(index);
            assertLine(line, actual.getLines().get(index));
            quantity += line.getQuantity();
        }
        String receipt = "document:" + expected.getDocumentId() + ":" + quantity;
        assertEquals(receipt, actual.getReceipt(), this.id);
    }

    private void assertIndex(JsonNode request, JsonNode response) throws Exception {
        ComplexIndex expected = JSON.treeToValue(request, ComplexIndex.class);
        ComplexIndex actual = JSON.treeToValue(response, ComplexIndex.class);
        assertEquals(expected.getRevision(), actual.getRevision(), this.id);
        assertNotNull(actual.getLinesByCode(), this.id);
        assertEquals(expected.getLinesByCode().keySet(), actual.getLinesByCode().keySet(), this.id);
        for (Map.Entry<String, ComplexLine> entry : expected.getLinesByCode().entrySet()) {
            assertLine(entry.getValue(), actual.getLinesByCode().get(entry.getKey()));
        }
        assertEquals(expected.getStockByCode(), actual.getStockByCode(), this.id);
        int quantity = 0;
        for (int value : expected.getStockByCode().values()) {
            quantity += value;
        }
        assertEquals("index:" + expected.getRevision() + ":" + quantity, actual.getReceipt(), this.id);
    }

    private void assertLine(ComplexLine expected, ComplexLine actual) {
        assertNotNull(actual, this.id);
        assertEquals(expected.getSku(), actual.getSku(), this.id);
        assertEquals(expected.getQuantity(), actual.getQuantity(), this.id);
        assertEquals(expected.getUnitPrice(), actual.getUnitPrice(), 0.0, this.id);
    }
}
