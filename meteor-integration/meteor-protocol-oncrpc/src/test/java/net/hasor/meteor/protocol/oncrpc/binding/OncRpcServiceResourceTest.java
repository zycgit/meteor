/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.oncrpc.binding;
import java.lang.reflect.Method;
import java.util.Map;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.hasor.meteor.annotation.MetIgnore;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetService;
import net.hasor.meteor.domain.ProtocolBinding;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.oncrpc.OncRpcProtocol;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodec;
import net.hasor.meteor.protocol.oncrpc.codec.XdrCodecs;
import org.junit.Test;
import static org.junit.Assert.*;

public class OncRpcServiceResourceTest {
    @Test
    public void customCodecWithoutAnIdlDefinitionExportsAnOpaqueType() {
        // 显式 codec 可以覆盖默认 String 编码，同时不提供可推断的 IDL 定义。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class) //
                .codec(String.class, XdrCodecs.string(4096));
        ProtocolConfig config = ProtocolConfig.newConfig("oncrpc", null, Map.of());
        ServiceDomain<Echo> service = new ServiceDomain<>(Echo.class);
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        ProtocolBinding binding = new OncRpcProtocol().prepareBinding(config, null, meta);
        ObjectNode description = binding.getDescription();

        // 原过程仍然发布，描述只说明该 codec 的类型结构不透明。
        var method = description.path("methods").get(0);
        String request = method.path("requestType").asText();
        assertEquals("opaque", description.path("types").path(request).path("kind").asText());
        assertEquals(1, method.path("procedureId").asLong());
    }

    @Test
    public void resourceExposesDeclaredServiceIdentity() {
        // 公共注解声明程序号，协议资源使用默认版本一，不创建业务实现。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertSame(Echo.class, resource.getServiceType());
        assertEquals(0x20000001L, resource.getProgram());
        assertEquals(1, resource.getVersion());
    }

    @Test
    public void resourceVersionCanBeSelectedWithoutChangingItsServiceIdentity() {
        // 协议版本是资源配置，公共服务版本保留业务坐标语义。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        resource.setVersion(7);

        // 修改版本不会丢失程序号或已经解析的过程映射。
        assertEquals(7, resource.getVersion());
        assertEquals(0x20000001L, resource.getProgram());
        assertTrue(resource.getMethods().containsKey(1L));
    }

    @Test
    public void methodNumberCanBeCombinedWithAPublicRemoteName() throws Exception {
        // 公共方法注解在同一处声明过程号和线上名称。
        OncRpcServiceResource resource = new OncRpcServiceResource(RenamedEcho.class);
        Method local = RenamedEcho.class.getMethod("localEcho", String.class);

        // 过程表保留 Java 方法，自动契约使用公共远程名称。
        assertEquals(local, resource.getMethods().get(11L));
        assertEquals(local, resource.getMethod("localEcho"));
        ServiceDomain<RenamedEcho> service = new ServiceDomain<>(RenamedEcho.class);
        ProtocolConfig protocol = ProtocolConfig.newConfig("oncrpc", null, Map.of());
        OncRpcServiceBinding binding = new OncRpcServiceBinding(resource, service, protocol);
        String source = binding.getSources().values().iterator().next();
        assertTrue(source.contains("echo("));
        assertFalse(source.contains("localEcho("));
    }

