package net.hasor.rsf.address;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/**
 * Loads a deterministic test provider through the actual ServiceLoader boundary.
 * It deliberately implements no Groovy syntax. The provider is visible only through
 * this test's context classloader, so production's no-provider path stays testable.
 */
public abstract class ScriptTestSupport {
    @Rule
    public final         TemporaryFolder temporary = new TemporaryFolder();
    private              ClassLoader     originalLoader;
    private static final String          SERVICE   = "META-INF/services/" + RuleScriptEngine.class.getName();

    @Before
    public void installScriptProvider() throws Exception {
        originalLoader = Thread.currentThread().getContextClassLoader();
        File descriptor = temporary.newFile("script-engine-service");
        Files.write(descriptor.toPath(), Collections.singletonList(TestEngine.class.getName()), StandardCharsets.UTF_8);
        URL url = descriptor.toURI().toURL();
        TestEngine.scripts.set(new HashMap<>());
        Thread.currentThread().setContextClassLoader(new ClassLoader(originalLoader) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return SERVICE.equals(name) ? Collections.enumeration(Collections.singletonList(url)) : super.getResources(name);
            }
        });
    }

    @After
    public void restoreScriptProvider() {
        Thread.currentThread().setContextClassLoader(originalLoader);
        TestEngine.scripts.remove();
    }

    protected String script(String name, RuleScript<?> script) {
        TestEngine.scripts.get().put(name, script);
        return name;
    }

    protected ClassLoader withoutScriptProvider() {
        return new ClassLoader(originalLoader) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return SERVICE.equals(name) ? Collections.emptyEnumeration() : super.getResources(name);
            }
        };
    }

    public static class TestEngine implements RuleScriptEngine {
        private static final ThreadLocal<Map<String, RuleScript<?>>> scripts = new ThreadLocal<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> RuleScript<T> eval(String source) {
            RuleScript<?> result = scripts.get().get(source);
            if (result == null) {
                throw new IllegalArgumentException("Unknown test script: " + source);
            }
            return (RuleScript<T>) result;
        }
    }
}
