/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.hasor.cobble.concurrent.QoSBucket;
import net.hasor.cobble.setting.MergedSettings;
import net.hasor.cobble.setting.provider.StreamType;
import net.hasor.rsf.address.AddressPool;
import net.hasor.rsf.address.FlowControlRef;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.address.route.random.RandomFlowControl;
import net.hasor.rsf.address.route.speed.SpeedFlowControl;
import net.hasor.rsf.address.route.unit.UnitFlowControl;
import org.junit.Test;
import static org.junit.Assert.*;
import net.hasor.cobble.setting.Settings;

public class FlowControlTest {
    private final InterAddress local  = new InterAddress("127.0.0.1", 8000, "local");
    private final InterAddress remote = new InterAddress("127.0.0.2", 8000, "remote");

    public static String unit(boolean enabled, double threshold, String exclusions) {
        return "<flowControl type='unit' enable='" + enabled + "'><threshold>" + threshold + "</threshold><exclusions>" + exclusions + "</exclusions></flowControl>";
    }

    private static String speed(String action, boolean enabled, int rate, int peak, int window) {
        return "<flowControl type='speed' enable='" + enabled + "'><action>" + action + "</action><rate>" + rate + "</rate><peak>" + peak + "</peak><timeWindow>" + window + "</timeWindow></flowControl>";
    }

    @Test
    public void unitThresholdIncludesEqualityAndFallsBackBelowThreshold() {
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(unit(true, 0.5, "192.0.2.*"));
        assertTrue(rule.isLocalUnit(4, 2));
        assertFalse(rule.isLocalUnit(4, 1));
        assertFalse(rule.isLocalUnit(0, 0));
        assertNull(rule.siftUnitAddress("local", Collections.emptyList()));
        assertNull(rule.siftUnitAddress("local", null));
        assertEquals(Collections.singletonList(this.local), rule.siftUnitAddress("LOCAL", Arrays.asList(this.local, this.remote)));
    }

    @Test
    public void unitRuleAllowsAnEmptyExclusionList() {
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(unit(true, 0.5, ""));
        assertNotNull("No excluded providers is a valid unit configuration", rule);
        assertEquals(Collections.singletonList(this.local), rule.siftUnitAddress("local", Arrays.asList(this.local, this.remote)));
    }

    @Test
    public void excludedProviderIsIncludedAcrossUnits() {
        UnitFlowControl rule = (UnitFlowControl) new RuleParser().ruleSettings(unit(true, 0.5, "127.0.0.2"));
        assertEquals(Arrays.asList(this.local, this.remote), rule.siftUnitAddress("local", Arrays.asList(this.local, this.remote)));
    }

    @Test
    public void poolUsesLocalUnitAndFallsBackWhenItBecomesUnavailable() {
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        pool.updateFlowControl("s", "<controlSet>" + unit(true, 0.5, "192.0.2.*") + "</controlSet>");
        assertEquals(this.local, pool.nextAddress("s", "get", null));
        pool.invalidAddress(this.local);
        assertEquals(this.remote, pool.nextAddress("s", "get", null));
    }

    @Test
    public void disabledUnitRuleKeepsAllProviders() {
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        pool.updateFlowControl("s", "<controlSet>" + unit(false, 0.5, "192.0.2.*") + "</controlSet>");
        assertEquals(Arrays.asList(this.local, this.remote), pool.queryLocalUnitAddresses("s"));
    }

    @Test
    public void parserRejectsBlankMalformedAndUnknownRules() {
        RuleParser parser = new RuleParser();
        assertNull(parser.ruleSettings((String) null));
        assertNull(parser.ruleSettings(""));
        assertNull(parser.ruleSettings("not xml"));
        assertNull(parser.ruleSettings("<flowControl type='unknown'></flowControl>"));
        assertNull(parser.ruleSettings("<flowControl><broken></flowControl>"));
        assertNull(parser.ruleSettings((Settings) null));
    }

    @Test
    public void parserNormalizesRuleTypeAndExposesMetadata() {
        Rule rule = new RuleParser().ruleSettings("<flowControl type=' RANDOM ' enable='true'></flowControl>");
        assertNotNull(rule);
        assertEquals("random", rule.routeID());
        assertTrue(rule.enable());
        assertTrue(rule.rawRoute().contains("flowControl"));
    }

    @Test
    public void parsingAnotherRuleDoesNotMutateEarlierRule() {
        RuleParser parser = new RuleParser();
        UnitFlowControl first = (UnitFlowControl) parser.ruleSettings(unit(true, 0.2, "192.0.2.*"));
        parser.ruleSettings(unit(false, 0.9, "192.0.2.*"));
        assertTrue("Previously parsed rules must remain enabled", first.enable());
        assertEquals(0.2, first.getThreshold(), 0.0001);
    }

