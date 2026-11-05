/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.console.global.http;
import java.io.IOException;
import java.io.Writer;
import net.hasor.meteor.console.utils.JsonUtils;
import net.hasor.web.Invoker;
import net.hasor.web.render.RenderEngine;
import net.hasor.web.render.RenderInvoker;

/** 正常 API 响应与其他控制台 JSON 读写使用相同配置。 */
public final class ConsoleJsonRender implements RenderEngine {
    @Override
    public String defaultContentType() {
        return "application/json";
    }

    @Override
    public void process(RenderInvoker invoker, Writer writer) throws IOException {
        writer.write(JsonUtils.writeValueAsString(invoker.get(Invoker.RETURN_DATA_KEY)));
    }
}