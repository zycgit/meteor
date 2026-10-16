/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectStatusExceptionTest {
    @Test
    public void canceledUsesHttp499() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("canceled", "failure", null, null);
        assertEquals(499, failure.httpStatus());
    }

    @Test
    public void unknownUsesHttp500() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("unknown", "failure", null, null);
        assertEquals(500, failure.httpStatus());
    }

    @Test
    public void invalidArgumentUsesHttp400() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("invalid_argument", "failure", null, null);
        assertEquals(400, failure.httpStatus());
    }

    @Test
    public void deadlineExceededUsesHttp504() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("deadline_exceeded", "failure", null, null);
        assertEquals(504, failure.httpStatus());
    }

    @Test
    public void notFoundUsesHttp404() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("not_found", "failure", null, null);
        assertEquals(404, failure.httpStatus());
    }

    @Test
    public void alreadyExistsUsesHttp409() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("already_exists", "failure", null, null);
        assertEquals(409, failure.httpStatus());
    }

    @Test
    public void permissionDeniedUsesHttp403() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("permission_denied", "failure", null, null);
        assertEquals(403, failure.httpStatus());
    }

    @Test
    public void resourceExhaustedUsesHttp429() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("resource_exhausted", "failure", null, null);
        assertEquals(429, failure.httpStatus());
    }

    @Test
    public void failedPreconditionUsesHttp400() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("failed_precondition", "failure", null, null);
        assertEquals(400, failure.httpStatus());
    }

    @Test
    public void abortedUsesHttp409() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("aborted", "failure", null, null);
        assertEquals(409, failure.httpStatus());
    }

    @Test
    public void outOfRangeUsesHttp400() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("out_of_range", "failure", null, null);
        assertEquals(400, failure.httpStatus());
    }

    @Test
    public void unimplementedUsesHttp501() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("unimplemented", "failure", null, null);
        assertEquals(501, failure.httpStatus());
    }

    @Test
    public void internalUsesHttp500() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("internal", "failure", null, null);
        assertEquals(500, failure.httpStatus());
    }

    @Test
    public void unavailableUsesHttp503() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("unavailable", "failure", null, null);
        assertEquals(503, failure.httpStatus());
    }

    @Test
    public void dataLossUsesHttp500() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("data_loss", "failure", null, null);
        assertEquals(500, failure.httpStatus());
    }

    @Test
    public void unauthenticatedUsesHttp401() {
        // 标准错误码映射到约定的 HTTP 状态。
        ConnectStatusException failure = new ConnectStatusException("unauthenticated", "failure", null, null);
        assertEquals(401, failure.httpStatus());
    }

    @Test
    public void http400FallsBackToInternal() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("internal", ConnectStatusException.fromHttpStatus(400).getCode());
    }

    @Test
    public void http401FallsBackToUnauthenticated() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unauthenticated", ConnectStatusException.fromHttpStatus(401).getCode());
    }

    @Test
    public void http403FallsBackToPermissionDenied() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("permission_denied", ConnectStatusException.fromHttpStatus(403).getCode());
    }

    @Test
    public void http404FallsBackToUnimplemented() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unimplemented", ConnectStatusException.fromHttpStatus(404).getCode());
    }

    @Test
    public void http429FallsBackToUnavailable() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unavailable", ConnectStatusException.fromHttpStatus(429).getCode());
    }

    @Test
    public void http502FallsBackToUnavailable() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unavailable", ConnectStatusException.fromHttpStatus(502).getCode());
    }

    @Test
    public void http503FallsBackToUnavailable() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unavailable", ConnectStatusException.fromHttpStatus(503).getCode());
    }

    @Test
    public void http504FallsBackToUnavailable() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unavailable", ConnectStatusException.fromHttpStatus(504).getCode());
    }

    @Test
    public void http418FallsBackToUnknown() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unknown", ConnectStatusException.fromHttpStatus(418).getCode());
    }

    @Test
    public void http200FallsBackToUnknown() {
        // 缺少 Connect 错误正文时按 HTTP 状态推导协议错误。
        assertEquals("unknown", ConnectStatusException.fromHttpStatus(200).getCode());
    }

    @Test
    public void retainsStructuredErrorDetails() {
        // 错误详情保留类型地址和 Base64 数据。
        Map<String, Object> detail = Map.of("type", "example.ErrorDetail", "value", "AQI=");
        ConnectStatusException failure = new ConnectStatusException("internal", "rejected", List.of(detail), Map.of());
        assertEquals(List.of(detail), failure.getDetails());
    }

    @Test
    public void retainsRepeatedMetadataValues() {
        // 重复元数据不折叠为单个字符串。
        Map<String, List<String>> metadata = Map.of("x-trace", List.of("first", "second"));
        ConnectStatusException failure = new ConnectStatusException("internal", "rejected", List.of(), metadata);
        assertEquals(metadata, failure.getMetadata());
    }

    @Test
    public void findsDirectConnectFailure() {
        // 已是协议错误时返回同一个对象。
        ConnectStatusException failure = new ConnectStatusException("not_found", "missing", List.of(), Map.of());
        assertSame(failure, ConnectStatusException.find(failure));
    }

    @Test
    public void findsNestedConnectFailure() {
        // 门面封装异常之后仍能识别原始协议错误。
        ConnectStatusException failure = new ConnectStatusException("not_found", "missing", List.of(), Map.of());
        Throwable wrapper = new IllegalStateException("outer", new RuntimeException(failure));
        assertSame(failure, ConnectStatusException.find(wrapper));
    }

    @Test
    public void unrelatedFailureHasNoConnectStatus() {
        // 普通应用异常不会被伪造为 Connect 协议错误。
        assertNull(ConnectStatusException.find(new IllegalArgumentException("business")));
    }

    @Test
    public void nullFailureHasNoConnectStatus() {
        // 没有异常时返回空结果。
        assertNull(ConnectStatusException.find(null));
    }

    @Test
    public void rejectsUnknownErrorCode() {
        // 业务方不能构造标准列表之外的错误码。
        assertThrows(IllegalArgumentException.class, () -> {
            new ConnectStatusException("custom_error", "failure", null, null);
        });
    }

    @Test
    public void rejectsNullErrorCode() {
        // 缺少错误码时立即报告配置错误。
        assertThrows(IllegalArgumentException.class, () -> {
            new ConnectStatusException(null, "failure", null, null);
        });
    }

    @Test
    public void nullDescriptionBecomesEmpty() {
        // 可选描述为空时提供稳定的空字符串。
        ConnectStatusException failure = new ConnectStatusException("unknown", null, null, null);
        assertEquals("", failure.getDescription());
    }

    @Test
    public void nullDetailsBecomeEmptyCollection() {
        // 不提供详情时无需调用方额外判空。
        ConnectStatusException failure = new ConnectStatusException("unknown", "failure", null, null);
        assertTrue(failure.getDetails().isEmpty());
    }

    @Test
    public void nullMetadataBecomesEmptyCollection() {
        // 不提供元数据时无需调用方额外判空。
        ConnectStatusException failure = new ConnectStatusException("unknown", "failure", null, null);
        assertTrue(failure.getMetadata().isEmpty());
    }

    @Test
    public void detailsDoNotRetainMutableInputMap() {
        // 构造后修改输入详情不会改变已创建的错误。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", "example.Detail");
        ConnectStatusException failure = new ConnectStatusException("internal", "failure", List.of(detail), Map.of());

        // 验证错误对象持有独立的详情快照。
        detail.put("type", "changed.Detail");
        assertEquals("example.Detail", failure.getDetails().get(0).get("type"));
    }

    @Test
    public void metadataDoesNotRetainMutableInputValues() {
        // 构造后修改元数据列表不会改变错误对象。
        List<String> values = new ArrayList<>();
        values.add("first");
        Map<String, List<String>> metadata = Map.of("x-trace", values);
        ConnectStatusException failure = new ConnectStatusException("internal", "failure", null, metadata);

        // 验证重复值仍是构造时的内容。
        values.add("second");
        assertEquals(List.of("first"), failure.getMetadata().get("x-trace"));
    }

    @Test
    public void returnedDetailsCannotBeChanged() {
        // 已发布的错误详情保持只读。
        Map<String, Object> detail = Map.of("type", "example.Detail", "value", "AQI=");
        ConnectStatusException failure = new ConnectStatusException("internal", "failure", List.of(detail), Map.of());
        assertThrows(UnsupportedOperationException.class, () -> failure.getDetails().get(0).put("value", "AA=="));
    }

    @Test
    public void returnedMetadataCannotBeChanged() {
        // 接收方不能修改错误对象内的重复元数据列表。
        Map<String, List<String>> metadata = Map.of("x-trace", List.of("first"));
        ConnectStatusException failure = new ConnectStatusException("internal", "failure", null, metadata);
        assertThrows(UnsupportedOperationException.class, () -> failure.getMetadata().get("x-trace").add("second"));
    }
}