    @Test
    public void changingAResourceVersionKeepsThePreparedEndpointVersion() {
        // 同一资源先准备版本二的端点，描述与定义在同一次绑定中确定。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        resource.setVersion(2);
        ServiceDomain<Echo> service = new ServiceDomain<>(Echo.class);
        ProtocolConfig protocol = ProtocolConfig.newConfig("oncrpc", null, Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        OncRpcProtocol provider = new OncRpcProtocol();
        ProtocolBinding first = provider.prepareBinding(protocol, null, meta);

        // 下一端点采用新版本，已准备端点继续持有自己的版本和描述。
        resource.setVersion(3);
        ProtocolBinding second = provider.prepareBinding(protocol, null, meta);
        OncRpcServiceBinding previousDefinition = (OncRpcServiceBinding) first.getDefinition();
        OncRpcServiceBinding nextDefinition = (OncRpcServiceBinding) second.getDefinition();
        assertEquals(2, previousDefinition.getVersion());
        assertEquals(2, first.getDescription().path("version").asLong());
        assertEquals(3, nextDefinition.getVersion());
        assertEquals(3, second.getDescription().path("version").asLong());
    }

    @Test
    public void procedureTableRetainsItsJavaMethod() throws Exception {
        // 资源把程序内的过程号映射到准确的 Java 方法签名。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        Method echo = Echo.class.getMethod("echo", String.class);
        assertEquals(1, resource.getMethods().size());
        assertEquals(echo, resource.getMethods().get(1L));
    }

    @Test
    public void methodNameLookupResolvesItsProcedure() throws Exception {
        // 出站编码通过方法名找到资源中的过程声明。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        Method echo = Echo.class.getMethod("echo", String.class);
        assertEquals(echo, resource.getMethod("echo"));
    }

    @Test
    public void unknownMethodNameHasNoProcedure() {
        // 未声明的方法不能落到其它过程。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertNull(resource.getMethod("missing"));
    }

    @Test
    public void procedureTableCannotBeChangedByItsCaller() {
        // 调用方只读取启动时解析完成的过程映射。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        Map<Long, Method> methods = resource.getMethods();

        // 外部删除过程不会改变资源的有效声明。
        assertThrows(UnsupportedOperationException.class, () -> methods.remove(1L));
        assertEquals(1, resource.getMethods().size());
    }

    @Test
    public void nullServiceTypeIsRejected() {
        // 资源必须绑定一个本地 Java 接口。
        assertThrows(NullPointerException.class, () -> new OncRpcServiceResource(null));
    }

    @Test
    public void concreteClassCannotBePublishedAsAnInterface() {
        // 即使普通类可被反射，也不能作为服务契约。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(String.class));
    }

    @Test
    public void unannotatedInterfaceHasNoProgramIdentity() {
        // 程序号必须由接口声明提供，不能通过接口名猜测。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(NoProgram.class));
    }

    @Test
    public void automaticPublicationSkipsInterfacesWithoutAProgram() {
        // 自动挂载缺少程序声明的普通接口时，仅拒绝 ONC RPC。
        assertNull(OncRpcServiceResource.infer(NoProgram.class));
    }

    @Test
    public void automaticPublicationSkipsInterfacesWithoutProcedureNumbers() {
        // 缺少过程编号的接口没有足够的线上契约。
        assertNull(OncRpcServiceResource.infer(MissingProcedure.class));
    }

    @Test
    public void automaticPublicationSkipsOverloadedSignatures() {
        // 不支持的重载签名不影响其他协议发布同一个接口。
        assertNull(OncRpcServiceResource.infer(OverloadedProcedure.class));
    }

    @Test
    public void programNumberAcceptsTheUnsignedLowerBoundary() {
        // 程序号使用完整的无符号 32 位范围。
        OncRpcServiceResource resource = new OncRpcServiceResource(ZeroProgram.class);
        assertEquals(0, resource.getProgram());
    }

    @Test
    public void programNumberAcceptsTheUnsignedUpperBoundary() {
        // 高位为一的程序号不被错误地当作负数。
        OncRpcServiceResource resource = new OncRpcServiceResource(MaximumProgram.class);
        assertEquals(0xffffffffL, resource.getProgram());
    }

    @Test
    public void negativeProgramNumberIsRejected() {
        // Java long 的负值不能成为线上无符号程序号。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(NegativeProgram.class));
    }

    @Test
    public void programNumberAboveTheUnsignedRangeIsRejected() {
        // 资源不能在编码时静默截断程序号。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(OversizedProgram.class));
    }

    @Test
    public void zeroVersionNumberIsRejected() {
        // 显式版本配置不能使用保留的零值。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertThrows(IllegalArgumentException.class, () -> resource.setVersion(0));
    }

    @Test
    public void versionNumberAcceptsTheUnsignedUpperBoundary() {
        // 版本号保留无符号数的完整值。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        resource.setVersion(0xffffffffL);
        assertEquals(0xffffffffL, resource.getVersion());
    }

    @Test
    public void negativeVersionNumberIsRejected() {
        // 负版本不能进入已解析的服务资源。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertThrows(IllegalArgumentException.class, () -> resource.setVersion(-1));
    }

    @Test
    public void versionNumberAboveTheUnsignedRangeIsRejected() {
        // 超过线上字段宽度的版本声明立即报错。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertThrows(IllegalArgumentException.class, () -> resource.setVersion(0x100000000L));
    }

    @Test
    public void procedureNumberAcceptsTheUnsignedUpperBoundary() {
        // 过程号不被 Java 有符号 int 范围限制。
        OncRpcServiceResource resource = new OncRpcServiceResource(MaximumProcedure.class);
        assertTrue(resource.getMethods().containsKey(0xffffffffL));
    }

    @Test
    public void nullProcedureNumberIsReserved() {
        // 过程零由插件提供 NULL 调用，用户不能重复声明。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(NullProcedure.class));
    }

    @Test
    public void negativeProcedureNumberIsRejected() {
        // 方法注解也遵守无符号 32 位约束。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(NegativeProcedure.class));
    }

    @Test
    public void procedureNumberAboveTheUnsignedRangeIsRejected() {
        // 不能把两个不同的长整数过程号截断为同一个线上值。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(OversizedProcedure.class));
    }

    @Test
    public void methodWithoutProcedureNumberIsRejected() {
        // 每个实例方法都需要明确过程号。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(MissingProcedure.class));
    }

    @Test
    public void overloadedJavaMethodIsRejected() {
        // 同名方法不能依靠参数重载选择线上过程。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(OverloadedProcedure.class));
    }

    @Test
    public void duplicateProcedureNumberIsRejected() {
        // 两个不同方法不能共享一个线上过程号。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(DuplicateProcedure.class));
    }

    @Test
    public void staticInterfaceMethodDoesNotBecomeAProcedure() {
        // 静态工具方法不属于服务实例的远程方法。
        OncRpcServiceResource resource = new OncRpcServiceResource(StaticUtility.class);
        assertEquals(1, resource.getMethods().size());
        assertNull(resource.getMethod("description"));
    }

    @Test
    public void ignoredMethodIsExcludedWithoutProcedure() {
        // 被框架排除的方法无需为 ONC RPC 声明过程号。
        OncRpcServiceResource resource = new OncRpcServiceResource(IgnoredMethod.class);
        assertEquals(1, resource.getMethods().size());
        assertNull(resource.getMethod("local"));
        assertTrue(resource.getMethods().containsKey(1L));
    }

    @Test
    public void interfaceWithoutBusinessMethodsStillHasAProgramIdentity() {
        // 仅提供内置 NULL 过程的程序不需要虚构业务方法。
        OncRpcServiceResource resource = new OncRpcServiceResource(EmptyProgram.class);
        assertTrue(resource.getMethods().isEmpty());
        assertEquals(0x20000001L, resource.getProgram());
    }

    @Test
    public void childInterfaceInheritsItsProgramDeclaration() {
        // 子接口沿用父接口的程序号，并保留继承的过程。
        OncRpcServiceResource resource = new OncRpcServiceResource(InheritedEcho.class);
        assertEquals(0x20000001L, resource.getProgram());
        assertEquals(1, resource.getVersion());
        assertTrue(resource.getMethods().containsKey(1L));
    }

    @Test
    public void programDeclarationCanBeInheritedAcrossMultipleLevels() {
        // 程序声明可以位于接口继承链的祖先节点。
        OncRpcServiceResource resource = new OncRpcServiceResource(DeepInheritedEcho.class);
        assertEquals(0x20000001L, resource.getProgram());
        assertEquals(1, resource.getVersion());
    }

    @Test
    public void identicalInheritedProgramDeclarationsAreUnambiguous() {
        // 多个父接口声明同一个程序时仍然只有一个有效身份。
        OncRpcServiceResource resource = new OncRpcServiceResource(MatchingProgramParents.class);
        assertEquals(0x20000001L, resource.getProgram());
        assertEquals(1, resource.getVersion());
    }

    @Test
    public void conflictingInheritedProgramNumbersAreRejected() {
        // 不能任意选择多个父接口中的某一个程序号。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(ConflictingProgramParents.class));
    }

    @Test
    public void inheritedStringServiceVersionsDoNotBecomeProgramVersions() {
        // 相同程序号的父接口可以声明不同业务版本，协议资源仍使用默认版本一。
        OncRpcServiceResource resource = new OncRpcServiceResource(DifferentServiceVersionParents.class);
        assertEquals(0x20000001L, resource.getProgram());
        assertEquals(1, resource.getVersion());
    }

    @Test
    public void explicitProgramDeclarationOverridesParentPrograms() {
        // 子接口显式指定自身身份后，不再通过父接口选择程序号。
        OncRpcServiceResource resource = new OncRpcServiceResource(ExplicitProgram.class);
        assertEquals(0x20000003L, resource.getProgram());
        assertEquals(1, resource.getVersion());
    }

    @Test
    public void unannotatedParentDoesNotHideAnInheritedProgram() {
        // 普通标记父接口不会覆盖另一父接口提供的程序声明。
        OncRpcServiceResource resource = new OncRpcServiceResource(MarkerAndProgramParents.class);
        assertEquals(0x20000001L, resource.getProgram());
        assertTrue(resource.getMethods().containsKey(1L));
    }

    @Test
    public void identicalInheritedProceduresShareOneMethodMapping() {
        // 两个父接口对同一签名和过程号达成一致时只保留一份映射。
        OncRpcServiceResource resource = new OncRpcServiceResource(MatchingProcedureParents.class);
        assertEquals(1, resource.getMethods().size());
        assertTrue(resource.getMethods().containsKey(1L));
    }

    @Test
    public void conflictingInheritedProcedureNumbersAreRejected() {
        // 同一个 Java 签名不能因反射顺序不同而选到不同过程号。
        Class<?> serviceType = ConflictingProcedureParents.class;
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(serviceType));
    }

