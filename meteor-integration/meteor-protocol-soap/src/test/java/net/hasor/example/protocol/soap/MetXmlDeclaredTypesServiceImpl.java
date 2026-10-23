/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.protocol.soap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 普通服务实现直接使用泛型中的 DTO，其余容器原样返回。 */
public class MetXmlDeclaredTypesServiceImpl implements MetXmlDeclaredTypesService {
    @Override
    public Map<String, MetXmlDeclaredUser> users(Map<String, MetXmlDeclaredUser> request) {
        Map<String, MetXmlDeclaredUser> result = new LinkedHashMap<>();
        for (Map.Entry<String, MetXmlDeclaredUser> entry : request.entrySet()) {
            MetXmlDeclaredUser source = entry.getValue();
            MetXmlDeclaredUser user = new MetXmlDeclaredUser();
            user.id = source.id + "-server";
            user.name = source.name;
            user.age = source.age;
            result.put(entry.getKey(), user);
        }
        return result;
    }

    @Override
    public Map<String, Object> values(Map<String, Object> request) {
        return request;
    }

    @Override
    public List<Object> list(List<Object> request) {
        return request;
    }

    @Override
    public Set<Object> set(Set<Object> request) {
        return request;
    }

    @Override
    public Map<String, List<Map<String, MetXmlDeclaredUser>>> nested( //
            Map<String, List<Map<String, MetXmlDeclaredUser>>> request) {
        return request;
    }

    @Override
    public Object any(Object request) {
        return request;
    }
}
