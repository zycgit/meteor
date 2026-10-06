/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.address.route.groovy223;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

/**
 * This class defines new Java 6 specific static groovy methods which extend the normal
 * JDK classes inside the Groovy environment.
 */
public class ScriptStaticExtensions {
    /**
     * Provides a convenient shorthand for accessing a Scripting Engine with name <code>languageShortName</code>
     * using a newly created <code>ScriptEngineManager</code> instance.
     *
     * @param self              Placeholder variable used by Groovy categories; ignored for default static methods
     * @param languageShortName The short name of the scripting engine of interest
     * @return the ScriptEngine corresponding to the supplied short name or null if no engine was found
     * @since 1.8.0
     */
    public static ScriptEngine $static_propertyMissing(ScriptEngineManager self, String languageShortName) {
        ScriptEngineManager manager = new ScriptEngineManager();
        return manager.getEngineByName(languageShortName);
    }
}
