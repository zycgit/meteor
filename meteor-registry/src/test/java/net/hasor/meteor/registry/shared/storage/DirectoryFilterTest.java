/*
 * Copyright 2015-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 */
package net.hasor.meteor.registry.shared.storage;
import java.util.ArrayList;
import java.util.List;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.registry.shared.storage.domain.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** 目录字段和协议摘要的纯数据筛选规则。 */
public class DirectoryFilterTest {
    @Test
    public void providerProtocolNamesExcludeBlankNames() {
        // 未填写协议名的端点不参与协议摘要。
        EntServiceRuntimeData service = provider(null, "", " \t", "rsf");

        // 有效名称来自端点的 protocolName。
        assertEquals(List.of("rsf"), new ArrayList<>(DirectoryFilter.protocolNames(service)));
    }

    @Test
    public void providerProtocolNamesAreDistinctAndSorted() {
        // 多个端点可以挂载同一协议，登记顺序不决定摘要顺序。
        EntServiceRuntimeData service = provider("soap", "rsf", "grpc", "soap");

        // 摘要按协议名排序并去重。
        List<String> protocolNames = new ArrayList<>(DirectoryFilter.protocolNames(service));
        assertEquals(List.of("grpc", "rsf", "soap"), protocolNames);
    }

    @Test
    public void consumerProtocolNamesOnlyUseSelectedProtocols() {
        // 消费者配置了 SOAP 端点，但当前服务只使用 gRPC 和 RSF。
        EntServiceRuntimeData service = provider("soap");
        service.setRole(MetServiceType.Consumer);
        service.setUsedProtocols(new String[] { "rsf", "grpc", "rsf" });

        // 消费摘要仅根据当前服务的 usedProtocols 计算。
        List<String> protocolNames = new ArrayList<>(DirectoryFilter.protocolNames(service));
        assertEquals(List.of("grpc", "rsf"), protocolNames);
    }

    @Test
    public void protocolTextUsesCanonicalProtocolNames() {
        // 消费协议的原始顺序和重复项不应产生另一种摘要格式。
        EntServiceRuntimeData service = provider("soap");
        service.setRole(MetServiceType.Consumer);
        service.setUsedProtocols(new String[] { "rsf", "grpc", "rsf" });

        // 文本摘要复用同一套协议名称选择、排序和去重规则。
        assertEquals("grpc rsf", DirectoryFilter.protocols(service));
    }

    @Test
    public void protocolFilterRequiresAnExactProtocolName() {
        // 相近协议名不能通过子串匹配混为同一协议。
        EntServiceRuntimeData service = provider("grpc-web");

        // 完整名称匹配成功，只有名称前缀时不匹配。
        assertTrue(DirectoryFilter.protocol(service, "grpc-web"));
        assertFalse(DirectoryFilter.protocol(service, "grpc"));
    }

    @Test
    public void protocolFilterReadsTheServiceFromAMember() {
        // 成员关系携带自己的实例服务登记。
        RefServiceRelationData member = new RefServiceRelationData();
        member.setService(provider("rsf"));

        // 协议选择直接读取成员的服务端点。
        assertTrue(DirectoryFilter.protocol(member, "rsf"));
    }

    @Test
    public void blankProtocolFilterDoesNotRestrictTheRecord() {
        // 没有指定协议时，不要求查询对象带有服务登记。
        EntServiceCatalogData catalog = new EntServiceCatalogData();

        // 空白条件保留无筛选行为。
        assertTrue(DirectoryFilter.protocol(catalog, " \t"));
    }

    @Test
    public void protocolFilterRejectsARecordWithoutServiceRegistration() {
        // 持久服务目录没有实例级别的协议使用情况。
        EntServiceCatalogData catalog = new EntServiceCatalogData();

        // 指定协议时仍明确拒绝不支持的记录类型。
        assertThrows(IllegalArgumentException.class, () -> DirectoryFilter.protocol(catalog, "rsf"));
    }

    @Test
    public void applicationTextReadsTheMemberInstance() {
        // 成员查询的应用名称来自该成员的实例身份。
        EntInstanceRuntimeData instance = new EntInstanceRuntimeData();
        instance.setApplicationName("orders");
        RefServiceRelationData member = new RefServiceRelationData();
        member.setInstance(instance);

        // 不使用调用方传入的身份回退文本替代应用名称。
        assertEquals("orders", DirectoryFilter.text(member, "application", "instance-one"));
    }

