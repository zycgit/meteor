/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceRouteMountsTest {
    @Test
    public void contextSelectionPreservesProtocolAndPathInsertionOrder() {
        // 多协议选择按加入顺序保存，协议名统一为小写，重复的规范化路径只保留一次。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("HTTPB", new ContextPath(List.of("/second/", "/first", "/second"), false));
        service.setBindContextPaths("httpa", new ContextPath(List.of("/other"), false));

        Map<String, ProtocolMeta> protocols = service.getProtocolMetas();
        Set<String> selectedPaths = protocols.get("httpb").getContextPath().getPaths();
        assertEquals(List.of("httpb", "httpa"), new ArrayList<>(protocols.keySet()));
        assertEquals(List.of("/second", "/first"), new ArrayList<>(selectedPaths));
        assertTrue(service.getBindProtocols().isEmpty());
    }

    @Test
    public void repeatedProtocolSelectionMovesToTheLatestConfigurationPosition() {
        // 同一协议重复声明时，顺序反映最后一次配置调用。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/first"), false));
        service.setBindContextPaths("http", new ContextPath(List.of("/second"), false));
        service.setBindContextPaths("httpa", new ContextPath(List.of("/latest"), false));

        Map<String, ProtocolMeta> protocols = service.getProtocolMetas();
        assertEquals(List.of("http", "httpa"), new ArrayList<>(protocols.keySet()));
        assertEquals(Set.of("/latest"), protocols.get("httpa").getContextPath().getPaths());
    }

    @Test
    public void contextSelectionCopiesInputsAndReturnsDeepImmutableSnapshots() {
        // 发布模型复制传入集合，查询得到的协议映射和路径集合均不可修改。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        List<String> selected = new ArrayList<>(List.of("/orders", "/inventory"));
        service.setBindContextPaths("httpa", new ContextPath(selected, false));
        Map<String, ProtocolMeta> snapshot = service.getProtocolMetas();
        selected.clear();

        Set<String> originalPaths = snapshot.get("httpa").getContextPath().getPaths();
        assertEquals(Set.of("/orders", "/inventory"), originalPaths);
        ContextPath otherPaths = new ContextPath(Set.of("/other"), false);
        ProtocolMeta other = new ProtocolMeta(service, otherPaths, Map.of());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("httpb", other));
        assertThrows(UnsupportedOperationException.class, () -> originalPaths.add("/other"));

        // 后续替换选择不会修改此前已返回的快照。
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/later"), false));
        assertEquals(Set.of("/orders", "/inventory"), snapshot.get("httpa").getContextPath().getPaths());
        assertEquals(Set.of("/later"), service.getProtocolMeta("httpa").getContextPath().getPaths());
    }

    @Test
    public void invalidContextDoesNotPartiallyReplacePreviousSelection() {
        // 一次替换中含有非法路径时，保留该协议原来的完整选择。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("httpa", new ContextPath(List.of("/orders"), false));

        assertThrows(IllegalArgumentException.class, () -> {
            service.setBindContextPaths("httpa", new ContextPath(List.of("/inventory", "/bad?query"), false));
        });
        ContextPath expected = new ContextPath(Set.of("/orders"), false);
        assertEquals(Set.of("httpa"), service.getProtocolMetas().keySet());
        assertEquals(expected, service.getProtocolMeta("httpa").getContextPath());
    }

    @Test
    public void emptyContextSelectionIsRejected() {
        // 显式选择至少需要一个路径，空集合不能隐式恢复默认上下文。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        assertThrows(IllegalArgumentException.class, () -> {
            service.setBindContextPaths("httpa", new ContextPath(List.of(), false));
        });
        assertTrue(service.getProtocolMetas().isEmpty());
    }

    @Test
    public void blankContextProtocolIsRejected() {
        // 上下文选择必须明确所属协议。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        ContextPath paths = new ContextPath(List.of("/orders"), false);
        assertThrows(IllegalArgumentException.class, () -> service.setBindContextPaths(" ", paths));
        assertTrue(service.getProtocolMetas().isEmpty());
    }
}
