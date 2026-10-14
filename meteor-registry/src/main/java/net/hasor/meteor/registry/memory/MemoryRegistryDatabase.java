/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.memory;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.*;

/** 同一配置共享的业务目录；嵌套 Map 对应直接所属集合，不维护实体关系索引。 */
final class MemoryRegistryDatabase {
    final NavigableMap<String, EntApplicationCatalogData>                                applications         = new TreeMap<>();
    final NavigableMap<String, EntInstanceCatalogData>                                   instances            = new TreeMap<>();
    final Map<String, NavigableMap<String, EntInstanceCatalogData>>                      applicationInstances = new HashMap<>();
    final Map<String, EntInstanceRuntimeData>                                            runtime              = new HashMap<>();
    final Map<String, String>                                                            sessions             = new HashMap<>();
    final NavigableMap<String, EntServiceCatalogData>                                    services             = new TreeMap<>();
    final Map<String, NavigableMap<String, EntServiceRuntimeData>>                       instanceServices     = new HashMap<>();
    final Map<String, Map<MetServiceType, NavigableMap<String, RefServiceRelationData>>> members              = new HashMap<>();
    final Map<String, ServiceSummary>                                                    summaries            = new HashMap<>();
    final Map<String, EntityRecord<?>>                                                   configurations       = new HashMap<>();
    final Map<String, Long>                                                              changes              = new HashMap<>();

    public void changed(String scope) {
        this.changes.merge(scope, 1L, Long::sum);
    }
}