    @Test
    public void unitTextReadsTheInstanceStatusIdentity() {
        // 实例查询结果将身份放在 InstanceStatus 中。
        EntInstanceCatalogData instance = new EntInstanceCatalogData();
        instance.setUnitName("east");
        InstanceStatus status = new InstanceStatus();
        status.setInstance(instance);

        // 单元字段沿用实例身份中的值。
        assertEquals("east", DirectoryFilter.text(status, "unit", "instance-one"));
    }

    @Test
    public void identityTextUsesTheCallerFallback() {
        // 身份字段由存储查询提供，无需重新读取记录正文。
        EntInstanceCatalogData instance = new EntInstanceCatalogData();
        instance.setInstanceId("record-instance");

        // 回退文本保持查询方选定的身份语义。
        assertEquals("selected-instance", DirectoryFilter.text(instance, "instance", "selected-instance"));
    }

    @Test
    public void unspecifiedTextFieldUsesTheCallerFallback() {
        // 未指定字段时沿用查询方的默认文本。
        EntServiceRuntimeData service = provider("rsf");

        // 默认字段不会转为协议或角色筛选。
        assertEquals("orders", DirectoryFilter.text(service, null, "orders"));
    }

    @Test
    public void providerRoleTextRetainsItsSearchTerms() {
        // 发布者登记保留用户可搜索的中英文角色名称。
        EntServiceRuntimeData service = provider("rsf");

        // 角色文本继续支持发布和提供者两种表述。
        assertEquals("发布 提供者 Provider", DirectoryFilter.text(service, "role", "orders"));
    }

    @Test
    public void consumerRoleTextRetainsItsSearchTerms() {
        // 订阅者登记与发布者使用不同的角色搜索文本。
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setRole(MetServiceType.Consumer);

        // 角色文本继续支持订阅和消费者两种表述。
        assertEquals("订阅 消费者 Consumer", DirectoryFilter.text(service, "role", "orders"));
    }

    @Test
    public void protocolTextReadsCanonicalServiceNames() {
        // 同一协议可以挂载多个端点，文本查询应复用协议摘要。
        EntServiceRuntimeData service = provider("rsf", "grpc", "rsf");

        // 字段分派保留规范化后的协议文本。
        assertEquals("grpc rsf", DirectoryFilter.text(service, "protocol", "orders"));
    }

    @Test
    public void addressTextCombinesListenAndPublishedAddresses() {
        // 监听地址和发布地址可以相同，也可以按端点分别配置。
        EntServiceRuntimeData service = provider("rsf", "grpc");
        RefProtocolMountData[] endpoints = service.getEndpoints();
        endpoints[0].setListenAddress("rsf://0.0.0.0:8000");
        endpoints[0].setPublishedAddress("rsf://orders:8000");
        endpoints[1].setListenAddress("rsf://orders:8000");

        // 地址文本继续合并端点地址，并按稳定顺序去重。
        String addresses = DirectoryFilter.text(service, "address", "orders");
        assertEquals("rsf://0.0.0.0:8000 rsf://orders:8000", addresses);
    }

    @Test
    public void textRejectsAnUnsupportedField() {
        // 查询字段不能任意读取登记对象属性。
        EntServiceRuntimeData service = provider("rsf");

        // 未支持的字段仍向调用方报告错误。
        assertThrows(IllegalArgumentException.class, () -> DirectoryFilter.text(service, "enabled", "orders"));
    }

    private static EntServiceRuntimeData provider(String... protocolNames) {
        RefProtocolMountData[] endpoints = new RefProtocolMountData[protocolNames.length];
        for (int index = 0; index < protocolNames.length; index++) {
            RefProtocolMountData endpoint = new RefProtocolMountData();
            endpoint.setProtocolName(protocolNames[index]);
            endpoints[index] = endpoint;
        }
        EntServiceRuntimeData service = new EntServiceRuntimeData();
        service.setRole(MetServiceType.Provider);
        service.setEndpoints(endpoints);
        return service;
    }
}
