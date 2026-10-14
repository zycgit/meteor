/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.domain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.domain.ContextPath;
import net.hasor.meteor.domain.ServiceDomain;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceContextPathsTest {
    @Test
    public void contextSelectionPreservesProtocolAndPathInsertionOrder() {
        // 多协议选择按加入顺序保存，协议名统一为小写，重复的规范化路径只保留一次。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("HTTPB", new ContextPath(List.of("/second/", "/first", "/second"), false));
        service.setBindContextPaths("httpa", new ContextPath(List.of("/other"), false));

        Map<String, ContextPath> paths = service.getBindContextPaths();
        assertEquals(List.of("httpb", "httpa"), new ArrayList<>(paths.keySet()));
        assertEquals(List.of("/second", "/first"), new ArrayList<>(paths.get("httpb").getPaths()));
        assertTrue(service.getBindProtocols().isEmpty());
    }

    @Test
    public void repeatedProtocolSelectionMovesToTheLatestConfigurationPosition() {
        // 协议名和 scheme 可能指向同一个协议，顺序必须反映最后一次配置调用。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/first"), false));
        service.setBindContextPaths("http", new ContextPath(List.of("/second"), false));
        service.setBindContextPaths("httpa", new ContextPath(List.of("/latest"), false));

        Map<String, ContextPath> paths = service.getBindContextPaths();
        assertEquals(List.of("http", "httpa"), new ArrayList<>(paths.keySet()));
        assertEquals(Set.of("/latest"), paths.get("httpa").getPaths());
    }

    @Test
    public void contextSelectionCopiesInputsAndReturnsDeepImmutableSnapshots() {
        // 发布模型复制传入集合，查询得到的协议映射和路径集合均不可修改。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        List<String> selected = new ArrayList<>(List.of("/orders", "/inventory"));
        service.setBindContextPaths("httpa", new ContextPath(selected, false));
        Map<String, ContextPath> snapshot = service.getBindContextPaths();
        selected.clear();

        assertEquals(Set.of("/orders", "/inventory"), snapshot.get("httpa").getPaths());
        ContextPath other = new ContextPath(Set.of("/other"), false);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("httpb", other));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get("httpa").getPaths().add("/other"));

        // 后续替换选择不会修改此前已返回的快照。
        service.setBindContextPaths("HTTPA", new ContextPath(List.of("/later"), false));
        assertEquals(Set.of("/orders", "/inventory"), snapshot.get("httpa").getPaths());
        assertEquals(Set.of("/later"), service.getBindContextPaths().get("httpa").getPaths());
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
        assertEquals(Map.of("httpa", expected), service.getBindContextPaths());
    }

    @Test
    public void emptyContextSelectionIsRejected() {
        // 显式选择至少需要一个路径，空集合不能隐式恢复默认上下文。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        assertThrows(IllegalArgumentException.class, () -> {
            service.setBindContextPaths("httpa", new ContextPath(List.of(), false));
        });
        assertTrue(service.getBindContextPaths().isEmpty());
    }

    @Test
    public void blankContextProtocolIsRejected() {
        // 上下文选择必须明确所属协议。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        ContextPath paths = new ContextPath(List.of("/orders"), false);
        assertThrows(IllegalArgumentException.class, () -> service.setBindContextPaths(" ", paths));
        assertTrue(service.getBindContextPaths().isEmpty());
    }
}
