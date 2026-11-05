/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.web;
import java.util.Map;
import net.hasor.core.Inject;
import net.hasor.meteor.console.global.MeteorConfig;
import net.hasor.meteor.console.global.auth.ConsoleCookies;
import net.hasor.meteor.console.global.auth.SessionService;
import net.hasor.meteor.console.global.http.ConsoleApiFilter;
import net.hasor.meteor.console.global.http.ConsoleRequest;
import net.hasor.web.Invoker;
import net.hasor.web.annotation.Get;
import net.hasor.web.annotation.MappingTo;
import net.hasor.web.annotation.Post;

/** 控制台登录、会话查询和退出。 */
@MappingTo("/api")
public class AuthController {
    @Inject
    private SessionService sessions;
    @Inject
    private ConsoleCookies cookies;
    @Inject
    private MeteorConfig   config;

    @Post
    @MappingTo("/auth/login")
    public Object login(Invoker invoker) {
        if (!this.sessions.isSecurityEnabled()) {
            return this.sessionInfo("");
        }

        ConsoleRequest request = ConsoleRequest.from(invoker);
        String username = request.text("username");
        String previous = this.cookies.token(invoker.getHttpRequest());
        String token = this.sessions.login(previous, username, request.text("password"));
        this.cookies.set(invoker.getHttpResponse(), token);
        return this.sessionInfo(username);
    }

    @Get
    @MappingTo("/auth/session")
    public Object session(Invoker invoker) {
        String username = (String) invoker.getHttpRequest().getAttribute(ConsoleApiFilter.USERNAME);
        return this.sessionInfo(username);
    }

    @Post
    @MappingTo("/auth/logout")
    public Object logout(Invoker invoker) {
        this.sessions.logout(this.cookies.token(invoker.getHttpRequest()));
        this.cookies.set(invoker.getHttpResponse(), "");
        return Map.of("success", true);
    }

    private Object sessionInfo(String username) {
        return Map.of(//
                "userName", username,//
                "localUnit", this.config.getUnit(),//
                "securityEnabled", this.sessions.isSecurityEnabled());
    }
}
