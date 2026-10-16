/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.route;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.QoSBucket;
import net.hasor.cobble.setting.MergedSettings;
import net.hasor.cobble.setting.Settings;
import net.hasor.cobble.setting.provider.StreamType;
import net.hasor.meteor.address.AddressPool;
import net.hasor.meteor.address.FlowControlRef;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.route.random.RandomFlowControl;
import net.hasor.meteor.address.route.speed.SpeedFlowControl;
import net.hasor.meteor.address.route.unit.UnitFlowControl;
import org.junit.Test;
import static net.hasor.meteor.address.route.FlowControlScripts.speed;
import static net.hasor.meteor.address.route.FlowControlScripts.unit;
import static org.junit.Assert.*;

public class FlowControlTest {
    private final InterAddress local  = new InterAddress("127.0.0.1", 8000, "local");
    private final InterAddress remote = new InterAddress("127.0.0.2", 8000, "remote");

    @Test
    public void unitThresholdIncludesEquality() {
        // 准备启用本单元优先、阈值为一半的规则。
        String xml = unit(true, 0.5, "192.0.2.*");
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(xml);

        // 本单元占比恰好达到阈值时启用本地优先。
        assertTrue(rule.isLocalUnit(4, 2));
    }

    @Test
    public void unitThresholdFallsBackBelowThreshold() {
        // 准备启用本单元优先、阈值为一半的规则。
        String xml = unit(true, 0.5, "192.0.2.*");
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(xml);

        // 本单元占比低于阈值时允许跨单元。
        assertFalse(rule.isLocalUnit(4, 1));
    }

    @Test
    public void unitThresholdRejectsNoAvailableProviders() {
        // 准备启用本单元优先、阈值为一半的规则。
        String xml = unit(true, 0.5, "192.0.2.*");
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(xml);

        // 没有可用地址时不能启用本地优先。
        assertFalse(rule.isLocalUnit(0, 0));
    }

    @Test
    public void unitFilterHandlesEmptyCandidates() {
        // 准备启用本单元优先、阈值为一半的规则。
        String xml = unit(true, 0.5, "192.0.2.*");
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(xml);

        // 空候选列表不产生本单元地址。
        assertNull(rule.siftUnitAddress("local", Collections.emptyList()));
    }

    @Test
    public void unitFilterHandlesNullCandidates() {
        // 准备启用本单元优先、阈值为一半的规则。
        String xml = unit(true, 0.5, "192.0.2.*");
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(xml);

        // 空引用候选不产生本单元地址。
        assertNull(rule.siftUnitAddress("local", null));
    }

    @Test
    public void unitNameMatchingIgnoresCase() {
        // 规则按本单元筛选，调用方使用大写单元名。
        String xml = unit(true, 0.5, "192.0.2.*");
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(xml);
        List<InterAddress> candidates = Arrays.asList(this.local, this.remote);

        // 大小写不影响本单元匹配。
        List<InterAddress> selected = rule.siftUnitAddress("LOCAL", candidates);
        assertEquals(Collections.singletonList(this.local), selected);
    }

    @Test
    public void unitRuleAllowsAnEmptyExclusionList() {
        // 空排除名单是合法的单元规则配置。
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(unit(true, 0.5, ""));
        assertNotNull("No excluded providers is a valid unit configuration", rule);
        List<InterAddress> candidates = Arrays.asList(this.local, this.remote);

        // 没有跨单元例外时，只保留本单元地址。
        List<InterAddress> selected = rule.siftUnitAddress("local", candidates);
        assertEquals(Collections.singletonList(this.local), selected);
    }

    @Test
    public void excludedProviderIsIncludedAcrossUnits() {
        // 把远端提供者加入跨单元排除名单。
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(unit(true, 0.5, "127.0.0.2"));
        List<InterAddress> candidates = Arrays.asList(this.local, this.remote);

        // 排除名单中的提供者与本单元地址共同参与选址。
        assertEquals(candidates, rule.siftUnitAddress("local", candidates));
    }

