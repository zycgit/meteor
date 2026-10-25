/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import net.hasor.meteor.MetExtension;

/** 通过已有上下文扩展入口提供预先配置好的 XmlMapper。 */
public class MetXmlJacksonExtension implements MetExtension {
    private final XmlMapper mapper;

    public MetXmlJacksonExtension(XmlMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public <T> T getService(Class<T> type) {
        return type == XmlMapper.class ? type.cast(this.mapper) : null;
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
}
