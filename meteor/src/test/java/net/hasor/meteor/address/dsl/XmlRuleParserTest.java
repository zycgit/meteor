/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.dsl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.address.InterAddress;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRuleParserTest {
    private final InterAddress east = new InterAddress("rsf", "127.0.0.1", 2181, "east");
    private final InterAddress west = new InterAddress("rsf", "127.0.0.1", 2182, "west");

    @Test
    public void environmentMetadataAndActualBeanArgumentsSelectCandidates() {
        // 解析一次，环境和服务元信息决定默认值，调用参数可覆盖。
        String xml = """
                <route>
                    <bind name="unit" value="utils.defaultIfBlank(service.meta['unit'], env.unitName)"/>
                    <if test="utils.equals(method.name, 'query')">
                        <bind name="unit" value="utils.defaultIfBlank(args[0].unit, unit)"/>
                    </if>
                    <filter name="targets" from="addresses" item="address"
                            test="utils.equals(address.formUnit, unit)"/>
                    <if test="!targets.isEmpty()"><return value="targets"/></if>
                    <return value="addresses"/>
                </route>
                """;
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("service", "query", new Object[] { new Query("west") });
        input.getEnv().put("unitName", "east");
        input.getService().put("meta", Map.of("unit", "east"));
        List<InterAddress> addresses = List.of(this.east, this.west);
        assertEquals(List.of(this.west), rule.evaluate(input, addresses));

        // 同一解析结果读取本次输入，无需刷新地址本。
        input.getMethod().put("name", "other");
        assertEquals(List.of(this.east), rule.evaluate(input, addresses));
        input.getService().put("meta", Map.of("unit", "unknown"));
        assertEquals(addresses, rule.evaluate(input, addresses));
    }

    @Test
    public void filterRestoresVariableAndNestedReturnStopsFollowingSteps() {
        String xml = """
                <route>
                    <bind name="item" value="2182"/>
                    <filter name="first" from="addresses" item="item" test="item.port == 2181"/>
                    <if test="item == 2182"><return value="first"/></if>
                    <return value="null"/>
                </route>
                """;
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("s", "query", null);
        assertEquals(List.of(this.east), rule.evaluate(input, List.of(this.east, this.west)));
    }

    @Test
    public void arithmeticCollectionsAndUtilitiesKeepOrderWithoutDuplicates() {
        String xml = """
                <route>
                    <bind name="port" value="2180 + args[0] % 3"/>
                    <filter name="selected" from="addresses" item="a" test="a.port &gt;= port"/>
                    <return value="{selected[1], selected[0], selected[1]}"/>
                </route>
                """;
        RouteContext input = new RouteContext("s", "query", new Object[] { 1 });
        RouteRule rule = new XmlRuleParser().parse(xml);
        assertEquals(List.of(this.west, this.east), rule.evaluate(input, List.of(this.east, this.west)));
    }

    @Test
    public void emptyReturnIsDifferentFromMissingReturnOrWrongResult() {
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);
        RouteRule empty = new XmlRuleParser().parse("<route><return value='{}'/></route>");
        assertTrue(empty.evaluate(input, addresses).isEmpty());

        // 执行失败必须由调用方感知，不得还原成全部候选地址。
        for (String value : List.of("null", "42", "{'127.0.0.1:2181'}", "args[2]")) {
            RouteRule invalid = new XmlRuleParser().parse("<route><return value=\"" + value + "\"/></route>");
            assertThrows(IllegalArgumentException.class, () -> invalid.evaluate(input, addresses));
        }
        String noReturn = "<route><if test='false'><return value='addresses'/></if></route>";
        RouteRule missing = new XmlRuleParser().parse(noReturn);
        assertThrows(IllegalArgumentException.class, () -> missing.evaluate(input, addresses));
    }

    @Test
    public void invalidStructureAndExpressionsFailDuringUpdate() {
        List<String> invalid = new ArrayList<>();
        invalid.add("<route/>");
        invalid.add("<route><unknown/></route>");
        invalid.add("<route><return value='('/></route>");
        invalid.add("<route><bind name='env' value='1'/><return value='addresses'/></route>");
        invalid.add("<route><return values='addresses'/></route>");
        invalid.add("<route><return value='addresses'><bind name='x' value='1'/></return></route>");
        invalid.add("<route>text<return value='addresses'/></route>");
        invalid.add("<!DOCTYPE route [<!ENTITY x SYSTEM 'file:///etc/hostname'>]><route>&x;</route>");
        for (String xml : invalid) {
            assertThrows(xml, IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
        }
    }

    @Test
    public void expressionsCannotWriteInputsConstructObjectsOrInvokeStaticCode() {
        List<String> forbidden = new ArrayList<>();
        forbidden.add("args[0] = 2");
        forbidden.add("new java.util.ArrayList()");
        forbidden.add("@java.lang.System@getProperty('user.home')");
        forbidden.add("#root");
        forbidden.add("(1, 2)");
        for (String expression : forbidden) {
            String xml = "<route><return value=\"" + expression + "\"/></route>";
            assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
        }
        RouteContext input = new RouteContext("s", "query", new Object[] { new Query("east") });
        for (String expression : List.of("args[0].setUnit('west')", "args[0].getClass()", "addresses.clear()")) {
            String xml = "<route><bind name='x' value=\"" + expression + "\"/><return value='addresses'/></route>";
            RouteRule rule = new XmlRuleParser().parse(xml);
            assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, List.of(this.east)));
        }
        assertEquals("east", ((Query) input.getArgs()[0]).getUnit());
    }

    @Test
    public void conditionsAndFilterSourcesHaveExplicitTypes() {
        RouteContext input = new RouteContext("s", "query", null);
        List<String> invalid = new ArrayList<>();
        invalid.add("<if test='1'><return value='addresses'/></if>");
        invalid.add("<filter name='x' from='1' item='a' test='true'/>");
        invalid.add("<filter name='x' from='addresses' item='a' test='1'/>");
        for (String step : invalid) {
            RouteRule rule = new XmlRuleParser().parse("<route>" + step + "<return value='addresses'/></route>");
            assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, List.of(this.east)));
        }
    }

    @Test
    public void concurrentCallsShareOnlyParsedSteps() throws Exception {
        RouteRule rule = new XmlRuleParser().parse("""
                <route>
                    <bind name="port" value="args[0]"/>
                    <filter name="selected" from="addresses" item="a" test="a.port == port"/>
                    <return value="selected"/>
                </route>
                """);
        var executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<List<InterAddress>>> calls = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int port = index % 2 == 0 ? 2181 : 2182;
                calls.add(() -> {
                    RouteContext input = new RouteContext("s", "query", new Object[] { port });
                    return rule.evaluate(input, List.of(this.east, this.west));
                });
            }
            var results = executor.invokeAll(calls, 5, TimeUnit.SECONDS);
            for (int index = 0; index < results.size(); index++) {
                InterAddress expected = index % 2 == 0 ? this.east : this.west;
                assertEquals(List.of(expected), results.get(index).get());
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    public static final class Query {
        private String unit;

        public Query(String unit) {
            this.unit = unit;
        }

        public String getUnit() {
            return this.unit;
        }

        public void setUnit(String unit) {
            this.unit = unit;
        }
    }
}
