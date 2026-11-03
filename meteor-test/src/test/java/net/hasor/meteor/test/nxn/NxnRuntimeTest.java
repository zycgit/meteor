package net.hasor.meteor.test.nxn;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedClass;
import static org.junit.jupiter.api.Assertions.*;

public class NxnRuntimeTest {
    @TempDir
    public Path temporary;

    @Test
    public void exportsOnlyTopLevelTestClassesAndPreservesRuntimeInputs() throws Exception {
        // 编译乱序顶层场景、普通类型与嵌套 fixture，发现范围只来自当前测试输出。
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("fixture/ZebraTest.java", """
                package fixture;
                import org.junit.jupiter.api.Test;
                public class ZebraTest { @Test void callsPeer() {} }
                """);
        sources.put("fixture/AlphaTest.java", """
                package fixture;
                import org.junit.jupiter.api.Test;
                public class AlphaTest { @Test void callsPeer() {} }
                """);
        sources.put("fixture/OuterFixture.java", """
                package fixture;
                import org.junit.jupiter.api.Test;
                public class OuterFixture {
                    public static class Nested { @Test void fixtureOnly() {} }
                }
                """);
        sources.put("fixture/Plain.java", "package fixture; public class Plain {}");
        Path classes = compile(sources);
        Path seed = seed(classes);
        Path output = this.temporary.resolve("runtime.json");

        // 借用测试运行图加载声明，导出命令只负责写清单。
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(0, NxnMain.run(new String[] { "export", seed.toString(), output.toString() }));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        // 输出稳定排序，独立运行图及启动参数原样保留，不再导出无消费者的字段。
        Map<String, Object> runtime = NxnPlan.readObject(output);
        assertEquals(List.of("fixture.AlphaTest", "fixture.ZebraTest"), runtime.get("classNames"));
        assertEquals(Map.of(), runtime.get("rpcCases"));
        assertFalse(runtime.containsKey("classDirectories"));
        assertFalse(runtime.containsKey("sourceRoots"));
        assertFalse(runtime.containsKey("classMethods"));
        Map<String, Object> expected = NxnPlan.readObject(seed);
        expected.remove("classDirectories");
        runtime.remove("classNames");
        runtime.remove("rpcCases");
        assertEquals(expected, runtime);
    }

    @Test
    public void exportsRepeatedRpcDeclarationsFromTheClass() throws Exception {
        // 一个测试类可声明多个协议，未覆盖的方法继承完整声明和参数筛选范围。
        Path classes = compile(Map.of("fixture/ClassProtocolsTest.java", """
                package fixture;
                import net.hasor.meteor.test.nxn.NxnRpc;
                import org.junit.jupiter.api.Test;
                @NxnRpc(name = "rsf", version = "1", cases = "direct*")
                @NxnRpc(name = "jsonrpc", version = "2")
                public class ClassProtocolsTest { @Test void callsPeer() {} }
                """));
        Path seed = seed(classes);
        Path output = this.temporary.resolve("runtime.json");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            NxnMain.run(new String[] { "export", seed.toString(), output.toString() });
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        // 可重复注解保持声明顺序，缺省版本与参数表达式由注解自身提供。
        Map<String, Object> runtime = NxnPlan.readObject(output);
        List<Map<String, String>> declarations = new ArrayList<>();
        declarations.add(Map.of("name", "rsf", "version", "1", "cases", "direct*"));
        declarations.add(Map.of("name", "jsonrpc", "version", "2", "cases", "*"));
        Map<String, Object> expected = Map.of("fixture.ClassProtocolsTest", Map.of("callsPeer", declarations));
        assertEquals(expected, runtime.get("rpcCases"));
    }

    @Test
    public void methodRpcDeclarationsReplaceClassDeclarations() throws Exception {
        // 方法上的多个协议声明替代类声明，不能把类协议再次拼入。
        Path classes = compile(Map.of("fixture/MethodProtocolsTest.java", """
                package fixture;
                import net.hasor.meteor.test.nxn.NxnRpc;
                import org.junit.jupiter.api.Test;
                @NxnRpc(name = "rsf", version = "1")
                public class MethodProtocolsTest {
                    @Test
                    @NxnRpc(name = "grpc", version = "1", cases = "stream*")
                    @NxnRpc(name = "hessian")
                    void callsPeer() {}
                }
                """));
        Path seed = seed(classes);
        Path output = this.temporary.resolve("runtime.json");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            NxnMain.run(new String[] { "export", seed.toString(), output.toString() });
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        // 方法声明中的版本、参数范围与缺省值全部保留。
        Map<String, Object> runtime = NxnPlan.readObject(output);
        List<Map<String, String>> declarations = new ArrayList<>();
        declarations.add(Map.of("name", "grpc", "version", "1", "cases", "stream*"));
        declarations.add(Map.of("name", "hessian", "version", "", "cases", "*"));
        Map<String, Object> expected = Map.of("fixture.MethodProtocolsTest", Map.of("callsPeer", declarations));
        assertEquals(expected, runtime.get("rpcCases"));
    }

