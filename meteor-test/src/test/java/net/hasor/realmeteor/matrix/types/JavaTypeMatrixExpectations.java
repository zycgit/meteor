/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix.types;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InaccessibleObjectException;
import java.util.Date;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import net.hasor.cobble.StringUtils;
import net.hasor.example.contract.types.JavaTypeProfile;
import net.hasor.meteor.protocol.grpc.GrpcStatusException;
import net.hasor.meteor.protocol.soap.SoapFault;
import net.hasor.meteor.protocol.xmlrpc.XmlRpcException;
import static org.junit.Assert.*;

/** 审阅过的有限拒绝类别；资源只有纯数据，没有任意异常、脚本或正则执行入口。 */
final class JavaTypeMatrixExpectations {
    private static final String   RESOURCE_PATH = "/matrix/java-type-expectations.json";
    private static final JsonNode RULES         = readRules();
    private final        String   reasonCode;
    private final        String   outcome;

    private JavaTypeMatrixExpectations(String reasonCode, String outcome) {
        this.reasonCode = reasonCode;
        this.outcome = outcome;
    }

    static JavaTypeMatrixExpectations forCase(JavaTypeProfile profile, String type, String vector) {
        JsonNode selected = null;
        int priority = Integer.MIN_VALUE;
        for (JsonNode rule : RULES) {
            JsonNode select = rule.path("select");
            boolean selectedProfile = matches(select.path("profile"), profile.toString());
            boolean selectedType = matches(select.path("type"), type);
            boolean selectedVector = matches(select.path("vector"), vector);
            if (!selectedProfile || !selectedType || !selectedVector) {
                continue;
            }
            int candidatePriority = rule.path("priority").asInt();
            if (candidatePriority == priority) {
                String caseKey = profile + "/" + type + "/" + vector;
                throw new IllegalStateException("Ambiguous Java type expectation: " + caseKey);
            }
            if (candidatePriority > priority) {
                selected = rule;
                priority = candidatePriority;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("Missing Java type expectation: " + profile + "/" + type + "/" + vector);
        }
        return new JavaTypeMatrixExpectations(selected.path("reasonCode").asText(), selected.path("outcome").asText());
    }

    boolean publicationRejected() {
        return StringUtils.equals(this.reasonCode, "SOAP_DEFAULT_MAPPING") //
                || StringUtils.equals(this.reasonCode, "THRIFT_DEFAULT_MAPPING");
    }

    boolean invocationRejected() {
        return StringUtils.equals(this.outcome, "rejection") && !this.publicationRejected();
    }

    Object expectedValue(Object value) {
        if (StringUtils.equals(this.reasonCode, "XMLRPC_DATE_SECONDS")) {
            long expectedMillis = Math.floorDiv(((Date) value).getTime(), 1000) * 1000;
            return new Date(expectedMillis);
        }
        if (StringUtils.equals(this.reasonCode, "HPROSE_UTC_REGION")) {
            return ((ZonedDateTime) value).withZoneSameInstant(ZoneOffset.UTC);
        }
        return value;
    }

    void assertRejection(Throwable failure, Class<?> contract, JavaTypeProfile profile) throws Exception {
        Class<?> declaredType = contract.getMethods()[0].getReturnType().getMethod("getValue").getReturnType();
        switch (this.reasonCode) {
            case "JSON_JAVA_TIME_MODULE" -> {
                InvalidDefinitionException cause = cause(failure, InvalidDefinitionException.class);
                contains(cause, "Java 8 date/time type", "not supported by default", "jackson-datatype-jsr310");
            }
            case "HESSIAN_JAVA_TIME_ACCESS", "XMLRPC_JAVA_TIME_ACCESS" -> {
                InaccessibleObjectException cause = cause(failure, InaccessibleObjectException.class);
                contains(cause, "java.time", "does not \"opens java.time\"");
            }
            case "SOAP_DEFAULT_MAPPING" -> {
                IllegalArgumentException cause = cause(failure, IllegalArgumentException.class);
                String expected = "Cannot map SOAP XML type " + declaredType.getName();
                contains(cause, expected, "only concrete ordinary POJOs are supported");
            }
            case "AUTO_PROTOBUF_MAPPING" -> {
                UnsupportedOperationException cause = cause(failure, UnsupportedOperationException.class);
                String protocol = profile == JavaTypeProfile.CONNECT_PROTOBUF ? "Connect" : "gRPC";
                contains(cause, "Service has no prepared " + protocol + " binding:", contract.getSimpleName());
            }
            case "TRIPLE_AUTO_PROTOBUF_MAPPING" -> {
                GrpcStatusException cause = cause(failure, GrpcStatusException.class);
                assertEquals(12, cause.getCode());
                assertEquals("Triple gRPC POJO wrapper requires Hessian2", cause.getDescription());
            }
            case "THRIFT_DEFAULT_MAPPING" -> {
                IllegalArgumentException cause = cause(failure, IllegalArgumentException.class);
                assertEquals("Unsupported Thrift Java type: " + declaredType.getName(), cause.getMessage());
            }
            case "ONC_DEFAULT_MAPPING" -> {
                IllegalArgumentException cause = cause(failure, IllegalArgumentException.class);
                assertEquals("ONC RPC service has no prepared protocol contract", cause.getMessage());
            }
            case "ONC_SCALAR_NULL" -> {
                IllegalArgumentException cause = cause(failure, IllegalArgumentException.class);
                String expected;
                if (declaredType == Byte.class || declaredType == Short.class || declaredType == Character.class) {
                    expected = "XDR " + declaredType.getSimpleName() + " cannot be null";
                } else {
                    expected = "XDR scalar cannot be null; use an explicit optional declaration";
                }
                assertEquals(expected, cause.getMessage());
            }
            case "ONC_STRING_NULL" -> {
                IllegalArgumentException cause = cause(failure, IllegalArgumentException.class);
                assertEquals("XDR string cannot be null", cause.getMessage());
            }
            case "ONC_ASCII_STRING" -> {
                IllegalArgumentException cause = cause(failure, IllegalArgumentException.class);
                assertEquals("XDR string must contain ASCII characters", cause.getMessage());
            }
            case "XMLRPC_INT32_RANGE", "XMLRPC_UUID_MAPPING" -> {
                IOException cause = cause(failure, IOException.class);
                assertEquals("XML-RPC integer exceeds signed 32-bit range", cause.getMessage());
            }
            case "XMLRPC_DECIMAL_MAPPING" -> {
                IOException cause = cause(failure, IOException.class);
                assertEquals("Unsupported XML-RPC value: java.math.BigDecimal", cause.getMessage());
            }
            case "XMLRPC_XML_CHARACTER" -> {
                IOException cause = cause(failure, IOException.class);
                assertEquals("XML-RPC text contains a character forbidden by XML 1.0", cause.getMessage());
            }
            case "SOAP_XML_CHARACTER" -> {
                SoapFault cause = cause(failure, SoapFault.class);
                contains(cause, "&#0", "invalid XML character");
            }
            case "XMLRPC_JAVA_TIME_PARAMS" -> {
                XmlRpcException cause = cause(failure, XmlRpcException.class);
                assertEquals(-32602, cause.getCode());
                assertEquals("Invalid params", cause.getMessage());
            }
            default -> fail("No reviewed rejection assertion for " + this.reasonCode);
        }
    }

    private static <T extends Throwable> T cause(Throwable failure, Class<T> expectedType) {
        // 只接受指定的精确异常；外层反射、Future 和 RPC 包装不改变拒绝原因。
        Throwable current = failure;
        T found = null;
        for (int depth = 0; current != null && depth < 32; depth++) {
            if (current.getClass() == expectedType) {
                found = expectedType.cast(current);
            }
            current = current.getCause();
        }
        if (found != null) {
            return found;
        }
        throw new AssertionError("Expected precise cause " + expectedType.getName(), failure);
    }

    private static void contains(Throwable failure, String... fragments) {
        for (String fragment : fragments) {
            assertTrue(failure.getMessage(), failure.getMessage().contains(fragment));
        }
    }

    private static boolean matches(JsonNode selection, String value) {
        if (selection.isMissingNode()) {
            return true;
        }
        if (selection.isTextual()) {
            return StringUtils.equals(selection.textValue(), value);
        }
        for (JsonNode candidate : selection) {
            if (StringUtils.equals(candidate.textValue(), value)) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode readRules() {
        try (InputStream input = JavaTypeMatrixExpectations.class.getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                throw new IllegalStateException("Missing Java type expectation resource");
            }
            return new ObjectMapper().readTree(input).path("rules");
        } catch (IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
