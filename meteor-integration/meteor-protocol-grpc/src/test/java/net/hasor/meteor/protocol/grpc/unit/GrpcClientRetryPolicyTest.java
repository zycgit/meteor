/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.unit;
import java.util.Map;
import net.hasor.meteor.protocol.grpc.session.GrpcSessionUnitAccess;
import net.hasor.meteor.protocol.grpc.session.GrpcSessionUnitAccess.ClientPolicy;
import net.hasor.meteor.protocol.grpc.session.GrpcSessionUnitAccess.MethodPolicy;
import net.hasor.meteor.protocol.grpc.session.GrpcSessionUnitAccess.RetryThrottle;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcClientRetryPolicyTest {
    @Test
    public void exactMethodOverridesServiceAndCapsAttempts() throws Exception {
        // 同时配置全局、服务和方法三级策略。
        String config = """
                {"methodConfig":[
                  {"name":[{}],"hedgingPolicy":{"maxAttempts":2}},
                  {"name":[{"service":"test.Service"}],"hedgingPolicy":{"maxAttempts":3}},
                  {"name":[{"service":"test.Service","method":"echo"}],"retryPolicy":{
                    "maxAttempts":9,"initialBackoff":"0.000001s","maxBackoff":"1s",
                    "backoffMultiplier":2,"retryableStatusCodes":[14,"aborted"]}}
                ]}
                """;

        // 按方法匹配最具体的策略，并限制最大尝试次数。
        ClientPolicy policy = this.policy(config);
        MethodPolicy method = policy.method("/mount/test.Service/echo");
        assertEquals(5, method.attempts());
        assertEquals(1, method.initialDelay());
        assertTrue(method.retryable(14));
        assertTrue(method.retryable(10));
    }

    @Test
    public void servicePolicyOverridesGlobalDefault() throws Exception {
        // 同时配置全局、服务和方法三级策略。
        String config = """
                {"methodConfig":[
                  {"name":[{}],"hedgingPolicy":{"maxAttempts":2}},
                  {"name":[{"service":"test.Service"}],"hedgingPolicy":{"maxAttempts":3}},
                  {"name":[{"service":"test.Service","method":"echo"}],"retryPolicy":{
                    "maxAttempts":9,"initialBackoff":"0.000001s","maxBackoff":"1s",
                    "backoffMultiplier":2,"retryableStatusCodes":[14,"aborted"]}}
                ]}
                """;

        // 未命中具体方法时，使用所属服务的策略。
        ClientPolicy policy = this.policy(config);
        MethodPolicy method = policy.method("/test.Service/other");
        assertEquals(3, method.attempts());
    }

    @Test
    public void unmatchedServiceUsesGlobalDefault() throws Exception {
        // 同时配置全局、服务和方法三级策略。
        String config = """
                {"methodConfig":[
                  {"name":[{}],"hedgingPolicy":{"maxAttempts":2}},
                  {"name":[{"service":"test.Service"}],"hedgingPolicy":{"maxAttempts":3}},
                  {"name":[{"service":"test.Service","method":"echo"}],"retryPolicy":{
                    "maxAttempts":9,"initialBackoff":"0.000001s","maxBackoff":"1s",
                    "backoffMultiplier":2,"retryableStatusCodes":[14,"aborted"]}}
                ]}
                """;

        // 没有服务级匹配时，回退到全局策略。
        ClientPolicy policy = this.policy(config);
        MethodPolicy method = policy.method("/other.Service/echo");
        assertEquals(2, method.attempts());
    }

    @Test
    public void rejectsOneAttempt() throws Exception {
        // 配置不足两次的对冲尝试次数。
        String config = "{\"methodConfig\":[{\"name\":[{}],\"hedgingPolicy\":{\"maxAttempts\":1}}]}";

        // 加载配置时拒绝无效的尝试次数。
        assertThrows(IllegalArgumentException.class, () -> this.policy(config));
    }

    @Test
    public void rejectsFractionalAttempts() throws Exception {
        // 配置非整数的对冲尝试次数。
        String config = "{\"methodConfig\":[{\"name\":[{}],\"hedgingPolicy\":{\"maxAttempts\":2.5}}]}";

        // 加载配置时拒绝小数次数。
        assertThrows(IllegalArgumentException.class, () -> this.policy(config));
    }

    @Test
    public void rejectsNegativeHedgingDelay() throws Exception {
        // 配置负数的对冲等待时间。
        String config = """
                {"methodConfig":[{"name":[{}],"hedgingPolicy":{
                  "maxAttempts":2,"hedgingDelay":"-1s"
                }}]}
                """;

        // 加载配置时拒绝负延迟。
        assertThrows(IllegalArgumentException.class, () -> this.policy(config));
    }

    @Test
    public void rejectsOkAsANonFatalStatus() throws Exception {
        // 将成功状态放入非致命错误列表。
        String config = """
                {"methodConfig":[{"name":[{}],"hedgingPolicy":{
                  "maxAttempts":2,"nonFatalStatusCodes":["OK"]
                }}]}
                """;

        // 加载配置时拒绝用成功状态触发重试。
        assertThrows(IllegalArgumentException.class, () -> this.policy(config));
    }

    @Test
    public void rejectsCombinedRetryAndHedgingPolicies() throws Exception {
        // 在同一方法上同时配置重试和对冲。
        String config = "{\"methodConfig\":[{\"name\":[{}],\"retryPolicy\":{},\"hedgingPolicy\":{}}]}";

        // 加载配置时拒绝互斥策略。
        assertThrows(IllegalArgumentException.class, () -> this.policy(config));
    }

    @Test
    public void rejectsNonObjectServiceConfig() throws Exception {
        // 使用数组作为服务配置根节点。
        String config = "[]";

        // 配置入口只接受对象根节点。
        assertThrows(IllegalArgumentException.class, () -> this.policy(config));
    }

    @Test
    public void rejectsMalformedServiceConfig() throws Exception {
        // 提供不完整的 JSON 配置。
        String config = "{";

        // 解析失败直接暴露为非法配置。
        assertThrows(IllegalArgumentException.class, () -> this.policy(config));
    }

    @Test
    public void methodsWithoutRetryPolicyHaveOneAttempt() throws Exception {
        // 只配置重试限流，不配置方法重试策略。
        ClientPolicy policy = this.policy("{\"retryThrottling\":{\"maxTokens\":4,\"tokenRatio\":1}}");

        // 普通方法保持只尝试一次。
        assertEquals(1, policy.method("/test.Service/echo").attempts());
    }

    @Test
    public void retryThrottlingIsSharedOnlyWithinOneTarget() throws Exception {
        // 配置每个目标独立维护的重试令牌。
        ClientPolicy policy = this.policy("{\"retryThrottling\":{\"maxTokens\":4,\"tokenRatio\":1}}");

        // 耗尽第一个目标的额度，确认共享范围不包含第二个目标。
        RetryThrottle first = policy.throttle("grpc://first");
        assertTrue(first.failed());
        assertFalse(first.failed());
        assertFalse(policy.throttle("grpc://first").allowsRetry());
        assertTrue(policy.throttle("grpc://second").allowsRetry());
    }

    @Test
    public void successfulCallsRestoreRetryTokens() throws Exception {
        // 准备一个带重试令牌的目标。
        ClientPolicy policy = this.policy("{\"retryThrottling\":{\"maxTokens\":4,\"tokenRatio\":1}}");
        RetryThrottle throttle = policy.throttle("grpc://first");

        // 失败耗尽额度后，成功调用补回令牌并恢复重试。
        throttle.failed();
        throttle.failed();
        throttle.succeeded();
        assertTrue(throttle.allowsRetry());
    }

    @Test
    public void targetThrottleCacheEvictsTheLeastRecentlyUsedEntry() throws Exception {
        // 将动态目标缓存限制为两个，并为每个目标启用独立的重试令牌。
        String json = "{\"retryThrottling\":{\"maxTokens\":4,\"tokenRatio\":1}}";
        Map<String, String> options = Map.of(//
                "streamBody", "true", "serviceConfig", json, "retryThrottleCacheSize", "2");
        ClientPolicy policy = new ClientPolicy(GrpcSessionUnitAccess.config(options));
        RetryThrottle first = policy.throttle("grpc://first");
        RetryThrottle second = policy.throttle("grpc://second");

        // 再次访问第一个目标后加入第三个目标，最久未使用的第二个目标必须被替换。
        assertTrue(first.sameAs(policy.throttle("grpc://first")));
        policy.throttle("grpc://third");
        assertFalse(second.sameAs(policy.throttle("grpc://second")));
    }

    private ClientPolicy policy(String json) {
        Map<String, String> options = Map.of("streamBody", "true", "serviceConfig", json);
        return new ClientPolicy(GrpcSessionUnitAccess.config(options));
    }
}
