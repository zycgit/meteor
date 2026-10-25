/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.protocol.soap.python;
import java.util.Collection;
import net.hasor.realmeteor.matrix.OrderDiscovery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** 单独检查 Python SOAP 的发现筛选，不启动 Python 或 SOAP 服务。 */
public class SoapPythonMatrixSelectionTest {
    private String previousDiscovery;

    @Before
    public void isolateDiscoverySelection() {
        // 不继承外部任务的筛选，避免筛选校验本身受运行范围影响。
        this.previousDiscovery = System.getProperty("meteor.matrix.discoveries");
        System.clearProperty("meteor.matrix.discoveries");
    }

    @After
    public void restoreDiscoverySelection() {
        // 运行后还原原始命令行或默认状态。
        if (this.previousDiscovery == null) {
            System.clearProperty("meteor.matrix.discoveries");
        } else {
            System.setProperty("meteor.matrix.discoveries", this.previousDiscovery);
        }
    }

    @Test
    public void discoverySelectionKeepsOnlyTheRequestedBackend() {
        // 明确只选择一个注册中心，不构建实际环境。
        System.setProperty("meteor.matrix.discoveries", "nacos");
        Collection<Object[]> cases = SoapPythonOrderInteropTest.configurations();

        // 参数结果中只有所选后端。
        assertEquals(1, cases.size());
        assertEquals(OrderDiscovery.NACOS, cases.iterator().next()[0]);
    }

    @Test
    public void discoverySelectionAcceptsTheWildcardForEveryBackend() {
        // 通配符覆盖直连和全部已实现的注册中心。
        System.setProperty("meteor.matrix.discoveries", "*");
        Collection<Object[]> cases = SoapPythonOrderInteropTest.configurations();

        // 预期数量直接来自当前发现枚举。
        assertEquals(OrderDiscovery.values().length, cases.size());
    }

    @Test
    public void discoverySelectionAcceptsAllForEveryBackend() {
        // all 与其余矩阵采用相同的完整选择语义。
        System.setProperty("meteor.matrix.discoveries", "all");
        Collection<Object[]> cases = SoapPythonOrderInteropTest.configurations();

        // 每种发现方式都产生一个独立参数。
        assertEquals(OrderDiscovery.values().length, cases.size());
    }

    @Test
    public void discoverySelectionRejectsAnUnknownBackendBesideAKnownBackend() {
        // 有效后端旁的未知条目必须报错，不能只留下有效子集。
        System.setProperty("meteor.matrix.discoveries", "nacos,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, SoapPythonOrderInteropTest::configurations);

        // 提示用户修正发现配置属性。
        assertTrue(failure.getMessage().contains("meteor.matrix.discoveries"));
    }

    @Test
    public void discoverySelectionRejectsAnUnknownBackendBesideAll() {
        // all 不能掩盖列表中另一个条目的拼写错误。
        System.setProperty("meteor.matrix.discoveries", "all,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, SoapPythonOrderInteropTest::configurations);

        // 完整校验后才处理全选，错误仍定位到发现属性。
        assertTrue(failure.getMessage().contains("meteor.matrix.discoveries"));
    }

    @Test
    public void discoverySelectionRejectsAnEmptyToken() {
        // 连续逗号中的空项不能被当作不存在。
        System.setProperty("meteor.matrix.discoveries", "direct,,nacos");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, SoapPythonOrderInteropTest::configurations);

        // 错误保留具体属性，不能以零测试或部分测试结束。
        assertTrue(failure.getMessage().contains("meteor.matrix.discoveries"));
    }
}
