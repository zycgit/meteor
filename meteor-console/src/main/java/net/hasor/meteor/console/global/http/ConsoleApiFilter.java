/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global.http;
import java.net.URI;
import javax.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.databind.JsonNode;
import net.hasor.cobble.StringUtils;
import net.hasor.meteor.console.global.auth.ConsoleCookies;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.global.error.ConsoleExceptionHandler;
import net.hasor.meteor.console.global.error.ConsoleHttpException;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.web.Invoker;
import net.hasor.web.InvokerChain;
import net.hasor.web.InvokerFilter;

/** API 的认证、同源校验、正文限制和统一异常边界。 */
public final class ConsoleApiFilter implements InvokerFilter {
    public static final  String                  USERNAME   = ConsoleApiFilter.class.getName() + ".username";
    private static final int                     MAX_BODY   = 1024 * 1024;
    private final        SessionService          sessions;
    private final        ConsoleCookies          cookies;
    private final        ConsoleExceptionHandler exceptions = new ConsoleExceptionHandler();

    public ConsoleApiFilter(SessionService sessions, ConsoleCookies cookies) {
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @Override
    public Object doInvoke(Invoker invoker, InvokerChain chain) throws Throwable {
        invoker.getHttpResponse().setHeader("Cache-Control", "no-store");
        invoker.getHttpResponse().setHeader("X-Content-Type-Options", "nosniff");
        try {
            HttpServletRequest request = invoker.getHttpRequest();
            String method = request.getMethod();
            boolean reading = StringUtils.equals("GET", method);
            if (!reading) {
                this.requireSameOrigin(request);
            }

            boolean login = StringUtils.equals("POST", method) && StringUtils.equals("/api/auth/login", request.getRequestURI());
            if (!login) {
                String username = this.sessions.authenticate(this.cookies.token(request));
                request.setAttribute(USERNAME, username);
            }

            JsonNode body = JsonUtils.createObjectNode();
            if (!reading) {
                byte[] bytes = request.getInputStream().readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) {
                    throw new ConsoleHttpException(413, "请求内容超过 1 MiB");
                }
                if (bytes.length > 0) {
                    String type = request.getContentType();
                    if (type == null || !type.startsWith("application/json")) {
                        throw new ConsoleHttpException(415, "请求内容必须为 application/json");
                    }
                    body = JsonUtils.readTree(bytes);
                    if (body == null || !body.isObject()) {
                        throw new IllegalArgumentException("请求正文必须是 JSON 对象");
                    }
                }
            }
            String query = request.getQueryString();
            URI uri = URI.create(request.getRequestURI() + (query == null ? "" : "?" + query));
            String managementToken = request.getHeader("X-Meteor-Management-Token");
            request.setAttribute(ConsoleRequest.class.getName(), new ConsoleRequest(uri, body, managementToken));
            return chain.doNext(invoker);
        } catch (Exception failure) {
            this.exceptions.handle(invoker, failure);
            return null;
        }
    }

    private void requireSameOrigin(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        String site = request.getHeader("Sec-Fetch-Site");
        String host = request.getHeader("Host");
        boolean foreign = origin != null && !StringUtils.equals(URI.create(origin).getAuthority(), host);
        if (foreign || StringUtils.equals("cross-site", site)) {
            throw new ConsoleHttpException(403, "不允许跨站操作");
        }
    }
}
