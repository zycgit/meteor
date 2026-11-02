/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.client.support;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.MetFilter;
import net.hasor.meteor.MetFilterChain;
import net.hasor.meteor.MetRequest;
import net.hasor.meteor.MetResponse;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.registry.RegistryTokenProvider;

/** 目标实例的管理 RPC 逐次鉴权；未配置凭据时默认拒绝。 */
final class ManagementAuthentication implements MetFilter {
    private final RegistryTokenProvider tokens;

    ManagementAuthentication(RegistryTokenProvider tokens) {
        this.tokens = tokens;
    }

    @Override
    public void doFilter(MetRequest request, MetResponse response, MetFilterChain chain) throws Throwable {
        // 绑定过滤器也进入出站链路；目标凭据由接收请求的实例校验。
        if (request.isLocal()) {
            chain.doFilter(request, response);
            return;
        }
        String expected;
        try {
            expected = this.tokens == null ? null : this.tokens.getToken();
        } catch (RuntimeException unavailable) {
            response.sendStatus(ProtocolStatus.Unauthorized, "Instance management authentication unavailable");
            return;
        }
        Object[] arguments = request.getParameterObject();
        String supplied = null;
        if (arguments != null && arguments.length > 0 && arguments[0] instanceof String token) {
            supplied = token;
        }
        if (StringUtils.isBlank(expected) || StringUtils.isBlank(supplied)) {
            response.sendStatus(ProtocolStatus.Unauthorized, "Instance management authentication failed");
            return;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] suppliedBytes = supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, suppliedBytes)) {
            response.sendStatus(ProtocolStatus.Unauthorized, "Instance management authentication failed");
            return;
        }
        chain.doFilter(request, response);
    }
}
