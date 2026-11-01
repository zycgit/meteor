/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.admin.support;
import java.util.List;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.admin.*;
import net.hasor.meteor.registry.shared.storage.domain.EntServiceRuntimeData;
import net.hasor.meteor.registry.shared.storage.domain.RefProtocolMountData;
import net.hasor.meteor.registry.shared.storage.domain.ServiceSummary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 管理摘要和直连目录在已有 DTO 上完成映射与筛选。 */
public class DirectoryMappingTest {
    @Test
    public void runtimeSummaryKeepsServiceCoordinatesAndMemberRole() {
        // 运行登记仍持有本实例声明的坐标，不能改从其他实例推断。
        EntServiceRuntimeData registration = registration(MetServiceType.Provider);

        // 摘要只返回坐标及当前成员的角色、数量。
        MetServiceInfo summary = DirectoryMapping.service(registration);
        assertEquals("orders", summary.getServiceId());
        assertEquals("shop", summary.getGroup());
        assertEquals("OrderService", summary.getName());
        assertEquals("v2", summary.getVersion());
        assertEquals(MetServiceType.Provider, summary.getRole());
        assertEquals(1, summary.getProviderCount());
        assertEquals(0, summary.getConsumerCount());
    }

    @Test
    public void runtimeSummaryDoesNotReadTheFullDescription() {
        // 完整描述可能包含很大的契约图，摘要查询不需要读取它。
        EntServiceRuntimeData registration = new EntServiceRuntimeData() {
            @Override
            public ObjectNode getDescription() {
                throw new AssertionError("A summary must not read the full service description");
            }
        };
        registration.setServiceId("orders");
        registration.setRole(MetServiceType.Provider);

        // 只读取坐标、角色和协议端点即可构造摘要。
        MetServiceInfo summary = DirectoryMapping.service(registration);
        assertEquals("orders", summary.getServiceId());
        assertEquals(1, summary.getProviderCount());
    }

    @Test
    public void providerSummaryDeduplicatesAndSortsAcceptedEndpointProtocols() {
        // 多个端点可以挂载相同协议，空协议名不构成协议能力。
        EntServiceRuntimeData registration = registration(MetServiceType.Provider);
        RefProtocolMountData[] endpoints = new RefProtocolMountData[] { // 端点登记顺序不决定摘要顺序。
                storedEndpoint("soap"), storedEndpoint("rsf"), storedEndpoint("soap"), storedEndpoint(" ") };
        registration.setEndpoints(endpoints);

        // 摘要协议集合来自已接受的服务端点，按协议名排序并去重。
        MetServiceInfo summary = DirectoryMapping.service(registration);
        assertArrayEquals(new String[] { "rsf", "soap" }, summary.getProtocols());
    }

    @Test
    public void consumerSummaryUsesOnlyProtocolsFromItsAddressBook() {
        // 订阅端可以配置其他协议，摘要只表示当前服务地址本的实际协议。
        EntServiceRuntimeData registration = registration(MetServiceType.Consumer);
        registration.setEndpoints(new RefProtocolMountData[] { storedEndpoint("soap") });
        registration.setUsedProtocols(new String[] { "rsf", "grpc", "rsf" });

        // 配置端点不能混入消费者的协议摘要。
        MetServiceInfo summary = DirectoryMapping.service(registration);
        assertEquals(MetServiceType.Consumer, summary.getRole());
        assertEquals(0, summary.getProviderCount());
        assertEquals(1, summary.getConsumerCount());
        assertArrayEquals(new String[] { "grpc", "rsf" }, summary.getProtocols());
    }

    @Test
    public void catalogSummaryKeepsCoordinatesAndAggregatedMemberCounts() {
        // 目录坐标和聚合计数分别来自目录记录与成员摘要。
        EntServiceRuntimeData catalog = registration(MetServiceType.Consumer);
        ServiceSummary members = new ServiceSummary();
        members.setProviderCount(2);
        members.setConsumerCount(3);
        members.setProtocols(new String[] { "grpc", "rsf" });

        // 同时存在发布与订阅成员时，保持现有的提供者角色优先语义。
        MetServiceInfo summary = DirectoryMapping.service(catalog, members);
        assertEquals("orders", summary.getServiceId());
        assertEquals("shop", summary.getGroup());
        assertEquals("OrderService", summary.getName());
        assertEquals("v2", summary.getVersion());
        assertEquals(2, summary.getProviderCount());
        assertEquals(3, summary.getConsumerCount());
        assertEquals(MetServiceType.Provider, summary.getRole());
        assertArrayEquals(new String[] { "grpc", "rsf" }, summary.getProtocols());
    }

