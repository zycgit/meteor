/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address;
import java.util.List;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.logging.Logger;
import net.hasor.cobble.logging.LoggerFactory;
import net.hasor.meteor.address.dsl.RouteContext;
import net.hasor.meteor.address.dsl.RouteRule;
import net.hasor.meteor.address.dsl.XmlRuleParser;

/** 随 RuleRef 发布的规则正文和解析结果，更新失败保留旧规则。 */
class InnerRuleEngine {
    private static final Logger    logger = LoggerFactory.getLogger(InnerRuleEngine.class);
    private              String    ruleScript;
    private              RouteRule rule;

    InnerRuleEngine() {
    }

    InnerRuleEngine(InnerRuleEngine source) {
        this.ruleScript = source.ruleScript;
        this.rule = source.rule;
    }

    public boolean isEnable() {
        return this.rule != null;
    }

    public boolean update(String script) {
        if (StringUtils.isBlank(script)) {
            script = null;
        }

        if (StringUtils.equals(script, this.ruleScript)) {
            return false;
        }

        try {
            RouteRule compiled = script == null ? null : new XmlRuleParser().parse(script);
            this.rule = compiled;
            this.ruleScript = script;
            return true;
        } catch (IllegalArgumentException failure) {
            logger.error("Cannot parse XML route", failure);
            return false;
        }
    }

    public String getScript() {
        return this.ruleScript;
    }

    List<InterAddress> evaluate(RouteContext input, List<InterAddress> candidates) {
        return this.rule.evaluate(input, candidates);
    }
}