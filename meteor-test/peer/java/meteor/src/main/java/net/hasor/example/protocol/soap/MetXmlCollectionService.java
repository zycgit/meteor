/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;

/** 集合由带明确 XML 映射的普通 DTO 承载。 */
public interface MetXmlCollectionService {
    MetXmlCollections echo(MetXmlCollections value);
}