    @Test
    public void poolUsesLocalUnitAndFallsBackWhenItBecomesUnavailable() {
        // 配置本单元优先，并确认本地地址可用。
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        String flowScript = "<controlSet>" + unit(true, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("s", flowScript);
        assertEquals(this.local, pool.nextAddress("s", "get", null));

        // 本地地址失效后，当前调用应退回远端地址。
        pool.invalidAddress(this.local);
        assertEquals(this.remote, pool.nextAddress("s", "get", null));
    }

    @Test
    public void disabledUnitRuleKeepsAllProviders() {
        // 配置一条禁用的单元规则。
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        String disabledRule = "<controlSet>" + unit(false, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("s", disabledRule);

        // 禁用状态不缩小可用地址范围。
        assertEquals(Arrays.asList(this.local, this.remote), pool.queryLocalUnitAddresses("s"));
    }

    @Test
    public void parserRejectsNullRuleText() {
        // 准备规则解析器，输入 null 规则文本。
        RuleParser parser = new RuleParser();

        // 解析失败通过空规则结果报告。
        assertNull(parser.ruleSettings((String) null));
    }

    @Test
    public void parserRejectsBlankRuleText() {
        // 准备规则解析器，输入空规则文本。
        RuleParser parser = new RuleParser();

        // 解析失败通过空规则结果报告。
        assertNull(parser.ruleSettings(""));
    }

    @Test
    public void parserRejectsNonXmlRuleText() {
        // 准备规则解析器，输入非 XML 文本。
        RuleParser parser = new RuleParser();

        // 解析失败通过空规则结果报告。
        assertNull(parser.ruleSettings("not xml"));
    }

    @Test
    public void parserRejectsUnknownRuleType() {
        // 准备规则解析器，输入未注册规则类型。
        RuleParser parser = new RuleParser();

        // 解析失败通过空规则结果报告。
        assertNull(parser.ruleSettings("<flowControl type='unknown'></flowControl>"));
    }

    @Test
    public void parserRejectsMalformedXml() {
        // 准备规则解析器，输入节点不闭合的 XML。
        RuleParser parser = new RuleParser();

        // 解析失败通过空规则结果报告。
        assertNull(parser.ruleSettings("<flowControl><broken></flowControl>"));
    }

    @Test
    public void parserRejectsNullSettings() {
        // 准备规则解析器，输入 null 配置对象。
        RuleParser parser = new RuleParser();

        // 解析失败通过空规则结果报告。
        assertNull(parser.ruleSettings((Settings) null));
    }

    @Test
    public void parserNormalizesRuleTypeAndExposesMetadata() {
        // 类型名称带空格和大写字母。
        Rule rule = new RuleParser().ruleSettings("<flowControl type=' RANDOM ' enable='true'></flowControl>");

        // 返回规则包含归一化类型、启用状态及原始 XML。
        assertNotNull(rule);
        assertEquals("random", rule.routeID());
        assertTrue(rule.enable());
        assertTrue(rule.rawRoute().contains("flowControl"));
    }

    @Test
    public void parsingAnotherRuleDoesNotMutateEarlierRule() {
        // 保留第一次解析得到的已启用规则。
        RuleParser parser = new RuleParser();
        UnitFlowControl first = (UnitFlowControl) parser.ruleSettings(unit(true, 0.2, "192.0.2.*"));

        // 再解析另一配置，已发布规则的启用状态和阈值保持不变。
        UnitFlowControl second = (UnitFlowControl) parser.ruleSettings(unit(false, 0.9, "192.0.2.*"));
        assertFalse(second.enable());
        assertEquals(0.9, second.getThreshold(), 0.0001);
        assertTrue("Previously parsed rules must remain enabled", first.enable());
        assertEquals(0.2, first.getThreshold(), 0.0001);
    }

    @Test
    public void configuringAnotherServiceCannotDisableExistingUnitRule() {
        // 两个服务拥有同一组候选，但分别更新各自规则。
        AddressPool pool = new AddressPool("local", 30000);
        List<InterAddress> candidates = Arrays.asList(this.local, this.remote);
        pool.appendAddress("one", candidates);
        pool.appendAddress("two", candidates);
        String enabledRule = "<controlSet>" + unit(true, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("one", enabledRule);
        String disabledRule = "<controlSet>" + unit(false, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("two", disabledRule);
        assertEquals(candidates, pool.queryLocalUnitAddresses("two"));

        // 第二个服务禁用单元规则不能影响第一个服务。
        List<InterAddress> selected = pool.queryLocalUnitAddresses("one");
        assertEquals("Rules must be isolated per service", Collections.singletonList(this.local), selected);
    }

    @Test
    public void invalidFlowControlUpdateMustReportFailure() {
        // 准备已有服务，排除服务不存在这一失败原因。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.local);

        // 非 XML 更新应报告失败。
        assertFalse(pool.updateFlowControl("s", "not xml"));
    }

    @Test
    public void blankFlowControlUpdateMustReportFailure() {
        // 准备已有服务，排除服务不存在这一失败原因。
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.local);

        // 空配置更新应报告失败。
        assertFalse(pool.updateFlowControl("s", ""));
    }

    @Test
    public void randomSelectionHandlesNullCandidates() {
        // 准备随机选址规则和空引用候选。
        RandomFlowControl rule = new RandomFlowControl();

        // 检查该候选边界对应的选址结果。
        InterAddress selected = rule.getServiceAddress(null);
        assertNull(selected);
    }

    @Test
    public void randomSelectionHandlesEmptyCandidates() {
        // 准备随机选址规则和空候选集合。
        RandomFlowControl rule = new RandomFlowControl();

        // 检查该候选边界对应的选址结果。
        InterAddress selected = rule.getServiceAddress(Collections.emptyList());
        assertNull(selected);
    }

    @Test
    public void randomSelectionReturnsTheOnlyCandidate() {
        // 准备随机选址规则和仅包含一个地址的候选集合。
        RandomFlowControl rule = new RandomFlowControl();

        // 检查该候选边界对应的选址结果。
        InterAddress selected = rule.getServiceAddress(Collections.singletonList(this.local));
        assertEquals(this.local, selected);
    }

    @Test
    public void randomSelectionAlwaysReturnsACandidate() {
        // 准备两个可选地址，重复抽样检查返回范围。
        RandomFlowControl rule = new RandomFlowControl();
        List<InterAddress> candidates = Arrays.asList(this.local, this.remote);

        // 每次随机结果必须来自当前候选集合。
        for (int index = 0; index < 100; index++) {
            InterAddress selected = rule.getServiceAddress(candidates);
            assertTrue(candidates.contains(selected));
        }
    }

    // 只替换配额桶的令牌检查，让作用域与并发隔离测试不依赖时间推进。
    private static class OneTokenControl extends SpeedFlowControl {
        @Override
        protected QoSBucket createQoSBucket(String key) {
            return new QoSBucket(1, 1, 1) {
                private final AtomicBoolean token = new AtomicBoolean(true);

                @Override
                public boolean check() {
                    return this.token.compareAndSet(true, false);
                }
            };
        }
    }

    private SpeedFlowControl deterministicControl(String action) throws Exception {
        // 准备只含一个令牌的配置，用于验证配额分组。
        String xml = "<xml>" + speed(action, true, 1, 1, 1) + "</xml>";
        MergedSettings settings = new MergedSettings();
        settings.loadStringBody(xml, StreamType.Xml);

        // 保留真实规则解析，仅使用确定性的令牌桶。
        SpeedFlowControl rule = new OneTokenControl();
        rule.parseControl(settings);
        return rule;
    }

    @Test
    public void serviceLimitSharesQuotaAcrossMethodsButNotServices() throws Exception {
        // 服务配额先由一个方法消耗。
        SpeedFlowControl rule = this.deterministicControl("service");
        assertTrue(rule.enable());
        assertTrue(rule.callCheck("s", "one", this.local));

        // 同服务的其他方法共享配额，其他服务仍可调用。
        assertFalse(rule.callCheck("s", "two", this.remote));
        assertTrue(rule.callCheck("other", "one", this.local));
    }

    @Test
    public void methodLimitSharesQuotaAcrossAddresses() throws Exception {
        // 配额作用域为服务和方法，先在一个地址消耗唯一令牌。
        SpeedFlowControl rule = this.deterministicControl("method");
        assertTrue(rule.enable());
        assertTrue(rule.callCheck("s", "one", this.local));

        // 同服务同方法切换地址仍使用原配额。
        assertFalse(rule.callCheck("s", "one", this.remote));
    }

    @Test
    public void methodLimitIsolatedByMethod() throws Exception {
        // 消耗指定服务第一个方法的配额。
        SpeedFlowControl rule = this.deterministicControl("method");
        assertTrue(rule.enable());
        assertTrue(rule.callCheck("s", "one", this.local));
        assertFalse(rule.callCheck("s", "one", this.local));

        // 同服务的另一方法拥有独立配额。
        assertTrue(rule.callCheck("s", "two", this.local));
    }

    @Test
    public void methodLimitIsolatedByService() throws Exception {
        // 消耗指定服务的方法配额。
        SpeedFlowControl rule = this.deterministicControl("method");
        assertTrue(rule.enable());
        assertTrue(rule.callCheck("s", "one", this.local));
        assertFalse(rule.callCheck("s", "one", this.local));

        // 另一服务的同名方法拥有独立配额。
        assertTrue(rule.callCheck("other", "one", this.local));
    }

    @Test
    public void methodQuotaKeysCannotCollideByConcatenation() throws Exception {
        // 准备直接拼接会相同的两个服务和方法组合。
        SpeedFlowControl rule = this.deterministicControl("method");
        assertTrue(rule.enable());
        assertTrue(rule.callCheck("ab", "c", this.local));

        // 配额键必须区分二元组，不能让第二个组合被限流。
        assertTrue("Different service/method pairs need separate quotas", rule.callCheck("a", "bc", this.local));
    }

    @Test
    public void addressLimitSharesQuotaAcrossServicesAtSameAddress() throws Exception {
        // 按地址限流，先消耗本地地址的令牌。
        SpeedFlowControl rule = this.deterministicControl("address");
        assertTrue(rule.enable());
        assertTrue(rule.callCheck("s", "one", this.local));

        // 同地址的其他服务共享配额，另一地址保持可调用。
        assertFalse(rule.callCheck("other", "two", this.local));
        assertTrue(rule.callCheck("s", "one", this.remote));
    }

    @Test
    public void concurrentFirstCallsShareOneQuotaBucket() throws Exception {
        // 所有首调用竞争同一服务配额，等待统一放行信号。
        SpeedFlowControl rule = this.deterministicControl("service");
        assertTrue(rule.enable());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return rule.callCheck("s", "get", this.local);
                }));
            }