    @Test
    public void configuringAnotherServiceCannotDisableExistingUnitRule() {
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("one", Arrays.asList(this.local, this.remote));
        pool.appendAddress("two", Arrays.asList(this.local, this.remote));
        pool.updateFlowControl("one", "<controlSet>" + unit(true, 0.5, "192.0.2.*") + "</controlSet>");
        pool.updateFlowControl("two", "<controlSet>" + unit(false, 0.5, "192.0.2.*") + "</controlSet>");
        assertEquals("Rule state must be isolated per service", Collections.singletonList(this.local), pool.queryLocalUnitAddresses("one"));
    }

    @Test
    public void invalidFlowControlUpdateMustReportFailure() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.local);
        assertFalse(pool.updateFlowControl("s", "not xml"));
    }

    @Test
    public void blankFlowControlUpdateMustReportFailure() {
        AddressPool pool = new AddressPool();
        pool.appendAddress("s", this.local);
        assertFalse(pool.updateFlowControl("s", ""));
    }

    @Test
    public void randomSelectionHandlesEmptySingleAndMultipleCandidates() {
        RandomFlowControl rule = new RandomFlowControl();
        assertNull(rule.getServiceAddress(null));
        assertNull(rule.getServiceAddress(Collections.emptyList()));
        assertEquals(this.local, rule.getServiceAddress(Collections.singletonList(this.local)));
        List<InterAddress> candidates = Arrays.asList(this.local, this.remote);
        for (int i = 0; i < 100; i++) {
            assertTrue(candidates.contains(rule.getServiceAddress(candidates)));
        }
    }

    // Tests quota grouping with a deterministic bucket; real QoSBucket is exercised separately.
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
        MergedSettings settings = new MergedSettings();
        settings.loadStringBody("<xml>" + speed(action, true, 1, 1, 1) + "</xml>", StreamType.Xml);
        SpeedFlowControl rule = new OneTokenControl();
        rule.parseControl(settings);
        assertTrue(rule.enable());
        return rule;
    }

    @Test
    public void serviceLimitSharesQuotaAcrossMethodsButNotServices() throws Exception {
        SpeedFlowControl rule = deterministicControl("service");
        assertTrue(rule.callCheck("s", "one", this.local));
        assertFalse(rule.callCheck("s", "two", this.remote));
        assertTrue(rule.callCheck("other", "one", this.local));
    }

    @Test
    public void methodLimitIsolatedByServiceAndMethod() throws Exception {
        SpeedFlowControl rule = deterministicControl("method");
        assertTrue(rule.callCheck("s", "one", this.local));
        assertFalse(rule.callCheck("s", "one", this.remote));
        assertTrue(rule.callCheck("s", "two", this.local));
        assertTrue(rule.callCheck("other", "one", this.local));
    }

    @Test
    public void methodQuotaKeysCannotCollideByConcatenation() throws Exception {
        SpeedFlowControl rule = deterministicControl("method");
        assertTrue(rule.callCheck("ab", "c", this.local));
        assertTrue("Different service/method pairs need separate quotas", rule.callCheck("a", "bc", this.local));
    }

    @Test
    public void addressLimitSharesQuotaAcrossServicesAtSameAddress() throws Exception {
        SpeedFlowControl rule = deterministicControl("address");
        assertTrue(rule.callCheck("s", "one", this.local));
        assertFalse(rule.callCheck("other", "two", this.local));
        assertTrue(rule.callCheck("s", "one", this.remote));
    }

    @Test
    public void concurrentFirstCallsShareOneQuotaBucket() throws Exception {
        SpeedFlowControl rule = deterministicControl("service");
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
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) {
                    accepted++;
                }
            }
            assertEquals(1, accepted);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 10000)
    public void actualQosBucketRejectsAfterInitialTokensAreConsumed() {
        SpeedFlowControl rule = (SpeedFlowControl) new RuleParser().ruleSettings(speed("service", true, 1, 60, 60000));
        assertTrue(rule.enable());
        for (int i = 0; i < 60; i++) {
            assertTrue(rule.callCheck("s", "get", this.local));
        }
        assertFalse(rule.callCheck("s", "get", this.local));
        assertTrue(rule.callCheck("other", "get", this.local));
    }

    @Test
    public void disabledRulesDoNotThrottleAndInvalidEnabledRulesAreRejected() {
        assertTrue(SpeedFlowControl.defaultControl().callCheck("s", "get", this.local));
        RuleParser parser = new RuleParser();
        SpeedFlowControl disabled = (SpeedFlowControl) parser.ruleSettings(speed("service", false, 1, 1, 1));
        assertNotNull(disabled);
        assertFalse(disabled.enable());
        assertTrue(disabled.callCheck("s", "get", this.local));
        assertNull(parser.ruleSettings(speed("service", true, 0, 1, 1)));
        assertNull(parser.ruleSettings(speed("", true, 1, 1, 1)));
    }

    @Test
    public void defaultArgumentKeysHandleNullEmptyAndOrdinaryValues() {
        DefaultArgsKey key = new DefaultArgsKey();
        assertEquals("null", key.eval("s", "get", null));
        assertEquals("", key.eval("s", "get", new Object[0]));
        assertEquals("hello-2-null-", key.eval("s", "get", new Object[] { "hello", 2, null }));
    }

    @Test
    public void compoundConfigurationAppliesAllRuleTypes() {
        FlowControlRef ref = FlowControlRef.defaultRef();
        String xml = "<controlSet>" + unit(true, 0.5, "192.0.2.*") + "<flowControl type='random' enable='true'></flowControl>" + speed("service", true, 1, 60, 60000) + "</controlSet>";
        ref.updateFlowControl(xml);
        assertEquals(xml, ref.flowControlScript);
        assertTrue(ref.unitFlowControl.enable());
        assertTrue(ref.randomFlowControl.enable());
        assertTrue(ref.speedFlowControl.enable());
        FlowControlRef copy = FlowControlRef.newRef(ref);
        assertNotSame(ref, copy);
        assertEquals(xml, copy.flowControlScript);
        assertEquals(0.5, copy.unitFlowControl.getThreshold(), 0.0001);
    }

    @Test
    public void emptyControlSetRestoresDefaultRules() {
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        assertTrue(pool.updateFlowControl("s", "<controlSet>" + unit(true, 0.5, "") + "</controlSet>"));
        assertEquals(Collections.singletonList(this.local), pool.queryLocalUnitAddresses("s"));
        assertTrue(pool.updateFlowControl("s", "<controlSet/>"));
        assertEquals(Arrays.asList(this.local, this.remote), pool.queryLocalUnitAddresses("s"));
        assertFalse(pool.getBucket("s").getFlowControlRef().speedFlowControl.enable());
    }

    @Test
    public void invalidSecondRuleRollsBackTheWholeControlSet() {
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        String original = "<controlSet>" + unit(true, 0.5, "") + "</controlSet>";
        assertTrue(pool.updateFlowControl("s", original));
        assertFalse(pool.updateFlowControl("s", "<controlSet>" + unit(false, 0.5, "") + speed("service", true, 0, 1, 1) + "</controlSet>"));
        assertEquals(original, pool.flowControl("s"));
        assertEquals(Collections.singletonList(this.local), pool.queryLocalUnitAddresses("s"));
    }

    @Test
    public void argumentEscapingPreservesOrdinaryKeysAndSeparatesLiteralEscapes() {
        DefaultArgsKey key = new DefaultArgsKey();
        assertEquals("vip-", key.eval("s", "get", new Object[] { "vip" }));
        assertNotEquals(key.eval("s", "get", new Object[] { "a-b" }), key.eval("s", "get", new Object[] { "a\\-b" }));
        assertNotEquals(key.eval("s", "get", new Object[] { "null" }), key.eval("s", "get", new Object[] { "\\null" }));
    }

    @Test
    public void failedUnitParsePreservesPreviouslyWorkingConfiguration() {
        AddressPool pool = new AddressPool("local", 30000);
        pool.appendAddress("s", Arrays.asList(this.local, this.remote));
        String valid = "<controlSet>" + unit(true, 0.5, "192.0.2.*") + "</controlSet>";
        pool.updateFlowControl("s", valid);
        // An out-of-range threshold must fail without altering the previous rule.
        pool.updateFlowControl("s", "<controlSet><flowControl type='unit' enable='true'>" + "<threshold>2.0</threshold></flowControl></controlSet>");
        assertEquals(Collections.singletonList(this.local), pool.queryLocalUnitAddresses("s"));
        assertEquals(valid, pool.flowControl("s"));
    }

    @Test
    public void argumentKeySeparatesEmbeddedDelimiters() {
        DefaultArgsKey key = new DefaultArgsKey();
        assertNotEquals("Distinct argument tuples must not route as the same tuple", key.eval("s", "get", new Object[] { "a-b", "c" }), key.eval("s", "get", new Object[] { "a", "b-c" }));
    }

    @Test
    public void argumentKeySeparatesNullFromLiteralNull() {
        DefaultArgsKey key = new DefaultArgsKey();
        assertNotEquals(key.eval("s", "get", new Object[] { null }), key.eval("s", "get", new Object[] { "null" }));
    }
}
