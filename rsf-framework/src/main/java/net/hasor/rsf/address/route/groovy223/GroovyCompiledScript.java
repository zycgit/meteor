/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.address.route.groovy223;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 * Used to represent compiled Groovy scripts.  Such scripts may be executed repeatedly
 * by Groovy's <code>ScriptEngine</code> using the <code>eval</code> method without reparsing overheads.
 *
 * @author Adapted from original by Mike Grogan
 * @author Adapted from original by A. Sundararajan
 */
public class GroovyCompiledScript extends CompiledScript {
    private final GroovyScriptEngineImpl engine;
    private final Class                  clasz;

    public GroovyCompiledScript(GroovyScriptEngineImpl engine, Class clazz) {
        this.engine = engine;
        this.clasz = clazz;
    }

    public Object eval(ScriptContext context) throws ScriptException {
        return this.engine.eval(this.clasz, context);
    }

    public ScriptEngine getEngine() {
        return this.engine;
    }
}