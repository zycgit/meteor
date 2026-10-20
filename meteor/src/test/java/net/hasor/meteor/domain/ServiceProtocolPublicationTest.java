/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.domain;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.hasor.meteor.MetBindInfo;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceProtocolPublicationTest {
    @Test
    public void undeclaredProtocolHasNoPublicationGroup() {
        // 没有声明路径，也没有接受结果时，不凭空创建协议分组。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        assertNull(service.getProtocolMeta("grpc"));
        assertTrue(service.getProtocolMetas().isEmpty());
    }

    @Test
    public void protocolLookupIgnoresNameCase() {
        // 协议名统一查找，端点名保持连接器自己的标识。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        Object definition = new Object();
        ProtocolBinding endpoint = new ProtocolBinding("grpc", Map.of(), null, definition);
        ProtocolMeta publication = new ProtocolMeta(service, null, Map.of("public-http", endpoint));

        service.replaceProtocolPublication(Map.of("GRPC", publication));

        ProtocolMeta selected = service.getProtocolMeta("GrPc");
        assertEquals(Set.of("grpc"), service.getProtocolMetas().keySet());
        assertSame(selected, service.getProtocolMeta("grpc"));
        assertSame(service, selected.getService());
        assertSame(endpoint, selected.getEndpoint("public-http"));
        assertSame(definition, selected.getEndpoint("public-http").getDefinition());
    }

    @Test
    public void publicationCopiesTheProtocolIndex() {
        // 服务整体替换协议索引，调用方仍可复用组装时使用的索引。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolBinding endpoint = new ProtocolBinding("grpc", Map.of(), null, new Object());
        ProtocolMeta publication = new ProtocolMeta(service, null, Map.of("public-http", endpoint));
        Map<String, ProtocolMeta> protocols = new LinkedHashMap<>();
        protocols.put("grpc", publication);
        service.replaceProtocolPublication(protocols);

        // 清空组装索引不会撤销已登记的协议分组。
        protocols.clear();

        ProtocolMeta selected = service.getProtocolMeta("grpc");
        assertEquals(Set.of("public-http"), selected.getEndpoints().keySet());
        assertSame(endpoint, selected.getEndpoint("public-http"));
    }

    @Test
    public void changingEffectivePathsLeavesTheOriginalEndpointUnchanged() {
        // 同一协议定义用于不同端点时，有效路径的补齐不能串改原端点。
        ContextPath originalPaths = new ContextPath(Set.of("/public"), false);
        Object definition = new Object();
        ProtocolBinding original = new ProtocolBinding("grpc", Map.of("timeout", "3000"), originalPaths, definition);
        ContextPath effectivePaths = new ContextPath(Set.of("/internal"), true);

        ProtocolBinding selected = original.withContextPaths(effectivePaths);

        assertEquals(originalPaths, original.getContextPath());
        assertEquals(effectivePaths, selected.getContextPath());
        assertEquals(original.getScheme(), selected.getScheme());
        assertEquals(original.getOptions(), selected.getOptions());
        assertSame(definition, selected.getDefinition());
    }

    @Test
    public void publishedProtocolIndexCannotBeModifiedThroughService() {
        // 协议索引由服务整体替换，查询方不能直接增删其中的协议分组。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolBinding endpoint = new ProtocolBinding("rsf", Map.of(), null, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, Map.of("main-tcp", endpoint));
        service.replaceProtocolPublication(Map.of("rsf", publication));

        Map<String, ProtocolMeta> protocols = service.getProtocolMetas();
        assertThrows(UnsupportedOperationException.class, () -> protocols.put("other", publication));
        assertEquals(Set.of("rsf"), service.getProtocolMetas().keySet());
        assertEquals(Set.of("main-tcp"), service.getProtocolMeta("rsf").getEndpoints().keySet());
    }

    @Test
    public void replacingPublicationDropsUndeclaredProtocolsOutsideTheNewSnapshot() {
        // 首次接受多个协议，后续完整快照不合并旧的接受结果。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolBinding original = new ProtocolBinding("grpc", Map.of(), null, new Object());
        ProtocolBinding rsf = new ProtocolBinding("rsf", Map.of(), null, null);
        ProtocolMeta grpc = new ProtocolMeta(service, null, Map.of("public-http", original));
        ProtocolMeta rsfPublication = new ProtocolMeta(service, null, Map.of("main-tcp", rsf));
        service.replaceProtocolPublication(Map.of("grpc", grpc, "rsf", rsfPublication));
        Map<String, ProtocolBinding> previousEndpoints = service.getProtocolMeta("grpc").getEndpoints();

        // 同名协议使用新的端点，其它未声明协议被移除，旧查询快照仍稳定。
        ProtocolBinding replacement = new ProtocolBinding("grpcs", Map.of(), null, new Object());
        ProtocolMeta refreshed = new ProtocolMeta(service, null, Map.of("internal-http", replacement));
        service.replaceProtocolPublication(Map.of("grpc", refreshed));

        assertSame(replacement, service.getProtocolMeta("grpc").getEndpoint("internal-http"));
        assertEquals(Set.of("internal-http"), service.getProtocolMeta("grpc").getEndpoints().keySet());
        assertNull(service.getProtocolMeta("rsf"));
        assertEquals(Map.of("public-http", original), previousEndpoints);
    }

    @Test
    public void emptyPublicationClearsAcceptedProtocolsWithoutDeclarations() {
        // 没有原始路径声明时，清理接受结果后恢复为未准备状态。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolBinding endpoint = new ProtocolBinding("rsf", Map.of(), null, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, Map.of("main-tcp", endpoint));
        service.replaceProtocolPublication(Map.of("rsf", publication));

        service.replaceProtocolPublication(Map.of());

        assertNull(service.getProtocolMeta("rsf"));
        assertTrue(service.getProtocolMetas().isEmpty());
    }

    @Test
    public void clearingPublicationPreservesOriginalContextDeclarations() {
        // 服务声明和端点实际接受路径分别保存在分组与端点，清结果不删除原声明。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ContextPath declaredPaths = new ContextPath(Set.of("/orders", "/internal/orders"), false);
        service.setBindContextPaths("grpc", declaredPaths);
        ContextPath effectivePaths = new ContextPath(Set.of("/internal/orders"), false);
        ProtocolBinding endpoint = new ProtocolBinding("grpc", Map.of(), effectivePaths, new Object());
        ProtocolMeta publication = new ProtocolMeta(service, declaredPaths, Map.of("internal-http", endpoint));
        service.replaceProtocolPublication(Map.of("grpc", publication));
        ProtocolMeta previousPublication = service.getProtocolMeta("grpc");

        // 关闭或提供者拒绝后的空接受结果，只清端点信息。
        service.replaceProtocolPublication(Map.of());

        ProtocolMeta declared = service.getProtocolMeta("grpc");
        assertEquals(declaredPaths, declared.getContextPath());
        assertTrue(declared.getEndpoints().isEmpty());
        assertSame(service, declared.getService());
        assertEquals(effectivePaths, previousPublication.getEndpoint("internal-http").getContextPath());
    }

    @Test
    public void endpointPublicationRetainsAnExistingContextDeclaration() {
        // 提供者只补充端点结果时，服务原本的路径声明仍保存在协议分组中。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ContextPath declaredPaths = new ContextPath(Set.of("/orders"), true);
        service.setBindContextPaths("grpc", declaredPaths);
        ProtocolBinding endpoint = new ProtocolBinding("grpc", Map.of(), declaredPaths, new Object());
        ProtocolMeta prepared = new ProtocolMeta(service, null, Map.of("public-http", endpoint));

        service.replaceProtocolPublication(Map.of("grpc", prepared));

        ProtocolMeta selected = service.getProtocolMeta("grpc");
        assertEquals(declaredPaths, selected.getContextPath());
        assertSame(endpoint, selected.getEndpoint("public-http"));
        assertNull(prepared.getContextPath());
    }

    @Test
    public void acceptedEndpointDoesNotRequireAnAdditionalDefinition() {
        // 简单协议的端点没有额外定义对象，端点存在仍代表已接受服务。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolBinding endpoint = new ProtocolBinding("rsf", Map.of(), null, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, Map.of("main-tcp", endpoint));

        service.replaceProtocolPublication(Map.of("rsf", publication));

        ProtocolBinding selected = service.getProtocolMeta("rsf").getEndpoint("main-tcp");
        assertSame(endpoint, selected);
        assertNull(selected.getDefinition());
        assertNull(service.getProtocolMeta("soap"));
    }

    @Test
    public void nestedBindingWrappersResolveToTheSameInternalPublicationState() {
        // 对外绑定可经过多层包装，协议发布状态仍只由底层服务持有。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        MetBindInfo<Runnable> binding = new MetBindInfoWrap<>(new MetBindInfoWrap<>(service));
        Object definition = new Object();
        ProtocolBinding endpoint = new ProtocolBinding("grpc", Map.of(), null, definition);
        ProtocolMeta publication = new ProtocolMeta(service, null, Map.of("public-http", endpoint));

        // 内部协作方解包得到同一状态来源，无需在公共绑定接口转发协议状态。
        ServiceDomain<?> internal = MetBindInfoWrap.unwrap(binding);
        internal.replaceProtocolPublication(Map.of("grpc", publication));

        ProtocolMeta selected = internal.getProtocolMeta("GRPC");
        assertSame(service, internal);
        assertSame(service, MetBindInfoWrap.unwrap(service));
        assertSame(selected, service.getProtocolMeta("grpc"));
        assertSame(definition, selected.getEndpoint("public-http").getDefinition());
        assertEquals(internal.getProtocolMetas(), service.getProtocolMetas());
    }

    @Test
    public void publicBindingContractDoesNotExposeProtocolPublicationState() {
        // 仅检查公共接口声明，协议状态的查询和写入留在内部 ServiceDomain。
        assertThrows(NoSuchMethodException.class, () -> MetBindInfo.class.getMethod("getProtocolMetas"));
        assertThrows(NoSuchMethodException.class, () -> MetBindInfo.class.getMethod("getProtocolMeta", String.class));
        assertThrows(NoSuchMethodException.class, () -> {
            MetBindInfo.class.getMethod("replaceProtocolPublication", Map.class);
        });
    }
}
