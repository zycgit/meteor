package net.hasor.meteor.test.nxn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class NxnRpcSelectionTest {
    private static final String TEST_CLASS = "test.InteropTest";
    private static final String TEST_METHOD = "callsPeer";

    @Test
    public void classNameDoesNotDeclareItsProtocol() {
        // 类名中的 grpc 不能代替实际的 SOAP 声明。
        String className = "test.grpc.GrpcTest";
        Map<String, Object> metadata = metadata(className, List.of(declaration("soap", "1.2", "*")));

        // 选择完全由声明决定。
        assertFalse(NxnRpcSelection.selected(metadata, "grpc", "", className, TEST_METHOD, List.of("")));
    }

    @Test
    public void protocolNameDoesNotMatchAPrefix() {
        // gRPC-Web 和 gRPC 分别登记，不能通过名称前缀合并。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpcweb", "", "*")));

        // 普通 gRPC 选择不会接收 gRPC-Web 场景。
        assertFalse(selected(metadata, "grpc", "", ""));
    }

    @Test
    public void soapVersionSelectsTheDeclaredWireProtocol() {
        // SOAP 版本描述线上协议，不描述 SDK 发行版本。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("soap", "1.1", "*")));

        // 显式的 1.1 声明进入相同版本的执行范围。
        assertTrue(selected(metadata, "soap", "1.1", ""));
    }

    @Test
    public void soapVersionDoesNotSelectAnotherWireProtocol() {
        // 同一协议的不同版本仍须精确匹配。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("soap", "1.2", "*")));

        // 1.1 选择不运行 1.2 场景。
        assertFalse(selected(metadata, "soap", "1.1", ""));
    }

    @Test
    public void dubboMajorVersionSelectsItsExplicitSdkCases() {
        // Dubbo 按大版本归类，SDK 发行版本只出现在场景名称中。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(
                declaration("dubbo", "2", "dubbo/2.7.23/*"),
                declaration("dubbo", "3", "dubbo/3.3.6/*")));

        // 大版本 2 选择声明属于自己的 SDK 场景。
        assertTrue(selected(metadata, "dubbo", "2", "dubbo/2.7.23/provider"));
    }

    @Test
    public void dubboMajorVersionExcludesOtherSdkCases() {
        // 两个 SDK 场景属于同一参数化方法，但各自声明大版本。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(
                declaration("dubbo", "2", "dubbo/2.7.23/*"),
                declaration("dubbo", "3", "dubbo/3.3.6/*")));

        // 选择大版本 2 不能夹带大版本 3 的参数实例。
        assertFalse(selected(metadata, "dubbo", "2", "dubbo/3.3.6/consumer"));
    }

    @Test
    public void grpcDoesNotInferAProtocolVersionFromAnSdkRelease() {
        // 未声明协议版本的 gRPC 场景保留自己的无版本语义。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpc", "", "*")));

        // 不能把任意 gRPC SDK 发行版本解释为协议版本。
        assertFalse(selected(metadata, "grpc", "1.85.0", "grpc-java/1.85.0/provider"));
    }

    @Test
    public void versionlessSelectionIncludesEveryDeclaredVersion() {
        // 不填写版本表示该 RPC 的全部已声明版本。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("soap", "1.2", "*")));

        // 省略版本不会默认缩小到某个 SDK 或线上版本。
        assertTrue(selected(metadata, "soap", "", ""));
    }

    @Test
    public void compositionMatchesEitherDeclaredProtocol() {
        // 同一组合场景显式声明入站和出站使用的两个协议。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(
                declaration("grpc", "", "grpc-to-thrift"),
                declaration("thrift", "", "grpc-to-thrift")));

        // 选择出站协议时仍保留这个组合场景。
        assertTrue(selected(metadata, "thrift", "", "grpc-to-thrift"));
    }

    @Test
    public void parameterNamesKeepLiteralBrackets() {
        // 方括号是参数显示名称的一部分，不是正则字符集合。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpc", "", "peer[json]/*")));

        // 只展开星号，其余内容按字面匹配。
        assertTrue(selected(metadata, "grpc", "", "peer[json]/provider"));
    }

    @Test
    public void parameterBracketsDoNotMatchACharacterClass() {
        // 相似字符串不能借助正则字符集合语义匹配参数。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpc", "", "peer[json]/*")));

        // 去掉方括号的参数不属于声明范围。
        assertFalse(selected(metadata, "grpc", "", "peerj/provider"));
    }

    @Test
    public void ordinaryMethodUsesAnEmptyParameterName() {
        // 普通测试没有参数显示名，默认 cases 星号仍应覆盖它。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpc", "", "*")));

        // 无参数列表按普通方法处理。
        assertTrue(NxnRpcSelection.selected(metadata, "grpc", "", TEST_CLASS, TEST_METHOD, List.of()));
    }

    @Test
    public void unannotatedMethodIsExcludedFromRpcSelection() {
        // 类可以包含无关测试，缺失声明的方法不属于 RPC 子集。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpc", "", "*")));

        // 不把同类其他方法自动扩展进来。
        assertFalse(NxnRpcSelection.selected(metadata, "grpc", "", TEST_CLASS, "otherMethod", List.of("")));
    }

    @Test
    public void allSelectionDoesNotRequireRpcMetadata() {
        // 旧运行清单不包含 RPC 声明时，全量执行仍保持可用。
        assertTrue(NxnRpcSelection.selected(Map.of(), "", "", TEST_CLASS, TEST_METHOD, List.of("")));
    }

    @Test
    public void catalogCollectsOnlyDeclaredProtocolVersions() {
        // 可用版本来自声明本身，参数里的 SDK 版本不参与目录。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(
                declaration("dubbo", "3", "dubbo/3.3.6/*"),
                declaration("dubbo", "2", "dubbo/2.7.23/*"),
                declaration("grpc", "", "grpc-java/1.85.0/*")));

        // 目录按协议名和协议版本稳定排序。
        Map<String, List<String>> catalog = NxnRpcSelection.catalog(metadata);
        assertEquals(List.of("dubbo", "grpc"), new ArrayList<>(catalog.keySet()));
        assertEquals(Map.of("dubbo", List.of("2", "3"), "grpc", List.of("")), catalog);
    }

    @Test
    public void catalogMergesTheSameProtocolAlias() {
        // 常见连字符写法归并到同一个协议入口。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(
                declaration("grpc-web", "", "binary"), declaration("grpcweb", "", "text")));

        // 同一声明版本不在目录中重复出现。
        assertEquals(Map.of("grpc-web", List.of("")), NxnRpcSelection.catalog(metadata));
    }

    @Test
    public void selectedClassesIntersectTheRequestedMethod() {
        // 用户指定的方法同时包含两个协议，其中只有目标协议声明可保留。
        Map<String, Object> methods = Map.of(
                "grpcCall", List.of(declaration("grpc", "", "*")),
                "soapCall", List.of(declaration("soap", "1.2", "*")));
        Map<String, Object> metadata = Map.of(TEST_CLASS, methods);
        Map<String, List<String>> classes = Map.of(TEST_CLASS, List.of(TEST_CLASS + ".*Call"));

        // 计划保留原始方法筛选，运行期再由同一声明约束实际方法。
        Map<String, List<String>> selected = NxnRpcSelection.select(classes, metadata, "soap", "1.2");
        assertEquals(Map.of(TEST_CLASS, List.of(TEST_CLASS + ".*Call")), selected);
    }

    @Test
    public void unknownProtocolFailsBeforeLaunchingTests() {
        // 有效目录只声明 gRPC。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpc", "", "*")));
        Map<String, List<String>> classes = Map.of(TEST_CLASS, List.of());

        // 拼错协议名直接报告可用目录。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnRpcSelection.select(classes, metadata, "grcp", ""));
        assertTrue(failure.getMessage().contains("Unknown RPC protocol"));
        assertTrue(failure.getMessage().contains("grpc"));
    }

    @Test
    public void unknownProtocolVersionFailsBeforeLaunchingTests() {
        // 当前方法只声明 SOAP 1.1。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("soap", "1.1", "*")));
        Map<String, List<String>> classes = Map.of(TEST_CLASS, List.of());

        // 错误版本不能退化为执行该协议的全部版本。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnRpcSelection.select(classes, metadata, "soap", "1.2"));
        assertTrue(failure.getMessage().contains("Unknown version"));
        assertTrue(failure.getMessage().contains("1.1"));
    }

    @Test
    public void disjointMethodSelectionFailsBeforeLaunchingTests() {
        // 用户选中的方法没有目标 RPC 的声明。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration("grpc", "", "*")));
        Map<String, List<String>> classes = Map.of(TEST_CLASS, List.of(TEST_CLASS + ".otherMethod"));

        // 两个筛选的交集为空时明确报错。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnRpcSelection.select(classes, metadata, "grpc", ""));
        assertTrue(failure.getMessage().contains("No @NxnRpc testcase"));
    }

    @Test
    public void rpcSelectionRequiresExportedMetadata() {
        // 旧运行清单只支持全量和 testcase 选择，不能猜测 RPC 归属。
        Map<String, List<String>> classes = Map.of(TEST_CLASS, List.of());

        // 缺少声明时提示重新导出运行清单。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnRpcSelection.select(classes, Map.of(), "grpc", ""));
        assertTrue(failure.getMessage().contains("requires exported @NxnRpc metadata"));
    }

    @Test
    public void malformedMethodMapIsRejected() {
        // 类下必须是方法映射，不能误读为数组。
        Map<String, Object> metadata = Map.of(TEST_CLASS, List.of());

        // 运行清单结构错误应以可诊断的参数异常报告。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnRpcSelection.catalog(metadata));
        assertTrue(failure.getMessage().contains("method maps"));
    }

    @Test
    public void malformedDeclarationArrayIsRejected() {
        // 每个方法的可重复声明必须保存为数组。
        Map<String, Object> metadata = Map.of(TEST_CLASS, Map.of(TEST_METHOD, declaration("grpc", "", "*")));

        // 不接受与导出约定不符的单对象结构。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnRpcSelection.catalog(metadata));
        assertTrue(failure.getMessage().contains("arrays"));
    }

    @Test
    public void malformedDeclarationEntryIsRejected() {
        // 数组内每一项都必须是声明对象。
        Map<String, Object> metadata = Map.of(TEST_CLASS, Map.of(TEST_METHOD, List.of("grpc")));

        // 错误元素不能泄露强制转换异常。
        assertThrows(IllegalArgumentException.class, () -> NxnRpcSelection.catalog(metadata));
    }

    @Test
    public void emptyDeclaredProtocolNameIsRejected() {
        // 空协议名不能形成一个看似可执行的目录项。
        Map<String, Object> metadata = metadata(TEST_CLASS, List.of(declaration(" ", "", "*")));

        // 明确指出缺失的声明属性。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> NxnRpcSelection.catalog(metadata));
        assertTrue(failure.getMessage().contains("name must not be empty"));
    }

    private static boolean selected(Map<String, Object> metadata, String rpc, String version, String parameter) {
        return NxnRpcSelection.selected(metadata, rpc, version, TEST_CLASS, TEST_METHOD, List.of(parameter));
    }

    private static Map<String, Object> metadata(String className, List<Map<String, Object>> declarations) {
        return Map.of(className, Map.of(TEST_METHOD, declarations));
    }

    private static Map<String, Object> declaration(String name, String version, String cases) {
        return Map.of("name", name, "version", version, "cases", cases);
    }
}