    @Test
    public void missingInheritedProcedureNumberIsRejected() {
        // 重复签名中有未声明过程的父方法时，不能静默使用另一个父方法。
        assertThrows(IllegalArgumentException.class, () -> new OncRpcServiceResource(MissingInheritedProcedure.class));
    }

    @Test
    public void registeredCodecIsReturnedForItsExactJavaType() {
        // 资源保存本地显式选择的 XDR 编码器，不复制或替换实例。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        XdrCodec<Integer> codec = XdrCodecs.INT;

        // 编码器注册返回同一资源，类型查询也返回同一编码器。
        assertSame(resource, resource.codec(Integer.class, codec));
        assertSame(codec, resource.getCodec(Integer.class));
    }

    @Test
    public void codecCanBeReplacedBeforePublication() {
        // 调用方可以在发布前重新选择同一 Java 类型的编码规则。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        XdrCodec<String> first = XdrCodecs.string(16);
        XdrCodec<String> replacement = XdrCodecs.string(64);
        resource.codec(String.class, first);

        // 后一次配置成为最终生效的编码器。
        resource.codec(String.class, replacement);
        assertSame(replacement, resource.getCodec(String.class));
    }

    @Test
    public void unregisteredJavaTypeHasNoExplicitCodec() {
        // 未配置类型交给后续编码适配处理，不伪造显式编码器。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertNull(resource.getCodec(String.class));
    }

