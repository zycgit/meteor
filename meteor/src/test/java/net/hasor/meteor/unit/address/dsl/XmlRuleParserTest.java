/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.address.dsl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.hasor.meteor.address.InterAddress;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.dsl.RouteRule;
import net.hasor.meteor.address.dsl.XmlRuleParser;
import org.junit.Test;
import static org.junit.Assert.*;

public class XmlRuleParserTest {
    private final        InterAddress east       = new InterAddress("rsf", "127.0.0.1", 2181, "east");
    private final        InterAddress west       = new InterAddress("rsf", "127.0.0.1", 2182, "west");
    private static final String       UNIT_ROUTE = """
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

    @Test
    public void actualBeanArgumentsOverrideServiceMetadata() {
        // 准备同时包含环境、服务元信息和实际调用参数的路由输入。
        RouteRule rule = new XmlRuleParser().parse(UNIT_ROUTE);
        RouteContext input = new RouteContext("service", "query", new Object[] { new Query("west") });
        input.getEnv().put("unitName", "east");
        input.getService().put("meta", Map.of("unit", "east"));
        List<InterAddress> addresses = List.of(this.east, this.west);

        // query 方法的 Bean 参数覆盖服务单元，选出西区地址。
        List<InterAddress> selected = rule.evaluate(input, addresses);
        assertEquals(List.of(this.west), selected);
    }

    @Test
    public void parsedRuleReadsChangedMethodNameOnEachEvaluation() {
        // 先以 query 方法解析并执行规则，让调用参数覆盖服务单元。
        RouteRule rule = new XmlRuleParser().parse(UNIT_ROUTE);
        RouteContext input = new RouteContext("service", "query", new Object[] { new Query("west") });
        input.getEnv().put("unitName", "east");
        input.getService().put("meta", Map.of("unit", "east"));
        List<InterAddress> addresses = List.of(this.east, this.west);
        assertEquals(List.of(this.west), rule.evaluate(input, addresses));

        // 复用同一解析结果，新的方法名应跳过参数覆盖并读取服务单元。
        input.getMethod().put("name", "other");
        assertEquals(List.of(this.east), rule.evaluate(input, addresses));
    }

    @Test
    public void changedMetadataWithoutMatchesFallsBackToAllCandidates() {
        // 先按服务元信息选出东区地址，保留同一个解析结果和输入。
        RouteRule rule = new XmlRuleParser().parse(UNIT_ROUTE);
        RouteContext input = new RouteContext("service", "other", new Object[] { new Query("west") });
        input.getEnv().put("unitName", "east");
        input.getService().put("meta", Map.of("unit", "east"));
        List<InterAddress> addresses = List.of(this.east, this.west);
        assertEquals(List.of(this.east), rule.evaluate(input, addresses));

        // 元信息更新为无匹配单元后，再次执行应返回原有候选地址。
        input.getService().put("meta", Map.of("unit", "unknown"));
        assertEquals(addresses, rule.evaluate(input, addresses));
    }

    @Test
    public void filterRestoresItsIterationVariable() {
        // 过滤步骤使用已有变量名作为迭代变量。
        String xml = """
                <route>
                    <bind name="item" value="2182"/>
                    <filter name="first" from="addresses" item="item" test="item.port == 2181"/>
                    <if test="item == 2182"><return value="first"/></if>
                    <return value="{}"/>
                </route>
                """;
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east, this.west);

        // 后续条件应读到过滤前的变量值。
        assertEquals(List.of(this.east), rule.evaluate(input, addresses));
    }

    @Test
    public void nestedReturnStopsFollowingSteps() {
        // 在条件分支返回后放置一个会求值失败的步骤。
        String xml = """
                <route>
                    <if test="true"><return value="addresses"/></if>
                    <return value="null"/>
                </route>
                """;
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east, this.west);

        // 嵌套返回结束整条规则，不继续执行后面的无效返回。
        assertEquals(addresses, rule.evaluate(input, addresses));
    }

    @Test
    public void arithmeticCollectionsKeepOrderWithoutDuplicates() {
        // 使用算术结果过滤候选，并在返回集合中显式改变顺序和重复地址。
        String xml = """
                <route>
                    <bind name="port" value="2180 + args[0] % 3"/>
                    <filter name="selected" from="addresses" item="a" test="a.port &gt;= port"/>
                    <return value="{selected[1], selected[0], selected[1]}"/>
                </route>
                """;
        RouteContext input = new RouteContext("s", "query", new Object[] { 1 });
        RouteRule rule = new XmlRuleParser().parse(xml);
        List<InterAddress> addresses = List.of(this.east, this.west);

        // 返回值保持规则指定的顺序，同时去除重复地址。
        assertEquals(List.of(this.west, this.east), rule.evaluate(input, addresses));
    }

    @Test
    public void emptyReturnSelectsNoCandidates() {
        // 准备显式返回空集合的规则。
        RouteRule rule = new XmlRuleParser().parse("<route><return value='{}'/></route>");
        RouteContext input = new RouteContext("s", "query", null);

        // 空集合是合法路由结果，不会回退到原有地址。
        assertTrue(rule.evaluate(input, List.of(this.east)).isEmpty());
    }

    @Test
    public void nullReturnFailsDuringEvaluation() {
        // 准备可以解析、但执行结果不合法的路由。
        RouteRule rule = new XmlRuleParser().parse("<route><return value=\"null\"/></route>");
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 返回 null 的规则不能伪装成空地址集合。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void scalarReturnFailsDuringEvaluation() {
        // 准备可以解析、但执行结果不合法的路由。
        RouteRule rule = new XmlRuleParser().parse("<route><return value=\"42\"/></route>");
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 标量返回值不符合候选地址集合契约。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void nonAddressReturnFailsDuringEvaluation() {
        // 准备可以解析、但执行结果不合法的路由。
        RouteRule rule = new XmlRuleParser().parse("<route><return value=\"{'127.0.0.1:2181'}\"/></route>");
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 字符串集合不能作为地址对象集合返回。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void missingArgumentFailsDuringEvaluation() {
        // 准备可以解析、但执行结果不合法的路由。
        RouteRule rule = new XmlRuleParser().parse("<route><return value=\"args[2]\"/></route>");
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 访问不存在的调用参数应向调用方报告失败。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void unreachedReturnFailsDuringEvaluation() {
        // 规则中存在返回节点，但本次调用不会进入它所在的分支。
        String xml = "<route><if test='false'><return value='addresses'/></if></route>";
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 没有执行到返回节点时，调用方应感知失败。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void routeWithoutReturnIsRejected() {
        // 准备没有返回节点的 XML。
        String xml = "<route/>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void unknownStepIsRejected() {
        // 准备未知步骤节点的 XML。
        String xml = "<route><unknown/></route>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void malformedExpressionIsRejected() {
        // 准备表达式语法损坏的 XML。
        String xml = "<route><return value='('/></route>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void bindingReservedInputIsRejected() {
        // 准备覆盖框架保留变量的 XML。
        String xml = "<route><bind name='env' value='1'/><return value='addresses'/></route>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void unknownReturnAttributeIsRejected() {
        // 准备返回节点含未知属性的 XML。
        String xml = "<route><return values='addresses'/></route>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void nestedStepInsideReturnIsRejected() {
        // 准备返回节点嵌套其他步骤的 XML。
        String xml = "<route><return value='addresses'><bind name='x' value='1'/></return></route>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void textOutsideStepsIsRejected() {
        // 准备步骤外含有文本的 XML。
        String xml = "<route>text<return value='addresses'/></route>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void externalEntityDeclarationIsRejected() {
        // 准备声明外部实体的 XML。
        String xml = "<!DOCTYPE route [<!ENTITY x SYSTEM 'file:///etc/hostname'>]><route>&x;</route>";

        // 更新阶段直接拒绝该结构，不留到调用时再失败。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void inputAssignmentIsRejectedDuringParsing() {
        // 准备尝试修改输入参数的表达式。
        String xml = "<route><return value=\"args[0] = 2\"/></route>";

        // 表达式解析阶段拒绝不允许的语法。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void objectConstructionIsRejectedDuringParsing() {
        // 准备尝试构造任意对象的表达式。
        String xml = "<route><return value=\"new java.util.ArrayList()\"/></route>";

        // 表达式解析阶段拒绝不允许的语法。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void staticInvocationIsRejectedDuringParsing() {
        // 准备尝试调用静态代码的表达式。
        String xml = "<route><return value=\"@java.lang.System@getProperty('user.home')\"/></route>";

        // 表达式解析阶段拒绝不允许的语法。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void rootVariableAccessIsRejectedDuringParsing() {
        // 准备尝试读取表达式根变量的表达式。
        String xml = "<route><return value=\"#root\"/></route>";

        // 表达式解析阶段拒绝不允许的语法。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void sequenceExpressionIsRejectedDuringParsing() {
        // 准备尝试执行逗号序列表达式的表达式。
        String xml = "<route><return value=\"(1, 2)\"/></route>";

        // 表达式解析阶段拒绝不允许的语法。
        assertThrows(IllegalArgumentException.class, () -> new XmlRuleParser().parse(xml));
    }

    @Test
    public void beanSetterInvocationIsRejectedWithoutMutatingInput() {
        // 准备尝试调用输入 Bean 的 setter的规则。
        String xml = "<route><bind name=\"x\" value=\"args[0].setUnit('west')\"/><return value=\"addresses\"/></route>";
        RouteRule rule = new XmlRuleParser().parse(xml);
        Query query = new Query("east");
        RouteContext input = new RouteContext("s", "query", new Object[] { query });
        List<InterAddress> addresses = new ArrayList<>(List.of(this.east));

        // 执行阶段拒绝越界操作，并保持调用输入不变。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
        assertEquals("east", query.getUnit());
        assertEquals(List.of(this.east), addresses);
    }

    @Test
    public void classInspectionIsRejectedDuringEvaluation() {
        // 准备尝试读取输入对象的 Class的规则。
        String xml = "<route><bind name=\"x\" value=\"args[0].getClass()\"/><return value=\"addresses\"/></route>";
        RouteRule rule = new XmlRuleParser().parse(xml);
        Query query = new Query("east");
        RouteContext input = new RouteContext("s", "query", new Object[] { query });
        List<InterAddress> addresses = new ArrayList<>(List.of(this.east));

        // 执行阶段拒绝越界操作，并保持调用输入不变。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
        assertEquals("east", query.getUnit());
        assertEquals(List.of(this.east), addresses);
    }

    @Test
    public void candidateMutationIsRejectedDuringEvaluation() {
        // 准备尝试修改候选地址集合的规则。
        String xml = "<route><bind name=\"x\" value=\"addresses.clear()\"/><return value=\"addresses\"/></route>";
        RouteRule rule = new XmlRuleParser().parse(xml);
        Query query = new Query("east");
        RouteContext input = new RouteContext("s", "query", new Object[] { query });
        List<InterAddress> addresses = new ArrayList<>(List.of(this.east));

        // 执行阶段拒绝越界操作，并保持调用输入不变。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
        assertEquals("east", query.getUnit());
        assertEquals(List.of(this.east), addresses);
    }

    @Test
    public void nonBooleanConditionIsRejected() {
        // 准备条件不是布尔值的规则。
        String step = "<if test='1'><return value='addresses'/></if>";
        String xml = "<route>" + step + "<return value='addresses'/></route>";
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 执行时严格检查类型，错误不转换为默认路由结果。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void nonCollectionFilterSourceIsRejected() {
        // 准备过滤来源不是集合的规则。
        String step = "<filter name='x' from='1' item='a' test='true'/>";
        String xml = "<route>" + step + "<return value='addresses'/></route>";
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 执行时严格检查类型，错误不转换为默认路由结果。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void nonBooleanFilterPredicateIsRejected() {
        // 准备过滤条件不是布尔值的规则。
        String step = "<filter name='x' from='addresses' item='a' test='1'/>";
        String xml = "<route>" + step + "<return value='addresses'/></route>";
        RouteRule rule = new XmlRuleParser().parse(xml);
        RouteContext input = new RouteContext("s", "query", null);
        List<InterAddress> addresses = List.of(this.east);

        // 执行时严格检查类型，错误不转换为默认路由结果。
        assertThrows(IllegalArgumentException.class, () -> rule.evaluate(input, addresses));
    }

    @Test
    public void concurrentCallsShareOnlyParsedSteps() throws Exception {
        // 所有任务共享解析结果，各自携带独立端口参数。
        RouteRule rule = new XmlRuleParser().parse("""
                <route>
                    <bind name="port" value="args[0]"/>
                    <filter name="selected" from="addresses" item="a" test="a.port == port"/>
                    <return value="selected"/>
                </route>
                """);
        List<InterAddress> addresses = List.of(this.east, this.west);
        var executor = Executors.newFixedThreadPool(4);
        try {
            // 生成同一并发隔离场景的负载，交替选择两个目标地址。
            List<Callable<List<InterAddress>>> calls = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                int port = index % 2 == 0 ? 2181 : 2182;
                calls.add(() -> {
                    RouteContext input = new RouteContext("s", "query", new Object[] { port });
                    return rule.evaluate(input, addresses);
                });
            }

            // 有界等待全部结果，各次求值不能串用其他调用的变量。
            var results = executor.invokeAll(calls, 5, TimeUnit.SECONDS);
            for (int index = 0; index < results.size(); index++) {
                InterAddress expected = index % 2 == 0 ? this.east : this.west;
                List<InterAddress> selected = results.get(index).get(5, TimeUnit.SECONDS);
                assertEquals(List.of(expected), selected);
            }
        } finally {
            // 即使任务失败也回收线程，防止影响后续用例。
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
