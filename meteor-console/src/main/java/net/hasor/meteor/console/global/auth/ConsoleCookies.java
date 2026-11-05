/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global.auth;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.hasor.cobble.StringUtils;
import net.hasor.core.Inject;
import net.hasor.meteor.console.global.MeteorConfig;

/** 登录 Cookie 的 HTTP 编解码。 */
public final class ConsoleCookies {
    private static final String       NAME = "meteor_session";
    @Inject
    private              MeteorConfig config;

    public String token(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (StringUtils.equals(NAME, cookie.getName())) {
                    String value = cookie.getValue();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        return value.substring(1, value.length() - 1);
                    }
                    return value;
                }
            }
        }
        return "";
    }

    public void set(HttpServletResponse response, String token) {
        long age = token.isEmpty() ? 0 : SessionService.TTL / 1000;
        String cookie = NAME + "=" + token + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + age;
        if (this.config.isSecureCookie()) {
            cookie += "; Secure";
        }
        response.addHeader("Set-Cookie", cookie);
    }
}
