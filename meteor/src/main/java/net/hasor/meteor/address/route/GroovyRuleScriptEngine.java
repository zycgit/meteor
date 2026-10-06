/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.route;
import javax.script.ScriptException;
import net.hasor.meteor.address.RuleScript;
import net.hasor.meteor.address.RuleScriptEngine;
import net.hasor.meteor.address.route.groovy223.GroovyScriptEngineImpl;

/** Supplies Groovy execution through rsf-address's optional script SPI. */
public final class GroovyRuleScriptEngine implements RuleScriptEngine {
    @Override
    @SuppressWarnings("unchecked")
    public <T> RuleScript<T> eval(String script) {
        GroovyScriptEngineImpl engine = new GroovyScriptEngineImpl();
        try {
            engine.eval(script);
            return engine.getInterface(RuleScript.class);
        } catch (ScriptException e) {
            throw new IllegalArgumentException("Cannot compile address route script", e);
        }
    }
}
