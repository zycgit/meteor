/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.support;
import net.hasor.meteor.MetContext;
import net.hasor.meteor.MetExtension;
import net.hasor.meteor.MetExtensionFactory;
import net.hasor.meteor.protocol.soap.SoapHeaderHandler;

/** 通过正式上下文扩展入口提供场景需要的 Header 处理器。 */
public final class SoapHeadersExtension implements MetExtensionFactory {
    private final SoapHeaderHandler handler;

    public SoapHeadersExtension(SoapHeaderHandler handler) {
        this.handler = handler;
    }

    @Override
    public MetExtension create(MetContext context) {
        return new MetExtension() {
            @Override
            public <T> T getService(Class<T> type) {
                SoapHeaderHandler target = SoapHeadersExtension.this.handler;
                return type.isInstance(target) ? type.cast(target) : null;
            }

            @Override
            public void beforeStart() {
            }

            @Override
            public void afterStart() {
            }

            @Override
            public void close() {
            }
        };
    }
}