    @Test
    public void nullCodecTypeIsRejected() {
        // 编码器必须关联一个明确的本地 Java 类型。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertThrows(NullPointerException.class, () -> resource.codec(null, XdrCodecs.INT));
    }

    @Test
    public void nullCodecInstanceIsRejected() {
        // 空编码器不能覆盖已知的类型映射。
        OncRpcServiceResource resource = new OncRpcServiceResource(Echo.class);
        assertThrows(NullPointerException.class, () -> resource.codec(Integer.class, null));
    }

    @MetService(id = 0x20000001L)
    private interface Echo {
        @MetMethod(id = 1)
        String echo(String value);
    }

    @MetService(id = 0x20000001L)
    private interface RenamedEcho {
        @MetMethod(id = 11, name = "echo")
        String localEcho(String value);
    }

    private interface NoProgram {
        @MetMethod(id = 1)
        void ping();
    }

    @MetService(id = 0)
    private interface ZeroProgram {
    }

    @MetService(id = 0xffffffffL)
    private interface MaximumProgram {
    }

    @MetService(id = -2)
    private interface NegativeProgram {
    }

    @MetService(id = 0x100000000L)
    private interface OversizedProgram {
    }

    @MetService(id = 0x20000001L)
    private interface MaximumProcedure {
        @MetMethod(id = 0xffffffffL)
        void ping();
    }