    @Test
    public void exportsTestDeclarationsWithoutInitializingTheClass() throws Exception {
        // 静态初始化和构造器一旦运行就失败，读取声明不能进入测试生命周期。
        Path classes = compile(Map.of("fixture/InitializationTest.java", """
                package fixture;
                import org.junit.jupiter.api.Test;
                public class InitializationTest {
                    static { fail(); }
                    public InitializationTest() { throw new AssertionError("constructor ran"); }
                    private static void fail() { throw new AssertionError("class initialized"); }
                    @Test void callsPeer() { throw new AssertionError("test ran"); }
                }
                """));
        Path seed = seed(classes);
        Path output = this.temporary.resolve("runtime.json");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(0, NxnMain.run(new String[] { "export", seed.toString(), output.toString() }));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        // 危险类型仍进入清单，不能通过漏掉场景来规避初始化问题。
        assertEquals(List.of("fixture.InitializationTest"), NxnPlan.readObject(output).get("classNames"));
    }

    @Test
    public void exportsParameterizedClassWithoutInvokingItsFactory() throws Exception {
        // 使用真实 Jupiter 参数类注解，参数工厂只有执行期才允许调用。
        Path classes = compile(Map.of("fixture/ParameterizedScenarioTest.java", """
                package fixture;
                import java.util.stream.Stream;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.params.ParameterizedClass;
                import org.junit.jupiter.params.provider.MethodSource;
                @ParameterizedClass
                @MethodSource("parameters")
                public class ParameterizedScenarioTest {
                    public ParameterizedScenarioTest(String value) {}
                    static Stream<String> parameters() { throw new AssertionError("factory ran"); }
                    @Test void callsPeer() { throw new AssertionError("test ran"); }
                }
                """));
        Path seed = seed(classes);
        Path output = this.temporary.resolve("runtime.json");

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, previous)) {
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(0, NxnMain.run(new String[] { "export", seed.toString(), output.toString() }));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }

        // 参数类保留为单个静态入口，没有提前展开参数向量。
        Map<String, Object> runtime = NxnPlan.readObject(output);
        assertEquals(List.of("fixture.ParameterizedScenarioTest"), runtime.get("classNames"));
    }

    @Test
    public void emptyCompiledTestDirectoryFailsWithoutProducingARuntime() throws Exception {
        // 编译结果为空时必须失败，不能生成看似成功的空测试清单。
        Path classes = Files.createDirectory(this.temporary.resolve("classes"));
        Path seed = seed(classes);
        Path output = this.temporary.resolve("runtime.json");

        String[] arguments = { "export", seed.toString(), output.toString() };
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMain.run(arguments));
        assertTrue(failure.getMessage().contains("No JUnit @Test classes"));
        assertFalse(Files.exists(output));
    }

    @Test
    public void missingCompiledTestDirectoryFailsExplicitly() throws Exception {
        // 路径失效不能被当成没有测试，也不能遗留新的运行清单。
        Path seed = seed(this.temporary.resolve("missing"));
        Path output = this.temporary.resolve("runtime.json");
        String[] arguments = { "export", seed.toString(), output.toString() };

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMain.run(arguments));
        assertTrue(failure.getMessage().contains("Test class directory does not exist"));
        assertFalse(Files.exists(output));
    }

    @Test
    public void exportRequiresBothSeedAndDestinationPaths() {
        // 命令参数不完整时先报告用法，不尝试读取其它运行产物。
        String[] arguments = { "export", "seed.json" };
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> NxnMain.run(arguments));
        assertTrue(failure.getMessage().contains("export SEED_JSON DEST_JSON"));
    }

    private Path compile(Map<String, String> sources) throws Exception {
        Path classes = Files.createDirectory(this.temporary.resolve("classes"));
        List<String> classpath = new ArrayList<>();
        for (Class<?> type : List.of(NxnRpc.class, Test.class, ParameterizedClass.class)) {
            Path location = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            classpath.add(location.toString());
        }
        List<String> arguments = new ArrayList<>();
        arguments.add("-classpath");
        arguments.add(String.join(File.pathSeparator, classpath));
        arguments.add("-d");
        arguments.add(classes.toString());
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = this.temporary.resolve("sources").resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            arguments.add(file.toString());
        }
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        String[] compilerArguments = arguments.toArray(String[]::new);
        int status = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, compilerArguments);
        assertEquals(0, status, diagnostics.toString(StandardCharsets.UTF_8));
        return classes;
    }

    private Path seed(Path classes) throws Exception {
        Map<String, Object> runtime = new LinkedHashMap<>();
        Path independentRuntime = this.temporary.resolve("independent-runtime.jar").toAbsolutePath();
        runtime.put("classpath", List.of(classes.toAbsolutePath().toString(), independentRuntime.toString()));
        runtime.put("classDirectories", List.of(classes.toAbsolutePath().toString()));
        runtime.put("jvmArguments", List.of("--add-opens=java.base/java.io=ALL-UNNAMED"));
        runtime.put("systemProperties", Map.of("meteor.test.sdk.runtimes", "isolated-sdk-runtimes.json"));
        runtime.put("workingDirectory", this.temporary.toAbsolutePath().toString());
        Path seed = this.temporary.resolve("seed.json");
        NxnPlan.writeJson(seed, runtime);
        return seed;
    }
}