    @Test
    public void catalogSummaryDoesNotShareItsProtocolArrayWithStorage() {
        // 聚合结果和 API 返回值分别拥有协议数组。
        EntServiceRuntimeData catalog = registration(MetServiceType.Provider);
        ServiceSummary members = new ServiceSummary();
        members.setProtocols(new String[] { "grpc", "rsf" });
        MetServiceInfo summary = DirectoryMapping.service(catalog, members);

        // 两侧修改不能反向影响另一侧的查询快照。
        members.getProtocols()[0] = "soap";
        summary.getProtocols()[1] = "hprose";
        assertArrayEquals(new String[] { "grpc", "hprose" }, summary.getProtocols());
        assertArrayEquals(new String[] { "soap", "rsf" }, members.getProtocols());
    }

    @Test
    public void runtimeSummaryDoesNotShareConsumerProtocolArray() {
        // 地址本协议是运行登记中的可变数组。
        EntServiceRuntimeData registration = registration(MetServiceType.Consumer);
        registration.setUsedProtocols(new String[] { "grpc", "rsf" });
        MetServiceInfo summary = DirectoryMapping.service(registration);

        // 摘要变化不改变原运行登记，登记更新也不追改已返回的摘要。
        registration.getUsedProtocols()[0] = "soap";
        summary.getProtocols()[1] = "hprose";
        assertArrayEquals(new String[] { "grpc", "hprose" }, summary.getProtocols());
        assertArrayEquals(new String[] { "soap", "rsf" }, registration.getUsedProtocols());
    }

    @Test
    public void protocolSelectorMatchesTheExactName() {
        // 协议选择器按协议名精确匹配，不能使用大小写折叠或前缀匹配。
        MetServiceInfo exact = summary("exact", "rsf");
        MetServiceInfo prefix = summary("prefix", "rsfs");
        MetServiceInfo upperCase = summary("upper", "RSF");
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setProtocol("rsf");

        // 仅完整匹配的摘要留在结果中。
        List<MetServiceInfo> source = List.of(prefix, upperCase, exact);
        MetPage<MetServiceInfo> page = DirectoryMapping.local(source, query, MetServiceInfo::getServiceId);
        assertEquals(List.of(exact), page.getItems());
    }

    @Test
    public void identitySearchDoesNotReadUnrelatedProtocolDetails() {
        // 按身份搜索只依赖身份字段，无需取得协议集合。
        MetServiceInfo service = new MetServiceInfo() {
            @Override
            public String[] getProtocols() {
                throw new AssertionError("An identity search must not read protocol details");
            }
        };
        service.setServiceId("orders");
        MetDirectoryQuery query = search("id", "orders");

        // 不为筛选还原存储对象，也不提前遍历协议端点。
        MetPage<MetServiceInfo> page = DirectoryMapping.local(List.of(service), query, MetServiceInfo::getServiceId);
        assertEquals(List.of(service), page.getItems());
    }

    @Test
    public void consumerMemberProtocolSelectorUsesItsActualUsedProtocols() {
        // 摘要和配置端点含有 RSF，但该消费者地址本当前只使用 gRPC。
        MetServiceMember member = member(MetServiceType.Consumer);
        member.getService().setProtocols(new String[] { "rsf" });
        member.setUsedProtocols(new String[] { "grpc" });
        member.setEndpoints(new MetServiceEndpoint[] { endpoint("rsf") });
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setProtocol("rsf");

        // 筛选直接读取成员的使用状态，不把配置协议当作已使用协议。
        List<MetServiceMember> source = List.of(member);
        MetPage<MetServiceMember> page = DirectoryMapping.local(source, query, DirectoryMapping::memberIdentity);
        assertTrue(page.getItems().isEmpty());
    }

    @Test
    public void roleSearchAcceptsTheChineseProviderLabel() {
        // 角色搜索保留管理页面使用的中文提示。
        MetServiceInfo service = summary("orders", "rsf");
        MetDirectoryQuery query = search("role", "提供者");

        // 直接读取摘要角色即可完成筛选，不需要还原运行登记。
        MetPage<MetServiceInfo> page = DirectoryMapping.local(List.of(service), query, MetServiceInfo::getServiceId);
        assertEquals(List.of(service), page.getItems());
    }