            // 同时发起调用，有界等待结果并统计唯一成功者。
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
        } finally {
            // 释放等待任务并回收线程，防止失败用例遗留并发资源。
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 10000)
    public void actualQosBucketRejectsAfterInitialTokensAreConsumed() {
        // 使用真实令牌桶，窗口内提供六十个初始令牌。
        String xml = speed("service", true, 1, 60, 60000);
        SpeedFlowControl rule = (SpeedFlowControl) new RuleParser().ruleSettings(xml);
        assertTrue(rule.enable());

        // 消耗完整初始额度，下一次同服务调用必须被拒绝。
        for (int i = 0; i < 60; i++) {
            assertTrue(rule.callCheck("s", "get", this.local));
        }
        assertFalse(rule.callCheck("s", "get", this.local));

        // 限流范围仅为当前服务，不占用另一服务的令牌。
        assertTrue(rule.callCheck("other", "get", this.local));
    }

    @Test
    public void defaultSpeedRuleDoesNotThrottle() {
        // 默认速率规则未启用流控。
        SpeedFlowControl rule = SpeedFlowControl.defaultControl();

        // 默认规则应放行调用。
        assertTrue(rule.callCheck("s", "get", this.local));
    }

    @Test
    public void disabledSpeedRuleDoesNotThrottle() {
        // 解析显式禁用的速率规则。
        String xml = speed("service", false, 1, 1, 1);
        SpeedFlowControl rule = (SpeedFlowControl) new RuleParser().ruleSettings(xml);

        // 规则保持禁用状态，调用不消耗速率配额。
        assertNotNull(rule);
        assertFalse(rule.enable());
        assertTrue(rule.callCheck("s", "get", this.local));
    }

    @Test
    public void enabledSpeedRuleRejectsInvalidRate() {
        // 启用速率规则，但将稳定速率设为非法的零。
        String xml = speed("service", true, 0, 1, 1);
        RuleParser parser = new RuleParser();

        // 参数错误应在配置解析时报告。
        assertNull(parser.ruleSettings(xml));
    }

    @Test
    public void enabledSpeedRuleRequiresAnAction() {
        // 启用速率规则，但没有选择配额作用域。
        String xml = speed("", true, 1, 1, 1);
        RuleParser parser = new RuleParser();

        // 未声明作用域的规则不能进入运行状态。
        assertNull(parser.ruleSettings(xml));
    }

    @Test
    public void compoundConfigurationAppliesAllRuleTypes() {
        // 准备同时包含单元、随机和速率规则的服务流控配置。
        String xml = """
                <controlSet>
                    %s
                    <flowControl type="random" enable="true"/>
                    %s
                </controlSet>
                """.formatted(unit(true, 0.5, "192.0.2.*"), speed("service", true, 1, 60, 60000));
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", List.of());

        // 通过地址池更新规则，检查整份配置实际发布的流控类型及参数。
        assertTrue(pool.updateFlowControl("s", xml));
        FlowControlRef ref = pool.getBucket("s").getFlowControlRef();
        assertEquals(xml, ref.flowControlScript);
        assertTrue(ref.unitFlowControl.enable());
        assertTrue(ref.randomFlowControl.enable());
        assertTrue(ref.speedFlowControl.enable());
        assertEquals(0.5, ref.unitFlowControl.getThreshold(), 0.0001);
    }

    @Test
    public void emptyControlSetRestoresDefaultRules() {
        // 先启用本单元筛选，确认当前配置已经生效。
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        String unitRule = "<controlSet>" + unit(true, 0.5, "") + "</controlSet>";
        assertTrue(pool.updateFlowControl("s", unitRule));
        assertEquals(Collections.singletonList(this.local), pool.queryLocalUnitAddresses("s"));

        // 空控制集恢复默认选址与速率规则。
        assertTrue(pool.updateFlowControl("s", "<controlSet/>"));
        assertEquals(Arrays.asList(this.local, this.remote), pool.queryLocalUnitAddresses("s"));
        assertFalse(pool.getBucket("s").getFlowControlRef().speedFlowControl.enable());
    }

    @Test
    public void invalidSecondRuleRollsBackTheWholeControlSet() {
        // 先发布一份有效的单元规则。
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        String original = "<controlSet>" + unit(true, 0.5, "") + "</controlSet>";
        assertTrue(pool.updateFlowControl("s", original));

        // 新控制集第二条规则非法，第一条也不得部分发布。
        String disabledUnit = unit(false, 0.5, "");
        String invalidSpeed = speed("service", true, 0, 1, 1);
        String invalidRules = "<controlSet>" + disabledUnit + invalidSpeed + "</controlSet>";
        assertFalse(pool.updateFlowControl("s", invalidRules));
        assertEquals(original, pool.flowControl("s"));
        assertEquals(Collections.singletonList(this.local), pool.queryLocalUnitAddresses("s"));
    }

    @Test
    public void failedUnitParsePreservesPreviouslyWorkingConfiguration() {
        // 先发布有效的单元筛选规则。
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        String valid = "<controlSet>" + unit(true, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("s", valid);

        // 阈值超出有效区间时，更新失败并保留原始配置与行为。
        String invalid = """
                <controlSet>
                    <flowControl type="unit" enable="true"><threshold>2.0</threshold></flowControl>
                </controlSet>
                """;
        assertFalse(pool.updateFlowControl("s", invalid));
        assertEquals(Collections.singletonList(this.local), pool.queryLocalUnitAddresses("s"));
        assertEquals(valid, pool.flowControl("s"));
    }

}
