/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.realmeteor.matrix;
import java.util.*;
import net.hasor.example.contract.scalar.ScalarValueProfile;
import net.hasor.example.matrix.OrderProtocol;
import net.hasor.realmeteor.composition.state.OrderedProtocolStateTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 只检查矩阵参数选择，不启动应用、注册中心或协议对端。 */
public class MatrixSelectionTest {
    private final Map<String, String> previousProperties = new LinkedHashMap<>();

    @BeforeEach
    public void isolateSelectionProperties() {
        // 每个用例独占筛选条件，既不继承命令行范围，也不污染随后运行的真实矩阵。
        String[] properties = { // 每个独立筛选维度分别保存。
                "meteor.matrix.protocols", //
                "meteor.matrix.discoveries", //
                "meteor.matrix.scalarProfiles", //
                "meteor.matrix.complexProfiles", //
                "meteor.matrix.writeProtocols", //
                "meteor.matrix.readProtocols", //
        };
        for (String property : properties) {
            this.previousProperties.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
    }

    @AfterEach
    public void restoreSelectionProperties() {
        // 恢复调用者已有的设置，包括原本不存在的属性。
        for (Map.Entry<String, String> entry : this.previousProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    @Test
    public void orderSelectionKeepsOnlyTheRequestedProtocolAndDiscovery() {
        // 精确选择一个协议和一种发现方式。
        System.setProperty("meteor.matrix.protocols", "grpc");
        System.setProperty("meteor.matrix.discoveries", "direct");
        Collection<Object[]> cases = OrderSelfMatrixTest.configurations();

        // 返回一个独立参数，而不是整个协议族或注册中心集合。
        assertEquals(1, cases.size());
        Object[] selected = cases.iterator().next();
        assertEquals(OrderProtocol.GRPC, selected[0]);
        assertEquals(OrderDiscovery.DIRECT, selected[1]);
    }

    @Test
    public void registryOrderSelectionKeepsEveryProtocolAndRegisteredDiscovery() {
        // 普通直连订单迁入标准能力矩阵，注册入口仍枚举全部已有注册方式。
        Collection<Object[]> cases = OrderRegistryMatrixTest.registryConfigurations();

        // 边界与 Unicode 入口使用的共享参数仍包含直连，不能被迁移一起裁掉。
        int expected = OrderProtocol.values().length * (OrderDiscovery.values().length - 1);
        assertEquals(expected, cases.size());
        for (Object[] selected : cases) {
            assertNotEquals(OrderDiscovery.DIRECT, selected[1]);
        }
        int all = OrderProtocol.values().length * OrderDiscovery.values().length;
        assertEquals(all, OrderSelfMatrixTest.configurations().size());
    }

    @Test
    public void registryOrderSelectionPreservesTheRequestedProtocolAndRegistry() {
        // 同时选中直连与 JDBC 时，注册入口只接收请求的 JDBC 组合。
        System.setProperty("meteor.matrix.protocols", "grpc");
        System.setProperty("meteor.matrix.discoveries", "direct,jdbc");
        Collection<Object[]> cases = OrderRegistryMatrixTest.registryConfigurations();

        // 排除直连不会扩大协议或注册方式的选择范围。
        assertEquals(1, cases.size());
        Object[] selected = cases.iterator().next();
        assertEquals(OrderProtocol.GRPC, selected[0]);
        assertEquals(OrderDiscovery.JDBC, selected[1]);
    }

    @Test
    public void registryOrderSelectionRejectsAnEmptyRegisteredSelection() {
        // 显式只选直连时，注册入口不能以零测试宣称执行成功。
        System.setProperty("meteor.matrix.discoveries", "direct");
        IllegalArgumentException failure = assertThrows( //
                IllegalArgumentException.class, OrderRegistryMatrixTest::registryConfigurations);

        // 错误指出空的注册矩阵，普通直连仍由标准能力矩阵负责。
        assertTrue(failure.getMessage().contains("registry"));
    }

    @Test
    public void orderSelectionRejectsAnUnknownProtocolBesideAKnownProtocol() {
        // 合法条目不能掩盖同一列表中的拼写错误。
        System.setProperty("meteor.matrix.protocols", "grpc,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, OrderSelfMatrixTest::configurations);

        // 错误指向用户需要调整的筛选属性。
        assertTrue(failure.getMessage().contains("meteor.matrix.protocols"));
    }

    @Test
    public void orderSelectionRejectsAnUnknownProtocolBesideTheWildcard() {
        // 通配符也不能让后续的未知协议绕过参数校验。
        System.setProperty("meteor.matrix.protocols", "*,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, OrderSelfMatrixTest::configurations);

        // 先检查全部条目，再决定是否选择全部协议。
        assertTrue(failure.getMessage().contains("meteor.matrix.protocols"));
    }

    @Test
    public void orderSelectionIgnoresCaseAndSurroundingWhitespace() {
        // 命令行中的大小写与外层空白不改变精确选择。
        System.setProperty("meteor.matrix.protocols", " GRpC ");
        System.setProperty("meteor.matrix.discoveries", " DiReCt ");
        Collection<Object[]> cases = OrderSelfMatrixTest.configurations();

        // 规范化后仍只生成一个指定组合。
        assertEquals(1, cases.size());
        Object[] selected = cases.iterator().next();
        assertEquals(OrderProtocol.GRPC, selected[0]);
        assertEquals(OrderDiscovery.DIRECT, selected[1]);
    }

    @Test
    public void orderSelectionAcceptsTheWildcardForEveryProtocolAndDiscovery() {
        // 通配符在两个维度均表示不限制。
        System.setProperty("meteor.matrix.protocols", "*");
        System.setProperty("meteor.matrix.discoveries", "*");
        Collection<Object[]> cases = OrderSelfMatrixTest.configurations();

        // 枚举数来自真实协议与发现枚举，不依赖历史验收数量。
        int expected = OrderProtocol.values().length * OrderDiscovery.values().length;
        assertEquals(expected, cases.size());
    }

    @Test
    public void orderSelectionAcceptsAllForEveryProtocolAndDiscovery() {
        // all 与通配符使用相同的完整矩阵语义。
        System.setProperty("meteor.matrix.protocols", "all");
        System.setProperty("meteor.matrix.discoveries", "all");
        Collection<Object[]> cases = OrderSelfMatrixTest.configurations();

        // 两个维度的全部组合都成为独立参数。
        int expected = OrderProtocol.values().length * OrderDiscovery.values().length;
        assertEquals(expected, cases.size());
    }

    @Test
    public void orderSelectionRejectsAnUnknownDiscoveryBesideAKnownDiscovery() {
        // 发现列表中包含一个存在和一个不存在的配置。
        System.setProperty("meteor.matrix.discoveries", "direct,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, OrderSelfMatrixTest::configurations);

        // 不能只执行 direct 后把整项选择视为成功。
        assertTrue(failure.getMessage().contains("meteor.matrix.discoveries"));
    }

    @Test
    public void orderSelectionRejectsAnEmptyProtocolToken() {
        // 末尾逗号代表遗漏条目，不能被 split 丢弃后继续执行。
        System.setProperty("meteor.matrix.protocols", "grpc,");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, OrderSelfMatrixTest::configurations);

        // 提示仍然定位到原始筛选属性。
        assertTrue(failure.getMessage().contains("meteor.matrix.protocols"));
    }

    @Test
    public void unicodeSelectionRejectsAProtocolWithNoUnicodeCases() {
        // XDR 字符串不属于 Unicode 正向矩阵，明确选择它会生成零项。
        System.setProperty("meteor.matrix.protocols", "oncrpc");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, OrderUnicodeMatrixTest::configurations);

        // 零项选择以明确错误结束，不伪造 ONC Unicode 支持。
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("unicode"));
    }

    @Test
    public void complexSelectionRejectsAnUnknownProfileBesideAKnownProfile() {
        // 有效编码与未知编码同时出现时整项筛选失败。
        System.setProperty("meteor.matrix.complexProfiles", "grpc-protobuf,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ComplexValueMatrixTest::configurations);

        // 协议维度本身有效也不能吞掉 profile 错误。
        assertTrue(failure.getMessage().contains("meteor.matrix.complexProfiles"));
    }

    @Test
    public void complexSelectionRejectsMutuallyExclusiveProtocolAndProfile() {
        // 两个合法条件各自存在，但没有共同的参数组合。
        System.setProperty("meteor.matrix.protocols", "grpc");
        System.setProperty("meteor.matrix.complexProfiles", "rsf-json");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ComplexValueMatrixTest::configurations);

        // 报告复杂模型矩阵为空，而不是以零测试成功。
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("complex"));
    }

    @Test
    public void scalarSelectionRejectsAnUnknownProtocolBesideAKnownProtocol() {
        // 标量矩阵不能在合法协议旁静默忽略未知协议。
        System.setProperty("meteor.matrix.protocols", "grpc,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ScalarValueMatrixTest::configurations);

        // 返回原始协议属性，方便定位命令行参数。
        assertTrue(failure.getMessage().contains("meteor.matrix.protocols"));
    }

    @Test
    public void scalarSelectionRejectsAnUnknownProfileWithoutAnyKnownProfile() {
        // 不存在的 profile 不能生成一个空的成功测试类。
        System.setProperty("meteor.matrix.scalarProfiles", "typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ScalarValueMatrixTest::configurations);

        // 错误直接指出具体 profile 属性。
        assertTrue(failure.getMessage().contains("meteor.matrix.scalarProfiles"));
    }

    @Test
    public void scalarSelectionRejectsMutuallyExclusiveProtocolAndProfile() {
        // 协议名和 profile 各自有效，但不能构成同一个标量编码。
        System.setProperty("meteor.matrix.protocols", "grpc");
        System.setProperty("meteor.matrix.scalarProfiles", "rsf-json");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ScalarValueMatrixTest::configurations);

        // 明确报告标量矩阵无匹配，而不是以空集合结束。
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("scalar"));
    }

    @Test
    public void scalarSelectionAcceptsTheWildcardForEveryProfile() throws Exception {
        // 标量的协议与编码选择均接受通配符。
        System.setProperty("meteor.matrix.protocols", "*");
        System.setProperty("meteor.matrix.scalarProfiles", "*");
        Collection<Object[]> cases = ScalarValueMatrixTest.configurations();

        // 每种编码至少拥有自己能表达的向量，不要求原生不支持的类型。
        EnumSet<ScalarValueProfile> profiles = EnumSet.noneOf(ScalarValueProfile.class);
        for (Object[] selected : cases) {
            profiles.add((ScalarValueProfile) selected[0]);
        }
        assertEquals(EnumSet.allOf(ScalarValueProfile.class), profiles);
    }

    @Test
    public void scalarSelectionAcceptsAllForEveryProfile() throws Exception {
        // all 与通配符在标量协议和编码两个维度含义相同。
        System.setProperty("meteor.matrix.protocols", "all");
        System.setProperty("meteor.matrix.scalarProfiles", "all");
        Collection<Object[]> cases = ScalarValueMatrixTest.configurations();

        // 只核对适用 profile，不把规范限制的类型强塞进矩阵。
        EnumSet<ScalarValueProfile> profiles = EnumSet.noneOf(ScalarValueProfile.class);
        for (Object[] selected : cases) {
            profiles.add((ScalarValueProfile) selected[0]);
        }
        assertEquals(EnumSet.allOf(ScalarValueProfile.class), profiles);
    }

    @Test
    public void momentSelectionRejectsAnUnknownProfile() {
        // 日期矩阵复用标量选择规则，也必须拒绝未知 profile。
        System.setProperty("meteor.matrix.scalarProfiles", "typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ScalarMomentMatrixTest::configurations);

        // 校验不能依赖普通标量矩阵恰好也被运行。
        assertTrue(failure.getMessage().contains("meteor.matrix.scalarProfiles"));
    }

    @Test
    public void momentSelectionRejectsMutuallyExclusiveProtocolAndProfile() {
        // 合法但互斥的两个筛选条件不能生成任何日期用例。
        System.setProperty("meteor.matrix.protocols", "grpc");
        System.setProperty("meteor.matrix.scalarProfiles", "rsf-json");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ScalarMomentMatrixTest::configurations);

        // 日期矩阵独立检查零项，不依赖普通标量矩阵的校验。
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("moment"));
    }

    @Test
    public void decimalSelectionRejectsAProtocolWithNoNativeDecimalCases() {
        // XML-RPC 没有任意精度 decimal，明确选择它只会产生零项。
        System.setProperty("meteor.matrix.protocols", "xmlrpc");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, ScalarDecimalMatrixTest::configurations);

        // 告知小数矩阵没有匹配项，不用字符串适配伪造支持。
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("decimal"));
    }

    @Test
    public void orderedSelectionAcceptsTheWildcardForBothProtocolDirections() {
        // 写入和读取都选择全部协议，发现方式固定为直连。
        System.setProperty("meteor.matrix.writeProtocols", "*");
        System.setProperty("meteor.matrix.readProtocols", "*");
        System.setProperty("meteor.matrix.discoveries", "direct");
        Collection<Object[]> cases = OrderedProtocolStateTest.cases();

        // 组合矩阵只包含不同协议之间的有序写入和读取。
        int protocols = OrderProtocol.values().length;
        assertEquals(protocols * (protocols - 1), cases.size());
    }

    @Test
    public void orderedSelectionRejectsAnUnknownWriterBesideAKnownWriter() {
        // 写入条件的未知条目不能被合法的 grpc 掩盖。
        System.setProperty("meteor.matrix.writeProtocols", "grpc,typo");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, OrderedProtocolStateTest::cases);

        // 精确指出写方向筛选属性。
        assertTrue(failure.getMessage().contains("meteor.matrix.writeProtocols"));
    }

    @Test
    public void orderedSelectionRejectsIdenticalSingleWriterAndReader() {
        // 同一协议自体互通归另一矩阵，此选择没有跨协议组合。
        System.setProperty("meteor.matrix.writeProtocols", "grpc");
        System.setProperty("meteor.matrix.readProtocols", "grpc");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, OrderedProtocolStateTest::cases);

        // 零组合必须明确失败，不能让交叉回归报告零项通过。
        String message = failure.getMessage().toLowerCase(Locale.ROOT);
        assertTrue(message.contains("protocol"));
    }
}
