/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.connect.codec;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.hasor.meteor.connector.ProtocolConfig;
import net.hasor.meteor.connector.transport.http.channel.HttpBodyStream;
import net.hasor.meteor.connector.transport.http.channel.HttpRequest;
import net.hasor.meteor.connector.transport.http.channel.HttpResponse;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectCorsTest {
    @Test
    public void sameOriginGetContinuesToTheSafeMethodCheck() {
        // GET 的无副作用校验属于协议映射，跨域层只判断来源。
        ConnectCors cors = this.cors(Map.of());

        // 没有跨域字段时，GET 继续进入协议。
        assertNull(cors.intercept(this.request("GET", Map.of())));
    }

    @Test
    public void preflightAllowsGetWithConnectMetadata() {
        // 浏览器为带超时和元数据的 GET 查询发起预检。
        ConnectCors cors = this.cors(Map.of());
        Map<String, List<String>> headers = Map.of( //
                "origin", List.of("https://browser.example"), //
                "access-control-request-method", List.of("GET"), //
                "access-control-request-headers", List.of("Connect-Timeout-Ms,X-Trace"));

        // GET 与 Connect 请求头均包含在正式许可中。
        HttpResponse response = cors.intercept(this.request("OPTIONS", headers));
        assertEquals(200, response.status());
        assertEquals("GET, POST", response.header("access-control-allow-methods"));
        assertEquals("connect-timeout-ms, x-trace", response.header("access-control-allow-headers"));
    }

    @Test
    public void sameOriginPostContinuesWithoutAnOriginHeader() {
        // 准备没有跨域字段的正常请求。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.request("POST", Map.of());

        // 同源调用继续进入 Connect 会话。
        assertNull(cors.intercept(request));
    }

    @Test
    public void defaultPolicyAcceptsCrossOriginPost() {
        // 默认配置接受任意来源的非凭证请求。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 允许的 POST 交给协议会话处理。
        assertNull(cors.intercept(request));
    }

    @Test
    public void explicitOriginsAcceptASecondConfiguredOrigin() {
        // 来源列表允许空白分隔，并保留每个完整来源。
        String origins = "https://first.example, https://second.example";
        ConnectCors cors = this.cors(Map.of("corsOrigins", origins));
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://second.example")));

        // 第二个来源也能进入协议处理。
        assertNull(cors.intercept(request));
    }

    @Test
    public void rejectsAPostFromAnUnlistedOrigin() {
        // 白名单只包含完整的可信来源。
        ConnectCors cors = this.cors(Map.of("corsOrigins", "https://browser.example"));
        String unlisted = "https://browser.example.evil.test";
        HttpRequest request = this.request("POST", Map.of("origin", List.of(unlisted)));

        // 相似前缀不能绕过精确来源检查。
        HttpResponse response = cors.intercept(request);
        assertEquals(403, response.status());
        assertNull(response.header("access-control-allow-origin"));
    }

    @Test
    public void rejectsWildcardOriginsWithCredentials() {
        // 浏览器凭证策略要求明确的来源。
        Map<String, String> options = Map.of("corsCredentials", "true");

        // 默认通配来源不能与凭证开启组合。
        assertThrows(IllegalArgumentException.class, () -> this.cors(options));
    }

    @Test
    public void credentialedResponseReflectsAnAllowedOrigin() {
        // 配置单个可信来源和凭证支持。
        String origin = "https://browser.example";
        Map<String, String> options = Map.of("corsOrigins", origin, "corsCredentials", "true");
        ConnectCors cors = this.cors(options);
        HttpRequest request = this.request("POST", Map.of("origin", List.of(origin)));

        // 响应返回精确来源和凭证许可。
        HttpResponse response = cors.decorate(request, this.response(Map.of()));
        assertEquals(origin, response.header("access-control-allow-origin"));
        assertEquals("true", response.header("access-control-allow-credentials"));
    }

    @Test
    public void defaultResponseUsesWildcardWithoutCredentials() {
        // 默认响应允许跨域读取，不开启凭证。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 通配来源与未设置的凭证头保持一致。
        HttpResponse response = cors.decorate(request, this.response(Map.of()));
        assertEquals("*", response.header("access-control-allow-origin"));
        assertNull(response.header("access-control-allow-credentials"));
    }

    @Test
    public void preflightAllowsRequestedHeadersWithoutCaseDuplicates() {
        // 浏览器请求多个字段，其中一个以不同大小写重复出现。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.preflight(" Content-Type, Connect-Protocol-Version, content-type, Authorization ");

        // 预检声明 GET 与 POST，字段列表去重并保留请求顺序。
        HttpResponse response = cors.intercept(request);
        assertEquals(200, response.status());
        assertEquals("GET, POST", response.header("access-control-allow-methods"));
        String permittedHeaders = response.header("access-control-allow-headers");
        assertEquals("content-type, connect-protocol-version, authorization", permittedHeaders);
        assertEquals("600", response.header("access-control-max-age"));
        Set<String> vary = this.tokens(response.headers().get("vary"));
        Set<String> dimensions = Set.of("origin", "access-control-request-method", "access-control-request-headers");
        assertTrue(vary.containsAll(dimensions));
    }

    @Test
    public void preflightWithoutRequestedHeadersOmitsTheAllowHeadersField() {
        // 预检可以不申请额外请求头。
        ConnectCors cors = this.cors(Map.of());
        Map<String, List<String>> headers = Map.of( //
                "origin", List.of("https://browser.example"), //
                "access-control-request-method", List.of("POST"));

        // 空字段列表不会生成空的允许字段头。
        HttpResponse response = cors.intercept(this.request("OPTIONS", headers));
        assertEquals(200, response.status());
        assertNull(response.header("access-control-allow-headers"));
    }

    @Test
    public void rejectsPreflightWithoutAnOrigin() {
        // 缺少来源的 OPTIONS 不是完整的 CORS 预检。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.request("OPTIONS", Map.of("access-control-request-method", List.of("POST")));

        // 返回请求错误且不授权来源。
        HttpResponse response = cors.intercept(request);
        assertEquals(400, response.status());
        assertNull(response.header("access-control-allow-origin"));
    }

    @Test
    public void rejectsPreflightWithoutARequestedMethod() {
        // 只有来源，尚未指定后续请求方法。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.request("OPTIONS", Map.of("origin", List.of("https://browser.example")));

        // 响应明确报告无效预检。
        assertEquals(400, cors.intercept(request).status());
    }

    @Test
    public void rejectsPreflightForAnUnsupportedMethod() {
        // Connect 不通过 DELETE 发起 RPC。
        ConnectCors cors = this.cors(Map.of());
        Map<String, List<String>> headers = Map.of( //
                "origin", List.of("https://browser.example"), //
                "access-control-request-method", List.of("DELETE"));

        // DELETE 预检不能获得 RPC 访问许可。
        assertEquals(400, cors.intercept(this.request("OPTIONS", headers)).status());
    }

    @Test
    public void rejectsPreflightFromAnUnlistedOrigin() {
        // 预检与实际请求使用同一来源白名单。
        ConnectCors cors = this.cors(Map.of("corsOrigins", "https://trusted.example"));
        HttpRequest request = this.preflight("content-type");

        // 拒绝来源时不返回跨域授权头。
        HttpResponse response = cors.intercept(request);
        assertEquals(403, response.status());
        assertNull(response.header("access-control-allow-origin"));
    }

    @Test
    public void headerAllowlistMatchesCaseInsensitively() {
        // 显式字段列表允许混合大小写与分隔空白。
        ConnectCors cors = this.cors(Map.of("corsHeaders", " Content-Type, Connect-Protocol-Version "));
        HttpRequest request = this.preflight("CONTENT-TYPE,connect-protocol-version");

        // 请求中的字段名按 HTTP 大小写无关语义匹配。
        HttpResponse response = cors.intercept(request);
        assertEquals(200, response.status());
        assertEquals("content-type, connect-protocol-version", response.header("access-control-allow-headers"));
    }

    @Test
    public void rejectsAHeaderOutsideTheAllowlist() {
        // 只允许正文类型字段。
        ConnectCors cors = this.cors(Map.of("corsHeaders", "content-type"));
        HttpRequest request = this.preflight("content-type,authorization");

        // 白名单以外的认证字段使整个预检失败。
        HttpResponse response = cors.intercept(request);
        assertEquals(403, response.status());
        assertNull(response.header("access-control-allow-headers"));
    }

    @Test
    public void wildcardHeaderPolicyStillRejectsInvalidHeaderNames() {
        // 通配配置不允许非法 HTTP 字段名称。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.preflight("x-valid,x invalid");

        // 含空格的字段不能被反射进响应头。
        assertEquals(403, cors.intercept(request).status());
    }

    @Test
    public void rejectsUnsupportedHttpMethodsWithAnAllowHeader() {
        // 正常跨域来源尝试使用不支持的方法。
        ConnectCors cors = this.cors(Map.of());
        HttpRequest request = this.request("DELETE", Map.of("origin", List.of("https://browser.example")));

        // 方法错误仍可由浏览器读取，并给出受支持的方法。
        HttpResponse response = cors.intercept(request);
        assertEquals(405, response.status());
        assertEquals("GET, POST, OPTIONS", response.header("allow"));
        assertEquals("*", response.header("access-control-allow-origin"));
    }

    @Test
    public void preflightCanDisableBrowserCaching() {
        // 缓存时间为零是有效配置。
        ConnectCors cors = this.cors(Map.of("corsMaxAge", "0"));

        // 预检直接公开配置的零缓存时间。
        HttpResponse response = cors.intercept(this.preflight("content-type"));
        assertEquals(200, response.status());
        assertEquals("0", response.header("access-control-max-age"));
    }

    @Test
    public void rejectsNegativePreflightCacheDuration() {
        // 负数不是有效的浏览器缓存时间。
        Map<String, String> options = Map.of("corsMaxAge", "-1");

        // 挂载配置构建阶段直接拒绝错误值。
        assertThrows(IllegalArgumentException.class, () -> this.cors(options));
    }

    @Test
    public void exposesApplicationMetadataButNotCookies() {
        // 响应包含业务元数据与浏览器自行管理的 Cookie。
        ConnectCors cors = this.cors(Map.of());
        Map<String, List<String>> headers = Map.of( //
                "X-Trace-Id", List.of("trace-1"), //
                "Set-Cookie", List.of("session=example"));
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 业务元数据可读取，Cookie 继续保留在真实响应中。
        HttpResponse response = cors.decorate(request, this.response(headers));
        Set<String> exposed = this.tokens(response.headers().get("access-control-expose-headers"));
        Set<String> required = Set.of("x-trace-id");
        assertTrue(exposed.containsAll(required));
        assertFalse(exposed.contains("set-cookie"));
        assertEquals("session=example", response.header("set-cookie"));
    }

    @Test
    public void preservesBufferedResponseContentAndTrailers() {
        // 准备带重复元数据、正文及尾部的协议响应。
        ConnectCors cors = this.cors(Map.of());
        Map<String, List<String>> headers = Map.of("x-label", List.of("first", "second"));
        Map<String, List<String>> trailers = Map.of("x-final-status", List.of("done"));
        byte[] body = "payload".getBytes(StandardCharsets.UTF_8);
        HttpResponse original = HttpResponse.newBuffered(202, headers, body, trailers);
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 跨域装饰只增加响应字段，不修改原响应和业务内容。
        HttpResponse response = cors.decorate(request, original);
        assertEquals(202, response.status());
        assertArrayEquals(body, response.body());
        assertEquals(trailers, response.trailers());
        assertEquals(List.of("first", "second"), response.headers().get("x-label"));
        assertNull(original.header("access-control-allow-origin"));
    }

    @Test
    public void preservesTheStreamingBodyOwner() {
        // 流式响应由已有 HTTP 正文对象持有其生命周期。
        ConnectCors cors = this.cors(Map.of());
        HttpBodyStream stream = new HttpBodyStream(64);
        Map<String, List<String>> trailers = Map.of("x-final-status", List.of("done"));
        HttpResponse original = HttpResponse.newStreaming(200, Map.of(), stream, trailers);
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 增加跨域头不会包装、消费或关闭正文。
        HttpResponse response = cors.decorate(request, original);
        assertSame(stream, response.stream());
        assertEquals(trailers, response.trailers());
        assertFalse(stream.completion().isDone());
        stream.finish(Map.of());
    }

    @Test
    public void leavesSameOriginResponsesUntouched() {
        // 没有 Origin 时无需跨域响应装饰。
        ConnectCors cors = this.cors(Map.of());
        HttpResponse original = this.response(Map.of("x-meta", List.of("value")));

        // 原始响应对象直接沿正常链路发送。
        assertSame(original, cors.decorate(this.request("POST", Map.of()), original));
    }

    @Test
    public void neverDecoratesAResponseForADisallowedOrigin() {
        // 未获授权的来源不能通过装饰步骤获得读取许可。
        ConnectCors cors = this.cors(Map.of("corsOrigins", "https://trusted.example"));
        HttpResponse original = this.response(Map.of());
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 前置拦截负责拒绝请求，装饰本身不增加授权字段。
        assertSame(original, cors.decorate(request, original));
    }

    @Test
    public void preservesExistingVaryDimensions() {
        // 上层已经声明缓存按编码和语言区分。
        ConnectCors cors = this.cors(Map.of());
        HttpResponse original = this.response(Map.of("Vary", List.of("Accept-Encoding", "Accept-Language")));
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 跨域响应保留已有维度并增加来源维度。
        HttpResponse response = cors.decorate(request, original);
        Set<String> vary = this.tokens(response.headers().get("vary"));
        assertEquals(Set.of("accept-encoding", "accept-language", "origin"), vary);
        assertEquals(List.of("Accept-Encoding", "Accept-Language"), original.headers().get("vary"));
    }

    @Test
    public void replacesAnExistingOriginHeaderWithoutCaseDuplicates() {
        // 业务响应中的旧来源与当前挂载策略冲突。
        ConnectCors cors = this.cors(Map.of());
        HttpResponse original = this.response(Map.of("Access-Control-Allow-Origin", List.of("https://old.example")));
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 浏览器要求来源字段只有一个值，由挂载配置决定。
        HttpResponse response = cors.decorate(request, original);
        assertEquals(List.of("*"), response.headers().get("access-control-allow-origin"));
    }

    @Test
    public void replacesAnExistingCredentialsHeaderWithoutCaseDuplicates() {
        // 凭证设置来自挂载配置，业务响应不能提供另一个冲突值。
        String origin = "https://browser.example";
        ConnectCors cors = this.cors(Map.of("corsOrigins", origin, "corsCredentials", "true"));
        HttpResponse original = this.response(Map.of("Access-Control-Allow-Credentials", List.of("false")));
        HttpRequest request = this.request("POST", Map.of("origin", List.of(origin)));

        // 最终响应只有配置明确允许的凭证值。
        HttpResponse response = cors.decorate(request, original);
        assertEquals(List.of("true"), response.headers().get("access-control-allow-credentials"));
    }

    @Test
    public void disabledCredentialsRemoveAnExistingPermission() {
        // 非凭证挂载不能沿用上游返回的凭证许可。
        ConnectCors cors = this.cors(Map.of());
        HttpResponse original = this.response(Map.of("Access-Control-Allow-Credentials", List.of("true")));
        HttpRequest request = this.request("POST", Map.of("origin", List.of("https://browser.example")));

        // 最终响应与挂载的 credentials=false 保持一致。
        HttpResponse response = cors.decorate(request, original);
        assertNull(response.header("access-control-allow-credentials"));
    }

    private ConnectCors cors(Map<String, String> options) {
        return new ConnectCors(ProtocolConfig.newConfig("connect", "connect", options));
    }

    private HttpRequest request(String method, Map<String, List<String>> headers) {
        return HttpRequest.newBuffered(method, "/example.Echo/echo", headers, new byte[0], 1, Map.of());
    }

    private HttpRequest preflight(String requestedHeaders) {
        Map<String, List<String>> headers = Map.of( //
                "origin", List.of("https://browser.example"), //
                "access-control-request-method", List.of("POST"), //
                "access-control-request-headers", List.of(requestedHeaders));
        return this.request("OPTIONS", headers);
    }

    private HttpResponse response(Map<String, List<String>> headers) {
        return HttpResponse.newBuffered(200, headers, new byte[0], Map.of());
    }

    private Set<String> tokens(List<String> fields) {
        Set<String> values = new LinkedHashSet<>();
        for (String field : fields) {
            for (String value : field.split(",")) {
                values.add(value.trim().toLowerCase(Locale.ROOT));
            }
        }
        return values;
    }
}
