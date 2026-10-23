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
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.domain.warp.MetBindInfoWrap;
import org.junit.Test;
import static org.junit.Assert.*;

public class ServiceProtocolPublicationTest {
    @Test
    public void namedResourceBelongsOnlyToItsOwnProtocol() {
        // 两个协议分别声明资源，未声明资源的协议保持为空。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        Object proto = new Object();
        Object wsdl = new Object();
        service.setBindResource("GRPC", proto);
        service.setBindResource("soap", wsdl);

        // 选择只发生在所属协议内，不能误用另一个协议的声明。
        assertSame(proto, service.getProtocolMeta("grpc").getBindResource());
        assertSame(wsdl, service.getProtocolMeta("soap").getBindResource());
        ProtocolMeta unbound = new ProtocolMeta(service, null, null, Map.of());
        assertNull(unbound.getBindResource());
    }

    @Test
    public void clearingResourceDoesNotAffectAnotherProtocol() {
        // 同一服务分别绑定两个协议的契约资源。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        service.setBindResource("grpc", new Object());
        Object wsdl = new Object();
        service.setBindResource("soap", wsdl);

        // 清空目标协议时，其他协议的资源保持原样。
        service.setBindResource("GRPC", null);

        assertNull(service.getProtocolMeta("grpc"));
        assertSame(wsdl, service.getProtocolMeta("soap").getBindResource());
    }

    @Test
    public void clearingAnUnboundResourceDoesNotCreateAPublicationGroup() {
        // 未声明资源的协议允许执行清空，不产生空分组。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);

        service.setBindResource("grpc", null);

        assertTrue(service.getProtocolMetas().isEmpty());
    }

    @Test
    public void clearingResourcePreservesTheContextDeclaration() {
        // 资源和路径属于同一协议，但可以独立修改。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ContextPath paths = new ContextPath(Set.of("/orders"), true);
        service.setBindContextPaths("soap", paths);
        service.setBindResource("soap", new Object());

        service.setBindResource("soap", null);

        ProtocolMeta declaration = service.getProtocolMeta("soap");
        assertSame(paths, declaration.getContextPath());
        assertNull(declaration.getBindResource());
    }

    @Test
    public void clearingResourcePreservesAlreadyAcceptedEndpoints() {
        // 已准备的端点结果与原始资源声明分别保存。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        Object resource = new Object();
        ProtocolBinding endpoint = new ProtocolBinding(null, new Object(), null);
        ProtocolMeta publication = new ProtocolMeta(service, null, resource, Map.of("http", endpoint));
        service.replaceProtocolPublication(Map.of("grpc", publication));

        // 只清空声明，已持有的端点结果继续由原生命周期管理。
        service.setBindResource("grpc", null);

        ProtocolMeta declaration = service.getProtocolMeta("grpc");
        assertNull(declaration.getBindResource());
        assertSame(endpoint, declaration.getEndpoint("http"));
    }

    @Test
    public void clearingPublicationKeepsNamedResourceWithoutContextDeclaration() {
        // 只有资源声明的协议也需要在关闭或撤销后保留原始输入。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        Object resource = new Object();
        service.setBindResource("grpc", resource);
        ProtocolBinding binding = new ProtocolBinding(null, new Object(), null);
        ProtocolMeta publication = new ProtocolMeta(service, null, resource, Map.of("http", binding));
        service.replaceProtocolPublication(Map.of("grpc", publication));

        // 清掉的是准备结果，而不是用户的资源声明。
        service.replaceProtocolPublication(Map.of());

        ProtocolMeta declaration = service.getProtocolMeta("grpc");
        assertSame(resource, declaration.getBindResource());
        assertTrue(declaration.getEndpoints().isEmpty());
    }

    @Test
    public void contextDeclarationPreservesTheNamedResource() {
        // 路径和资源是同一协议的独立声明，改变路径不能丢掉资源。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        Object resource = new Object();
        service.setBindResource("soap", resource);
        ContextPath paths = new ContextPath(Set.of("/orders"), true);

        service.setBindContextPaths("SOAP", paths);

        ProtocolMeta declaration = service.getProtocolMeta("soap");
        assertEquals(paths, declaration.getContextPath());
        assertSame(resource, declaration.getBindResource());
    }