    @Test
    public void roleSearchIgnoresEnglishTextCase() {
        // 角色的文本搜索与精确协议选择器使用不同的大小写语义。
        MetServiceInfo service = summary("orders", "rsf");
        service.setRole(MetServiceType.Consumer);
        MetDirectoryQuery query = search("role", "cOnSuMeR");

        // 英文角色名按照普通文本搜索处理。
        MetPage<MetServiceInfo> page = DirectoryMapping.local(List.of(service), query, MetServiceInfo::getServiceId);
        assertEquals(List.of(service), page.getItems());
    }

    @Test
    public void memberSearchReadsTheApplicationName() {
        // 应用名属于实例，不能用服务摘要中的名称替代。
        MetServiceMember member = member(MetServiceType.Provider);
        MetDirectoryQuery query = search("application", "SHOP-API");

        // 应用名保留普通文本搜索的大小写不敏感语义。
        List<MetServiceMember> source = List.of(member);
        MetPage<MetServiceMember> page = DirectoryMapping.local(source, query, DirectoryMapping::memberIdentity);
        assertEquals(List.of(member), page.getItems());
    }

    @Test
    public void memberSearchReadsTheUnitName() {
        // 单元搜索直接读取成员所属实例的单元名称。
        MetServiceMember member = member(MetServiceType.Provider);
        MetDirectoryQuery query = search("unit", "EAST");

        // 文本条件不需要额外的存储对象转换。
        List<MetServiceMember> source = List.of(member);
        MetPage<MetServiceMember> page = DirectoryMapping.local(source, query, DirectoryMapping::memberIdentity);
        assertEquals(List.of(member), page.getItems());
    }

    @Test
    public void memberUnitSelectorRejectsADifferentUnit() {
        // 单元选择器精确限定实例范围。
        MetServiceMember member = member(MetServiceType.Provider);
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setUnitName("west");

        // 不属于目标单元的实例不进入本地目录结果。
        List<MetServiceMember> source = List.of(member);
        MetPage<MetServiceMember> page = DirectoryMapping.local(source, query, DirectoryMapping::memberIdentity);
        assertTrue(page.getItems().isEmpty());
    }

    @Test
    public void memberAddressSearchIncludesTheActualListenAddress() {
        // 发布地址与监听地址可能不同，搜索仍然需要覆盖实际监听地址。
        MetServiceMember member = member(MetServiceType.Provider);
        MetServiceEndpoint endpoint = endpoint("rsf");
        endpoint.setPublishedAddress("rsf://public.example:2181");
        endpoint.setListenAddress("rsf://127.0.0.1:2181");
        member.setEndpoints(new MetServiceEndpoint[] { endpoint });
        MetDirectoryQuery query = search("address", "127.0.0.1:2181");

        // 成员端点提供完整地址，摘要不需要复制这些端点。
        List<MetServiceMember> source = List.of(member);
        MetPage<MetServiceMember> page = DirectoryMapping.local(source, query, DirectoryMapping::memberIdentity);
        assertEquals(List.of(member), page.getItems());
    }

    @Test
    public void memberDefaultSearchIncludesTheProcessIdentity() {
        // 实例身份文本由实例 ID、IP 和进程号组成。
        MetServiceMember member = member(MetServiceType.Provider);
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setSearch("4321");

        // 未指定字段时，默认搜索使用完整成员身份。
        List<MetServiceMember> source = List.of(member);
        MetPage<MetServiceMember> page = DirectoryMapping.local(source, query, DirectoryMapping::memberIdentity);
        assertEquals(List.of(member), page.getItems());
    }

    @Test
    public void summaryAddressSearchDoesNotInventEndpointAddresses() {
        // 服务摘要只有协议名，没有任何真实端点地址。
        MetServiceInfo service = summary("orders", "rsf");
        MetDirectoryQuery query = search("address", "rsf");

        // 协议名不能被伪造成地址，也不能误中地址搜索。
        MetPage<MetServiceInfo> page = DirectoryMapping.local(List.of(service), query, MetServiceInfo::getServiceId);
        assertTrue(page.getItems().isEmpty());
    }

    @Test
    public void localQueryRejectsAnUnsupportedSearchField() {
        // 已移除的 Java 类型不是目录摘要的搜索字段。
        MetServiceInfo service = summary("orders", "rsf");
        MetDirectoryQuery query = search("type", "OrderService");

        // 非法字段保留明确错误，不悄悄返回空结果。
        assertThrows(IllegalArgumentException.class, () -> {
            DirectoryMapping.local(List.of(service), query, MetServiceInfo::getServiceId);
        });
    }