    @MetService(id = 0x20000001L)
    private interface NullProcedure {
        @MetMethod(id = 0)
        void ping();
    }

    @MetService(id = 0x20000001L)
    private interface NegativeProcedure {
        @MetMethod(id = -1)
        void ping();
    }

    @MetService(id = 0x20000001L)
    private interface OversizedProcedure {
        @MetMethod(id = 0x100000000L)
        void ping();
    }

    @MetService(id = 0x20000001L)
    private interface MissingProcedure {
        void ping();
    }

    @MetService(id = 0x20000001L)
    private interface OverloadedProcedure {
        @MetMethod(id = 1)
        void call(int value);

        @MetMethod(id = 2)
        void call(String value);
    }

    @MetService(id = 0x20000001L)
    private interface DuplicateProcedure {
        @MetMethod(id = 1)
        void first();

        @MetMethod(id = 1)
        void second();
    }

    @MetService(id = 0x20000001L)
    private interface StaticUtility {
        @MetMethod(id = 1)
        void ping();

        static String description() {
            return "local";
        }
    }

    @MetService(id = 0x20000001L)
    private interface IgnoredMethod {
        @MetMethod(id = 1)
        void ping();

        @MetIgnore
        void local();
    }

    @MetService(id = 0x20000001L)
    private interface EmptyProgram {
    }

    private interface InheritedEcho extends Echo {
    }

    private interface DeepInheritedEcho extends InheritedEcho {
    }

    @MetService(id = 0x20000001L)
    private interface SameProgram {
    }

    @MetService(id = 0x20000002L)
    private interface OtherProgram {
    }

    @MetService(id = 0x20000001L, version = "business-v2")
    private interface OtherVersion {
    }

    private interface MatchingProgramParents extends Echo, SameProgram {
    }

    private interface ConflictingProgramParents extends Echo, OtherProgram {
    }

    private interface DifferentServiceVersionParents extends Echo, OtherVersion {
    }

    @MetService(id = 0x20000003L)
    private interface ExplicitProgram extends Echo, OtherProgram {
    }

    private interface Marker {
    }

    private interface MarkerAndProgramParents extends Marker, Echo {
    }

    private interface SameProcedure {
        @MetMethod(id = 1)
        String echo(String value);
    }

    private interface OtherProcedure {
        @MetMethod(id = 2)
        String echo(String value);
    }

    private interface UnannotatedProcedure {
        String echo(String value);
    }

    private interface MatchingProcedureParents extends Echo, SameProcedure {
    }

    private interface ConflictingProcedureParents extends Echo, OtherProcedure {
    }

    private interface MissingInheritedProcedure extends Echo, UnannotatedProcedure {
    }
}