    @Test
    public void namedResourceDeclarationPreservesTheContext() {
        // 后声明资源也不能覆盖已经选择的上下文路径。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ContextPath paths = new ContextPath(Set.of("/orders"), true);
        service.setBindContextPaths("soap", paths);
        Object resource = new Object();

        service.setBindResource("SOAP", resource);

        ProtocolMeta declaration = service.getProtocolMeta("soap");
        assertEquals(paths, declaration.getContextPath());
        assertSame(resource, declaration.getBindResource());
    }

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
        ProtocolBinding endpoint = new ProtocolBinding(null, definition, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, null, Map.of("public-http", endpoint));

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
        ProtocolBinding endpoint = new ProtocolBinding(null, new Object(), null);
        ProtocolMeta publication = new ProtocolMeta(service, null, null, Map.of("public-http", endpoint));
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
    public void publishedProtocolIndexCannotBeModifiedThroughService() {
        // 协议索引由服务整体替换，查询方不能直接增删其中的协议分组。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolBinding endpoint = new ProtocolBinding(null, null, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, null, Map.of("main-tcp", endpoint));
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
        ProtocolBinding original = new ProtocolBinding(null, new Object(), null);
        ProtocolBinding rsf = new ProtocolBinding(null, null, null);
        ProtocolMeta grpc = new ProtocolMeta(service, null, null, Map.of("public-http", original));
        ProtocolMeta rsfPublication = new ProtocolMeta(service, null, null, Map.of("main-tcp", rsf));
        service.replaceProtocolPublication(Map.of("grpc", grpc, "rsf", rsfPublication));
        Map<String, ProtocolBinding> previousEndpoints = service.getProtocolMeta("grpc").getEndpoints();

        // 同名协议使用新的端点，其它未声明协议被移除，旧查询快照仍稳定。
        ProtocolBinding replacement = new ProtocolBinding(null, new Object(), null);
        ProtocolMeta refreshed = new ProtocolMeta(service, null, null, Map.of("internal-http", replacement));
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
        ProtocolBinding endpoint = new ProtocolBinding(null, null, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, null, Map.of("main-tcp", endpoint));
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
        ProtocolBinding endpoint = new ProtocolBinding(effectivePaths, new Object(), null);
        ProtocolMeta publication = new ProtocolMeta(service, declaredPaths, null, Map.of("internal-http", endpoint));
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
    public void completePublicationIsInstalledWithoutRebuildingItsMetadata() {
        // 服务已有路径和资源声明，本轮准备结果完整保留这两项并携带端点绑定。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ContextPath declared = new ContextPath(Set.of("/orders"), true);
        Object resource = new Object();
        service.setBindContextPaths("grpc", declared);
        service.setBindResource("grpc", resource);
        ProtocolBinding endpoint = new ProtocolBinding(declared, new Object(), null);
        ProtocolMeta prepared = new ProtocolMeta(service, declared, resource, Map.of("http", endpoint));

        // 提交直接安装同一份完整元信息，不再重新合并或创建包装对象。
        service.replaceProtocolPublication(Map.of("grpc", prepared));
        assertSame(prepared, service.getProtocolMeta("grpc"));
        assertSame(endpoint, prepared.getEndpoint("http"));
        assertSame(declared, prepared.getContextPath());
        assertSame(resource, prepared.getBindResource());
    }

    @Test
    public void acceptedEndpointDoesNotRequireAnAdditionalDefinition() {
        // 简单协议的端点没有额外定义对象，端点存在仍代表已接受服务。
        ServiceDomain<Runnable> service = new ServiceDomain<>(Runnable.class);
        ProtocolBinding endpoint = new ProtocolBinding(null, null, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, null, Map.of("main-tcp", endpoint));

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
        ProtocolBinding endpoint = new ProtocolBinding(null, definition, null);
        ProtocolMeta publication = new ProtocolMeta(service, null, null, Map.of("public-http", endpoint));

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