    @Test
    public void localQueryFiltersBeforeApplyingTheCursorAndLimit() {
        // 非匹配项穿插在匹配项之间，游标应指向筛选后的目录。
        MetServiceInfo first = summary("orders-first", "rsf");
        MetServiceInfo second = summary("orders-second", "rsf");
        MetServiceInfo third = summary("orders-third", "rsf");
        MetServiceInfo excluded = summary("other", "soap");
        List<MetServiceInfo> source = List.of(excluded, first, excluded, second, third);
        MetDirectoryQuery query = search("id", "orders");
        query.setCursor("1");
        query.setLimit(1);

        // 第二个匹配项是当前页，下一游标也是筛选后的偏移。
        MetPage<MetServiceInfo> page = DirectoryMapping.local(source, query, MetServiceInfo::getServiceId);
        assertEquals(List.of(second), page.getItems());
        assertEquals("2", page.getNextCursor());
    }

    @Test
    public void cursorBeyondTheSelectedRowsReturnsAnEmptyFinalPage() {
        // 目录在连续翻页之间可能变短，已超出范围的游标仍返回终止页。
        MetServiceInfo service = summary("orders", "rsf");
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setCursor("5");

        // 空结果不再生成新的游标。
        MetPage<MetServiceInfo> page = DirectoryMapping.local(List.of(service), query, MetServiceInfo::getServiceId);
        assertTrue(page.getItems().isEmpty());
        assertNull(page.getNextCursor());
    }

    @Test
    public void localQueryRejectsANegativeCursor() {
        // 本地分页游标表示非负偏移。
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setCursor("-1");

        // 非法偏移不能被当作从第一页开始。
        assertThrows(IllegalArgumentException.class, () -> {
            DirectoryMapping.local(List.<MetServiceInfo>of(), query, MetServiceInfo::getServiceId);
        });
    }

    @Test
    public void localQueryRejectsANonNumericCursor() {
        // 本地目录只能使用它自己返回的数值游标。
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setCursor("invalid");

        // 不透明的远端游标不能误用于本地分页。
        assertThrows(IllegalArgumentException.class, () -> {
            DirectoryMapping.local(List.<MetServiceInfo>of(), query, MetServiceInfo::getServiceId);
        });
    }

    @Test
    public void queryRejectsAPageLimitBelowTheMinimum() {
        // 空页面大小不属于有效查询范围。
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setLimit(0);

        // 参数在进入目录读取之前统一校验。
        assertThrows(IllegalArgumentException.class, () -> DirectoryMapping.query(query));
    }

    @Test
    public void queryRejectsAPageLimitAboveTheMaximum() {
        // 管理查询最多接受一千项。
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setLimit(1001);

        // 本地与存储查询共用相同的分页上限。
        assertThrows(IllegalArgumentException.class, () -> DirectoryMapping.query(query));
    }

    private static EntServiceRuntimeData registration(MetServiceType role) {
        EntServiceRuntimeData registration = new EntServiceRuntimeData();
        registration.setServiceId("orders");
        registration.setGroup("shop");
        registration.setName("OrderService");
        registration.setVersion("v2");
        registration.setRole(role);
        return registration;
    }

    private static RefProtocolMountData storedEndpoint(String protocolName) {
        RefProtocolMountData endpoint = new RefProtocolMountData();
        endpoint.setProtocolName(protocolName);
        return endpoint;
    }

    private static MetServiceInfo summary(String serviceId, String protocolName) {
        MetServiceInfo service = new MetServiceInfo();
        service.setServiceId(serviceId);
        service.setRole(MetServiceType.Provider);
        service.setProtocols(new String[] { protocolName });
        return service;
    }

    private static MetServiceMember member(MetServiceType role) {
        MetInstanceInfo instance = new MetInstanceInfo();
        instance.setInstanceId("shop-1");
        instance.setIpAddress("192.0.2.10");
        instance.setProcessId(4321L);
        instance.setApplicationName("shop-api");
        instance.setUnitName("east");
        MetServiceInfo service = summary("orders", "rsf");
        service.setRole(role);
        MetServiceMember member = new MetServiceMember();
        member.setInstance(instance);
        member.setService(service);
        return member;
    }

    private static MetServiceEndpoint endpoint(String protocolName) {
        MetServiceEndpoint endpoint = new MetServiceEndpoint();
        endpoint.setProtocolName(protocolName);
        return endpoint;
    }

    private static MetDirectoryQuery search(String field, String text) {
        MetDirectoryQuery query = new MetDirectoryQuery();
        query.setField(field);
        query.setSearch(text);
        return query;
    }
}
