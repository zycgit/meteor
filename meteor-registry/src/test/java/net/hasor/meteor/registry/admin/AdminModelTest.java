/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.registry.admin;
import net.hasor.meteor.registry.shared.rpc.ServiceEndpointData;
import net.hasor.meteor.registry.shared.rpc.ServiceMemberData;
import net.hasor.meteor.registry.shared.rpc.ValidationData;
import net.hasor.meteor.registry.shared.utils.RegistryJson;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdminModelTest {
    @Test
    public void memberAddressesFollowPublishedEndpointsInsteadOfStoredSummary() throws Exception {
        // 准备重复发布地址、未发布端点及过期汇总地址。
        ServiceEndpointData first = new ServiceEndpointData();
        first.setPublishedAddress("rsf://localhost:2181");
        ServiceEndpointData duplicate = new ServiceEndpointData();
        duplicate.setPublishedAddress(first.getPublishedAddress());
        ServiceEndpointData unpublished = new ServiceEndpointData();
        unpublished.setListenAddress("rsf://localhost:2182");

        ServiceMemberData stored = new ServiceMemberData();
        stored.setEndpoints(new ServiceEndpointData[] { first, duplicate, unpublished });
        stored.setAddresses(new String[] { "rsf://stale:2181" });

        // 转换后的管理模型仅从已发布端点去重汇总地址。
        MetServiceMember member = RegistryJson.copy(stored, MetServiceMember.class);
        assertArrayEquals(new String[] { first.getPublishedAddress() }, member.getAddresses());

        // 发布地址以端点为准，修改返回模型后也不会残留旧的地址汇总。
        member.getEndpoints()[0].setPublishedAddress(null);
        member.getEndpoints()[1].setPublishedAddress(null);
        assertEquals(0, member.getAddresses().length);

        // 序列化往返后仍保持派生地址的语义。
        MetServiceMember restored = RegistryJson.read(RegistryJson.write(member), MetServiceMember.class);
        assertEquals(0, restored.getAddresses().length);
        assertEquals(unpublished.getListenAddress(), restored.getEndpoints()[2].getListenAddress());
    }

    @Test
    public void validationFollowsErrorsAcrossConversionAndRoundTrip() throws Exception {
        // 输入声明成功但含错误信息，结果应以错误列表为准。
        String inconsistentResult = "{\"valid\":true,\"errors\":[\"Invalid rule\"]}";
        ValidationData stored = RegistryJson.read(inconsistentResult, ValidationData.class);
        assertFalse(stored.isValid());

        // 转换为管理模型后保留相同的校验结果。
        MetValidationResult result = RegistryJson.copy(stored, MetValidationResult.class);
        assertFalse(result.isValid());
        assertArrayEquals(stored.getErrors(), result.getErrors());

        // 清空错误后恢复有效状态，序列化往返保持一致。
        result.setErrors(new String[0]);
        assertTrue(result.isValid());
        MetValidationResult restored = RegistryJson.read(RegistryJson.write(result), MetValidationResult.class);
        assertTrue(restored.isValid());
        assertEquals(0, restored.getErrors().length);
    }
}
