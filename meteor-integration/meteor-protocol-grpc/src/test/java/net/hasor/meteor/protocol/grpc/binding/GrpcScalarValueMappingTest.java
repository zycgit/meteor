/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.binding;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;
import com.google.protobuf.DynamicMessage;
import net.hasor.meteor.domain.MetServiceType;
import net.hasor.meteor.domain.ProtocolConfig;
import net.hasor.meteor.domain.ServiceDomain;
import net.hasor.meteor.domain.meta.ProtocolMeta;
import net.hasor.meteor.protocol.grpc.GrpcProtoResource;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** 显式契约中的 decimal string 和 epoch int64 保留 Java 叶子类型。 */
public class GrpcScalarValueMappingTest {
    @Rule
    public final TemporaryFolder directory = new TemporaryFolder();

    @Test
    public void decimalStringPreservesEveryDigitAndItsScale() throws Exception {
        // 显式 string 契约接收高于 double 精度的 BigDecimal。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.amount = new BigDecimal("9007199254740993.123456789012345678901234567890");

        // 线上内容就是小数文本，回读不经过浮点数。
        DynamicMessage message = binding.encode(binding.javaMethod("echo"), input, true);
        var field = message.getDescriptorForType().findFieldByNumber(1);
        assertEquals(input.amount.toString(), message.getField(field));
        ScalarData result = (ScalarData) binding.decode(binding.javaMethod("echo"), message.toByteArray(), true);
        assertEquals(input.amount, result.amount);
    }

    @Test
    public void rawProtoJsonDecimalTextPreservesEveryDigitAndItsScale() throws Exception {
        // 外部 ProtoJSON 按 string 契约传递精确十进制文本。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        BigDecimal expected = new BigDecimal("9007199254740993.123456789012345678901234567890");
        String json = "{\"amount\":\"" + expected + "\"}";
        var method = binding.javaMethod("echo");

        // 官方 JSON 解析和 Java 回读都不经过 DoubleNode。
        DynamicMessage message = binding.parseJson(method, json, true);
        ScalarData result = (ScalarData) binding.decode(method, message.toByteArray(), true);
        assertEquals(expected, result.amount);
    }

    @Test
    public void responseDecimalZeroPreservesItsScale() throws Exception {
        // 零值同样携带 scale，响应映射不能先去掉末尾零。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.amount = new BigDecimal("0.0000");

        // BigDecimal.equals 同时检查数值和 scale。
        ScalarData result = this.roundTrip(binding, input, false);
        assertEquals(input.amount, result.amount);
    }

    @Test
    public void decimalStringPreservesANegativeScale() throws Exception {
        // 指数形式保留负 scale，不能改写成 toPlainString。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.amount = new BigDecimal("1E+7");

        // 回读后仍是 scale=-7 的同一个十进制值。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.amount, result.amount);
    }

    @Test
    public void repeatedDecimalStringsPreserveElementScales() throws Exception {
        // repeated string 按各元素的声明类型转换。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.amounts = List.of(new BigDecimal("12345678901234567890.1200"), new BigDecimal("0.0000"));

        // 容器不改变元素的精度和 scale。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.amounts, result.amounts);
    }

    @Test
    public void decimalMapValuesPreserveElementScales() throws Exception {
        // map value 使用 string 契约，键仍使用普通字符串。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.amountByName = Map.of("scaled", new BigDecimal("1.200"), "exponent", new BigDecimal("1E+7"));

        // value 转换与普通字段共用同一份叶子映射。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.amountByName, result.amountByName);
    }

    @Test
    public void epochMillisTextIsNotInterpretedAsACalendarYear() throws Exception {
        // ProtoJSON 将 int64 写成文本，1000 必须表示毫秒而不是年份。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.instant = new Date(1000L);

        // 解码前按显式 int64 契约恢复整数节点。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.instant, result.instant);
    }

    @Test
    public void epochMillisPreservesInstantsBeforeEpoch() throws Exception {
        // 负毫秒值不能按日期字符串的符号解释。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.instant = new Date(-877L);

        // 秒内精度和方向保持不变。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.instant, result.instant);
    }

    @Test
    public void epochMillisPreservesValuesAboveDoublePrecision() throws Exception {
        // int64 日期不能经 double 中转。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.instant = new Date(9007199254740993L);

        // 真实 Date 毫秒值仍精确相等。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.instant, result.instant);
    }

    @Test
    public void repeatedEpochMillisPreservesEachDate() throws Exception {
        // repeated int64 的每个成员都对应一个 Date。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.instants = List.of(new Date(1000L), new Date(-877L));

        // 列表元素复用普通 Date 字段的转换。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.instants, result.instants);
    }

    @Test
    public void epochMillisMapValuesPreserveEachDate() throws Exception {
        // map 的值使用 int64，避免依赖文本日期格式。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.instantByName = Map.of("short", new Date(1000L), "precise", new Date(9007199254740993L));

        // value 的毫秒值独立转换，字符串键保持不变。
        ScalarData result = this.roundTrip(binding, input, true);
        assertEquals(input.instantByName, result.instantByName);
    }

    @Test
    public void anAbsentImplicitEpochFieldUsesTheEpochDefault() throws Exception {
        // int64 没有 presence，未设置字段的协议默认值为零。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");

        // 默认值也经过 Date 叶子映射，不被当作年份。
        ScalarData result = (ScalarData) binding.decode(binding.javaMethod("echo"), new byte[0], true);
        assertEquals(new Date(0L), result.instant);
    }

    @Test
    public void decimalPrecisionConfigurationPreservesDoubleNegativeZero() throws Exception {
        // 保留 BigDecimal scale 的 mapper 设置不能改变 double 的符号位。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        ScalarData input = new ScalarData();
        input.floating = -0.0d;

        // optional double 保留显式零，回读仍具有负号位。
        ScalarData result = this.roundTrip(binding, input, true);
        long expected = Double.doubleToRawLongBits(input.floating);
        long actual = Double.doubleToRawLongBits(result.floating);
        assertEquals(expected, actual);
    }

    @Test
    public void rawProtoJsonIntegerNegativeZeroPreservesItsDoubleSign() throws Exception {
        // 外部请求中的整数词法 -0 没有预先转换为 Java Double。
        GrpcServiceBinding binding = this.binding(Values.class, "int64");
        var method = binding.javaMethod("echo");
        DynamicMessage message = binding.parseJson(method, "{\"floating\":-0}", true);

        // 现有 ProtoJSON 负零恢复和新的十进制配置保持兼容。
        ScalarData result = (ScalarData) binding.decode(method, message.toByteArray(), true);
        assertEquals(Long.MIN_VALUE, Double.doubleToRawLongBits(result.floating));
    }

    @Test
    public void dateCannotBindAFloatingPointContract() {
        // 日期只新增明确的 int64 毫秒映射，不接受近似浮点数。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.binding(Values.class, "double"));

        // 发布期定位到不兼容的 Java 叶子类型。
        assertTrue(failure.getMessage().contains("java.util.Date"));
    }

    @Test
    public void anArbitraryObjectCannotBindADecimalStringContract() {
        // string 的扩展只接受 BigDecimal，不把普通 DTO 自动转文本。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.binding(ForeignValues.class, "int64"));

        // 保留原有契约类型检查。
        assertTrue(failure.getMessage().contains(ForeignValue.class.getName()));
    }

    @Test
    public void dateMapKeysDoNotAcquireAnImplicitTextFormat() {
        // Proto int64 key 没有约定 Java Date 的 JSON 属性名格式。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, //
                () -> this.binding(DateKeyValues.class, "int64"));

        // 叶子值支持不会扩大为日期键的猜测映射。
        assertTrue(failure.getMessage().contains("java.util.Date"));
    }

    private ScalarData roundTrip(GrpcServiceBinding binding, ScalarData input, boolean request) throws Exception {
        var method = binding.javaMethod("echo");
        byte[] bytes = binding.encode(method, input, request).toByteArray();
        return (ScalarData) binding.decode(method, bytes, request);
    }

    private <T> GrpcServiceBinding binding(Class<T> type, String instantType) throws Exception {
        // 已有文件决定线类型，普通 Java 声明只负责两端属性绑定。
        String proto = """
                syntax = "proto3";
                package scalar;
                message ScalarData {
                  optional string amount = 1;
                  %s instant = 2;
                  repeated string amounts = 3;
                  map<string, string> amount_by_name = 4;
                  repeated int64 instants = 5;
                  map<string, int64> instant_by_name = 6;
                  optional double floating = 7;
                  map<int64, string> date_keys = 8;
                }
                service Values { rpc echo (ScalarData) returns (ScalarData); }
                """.formatted(instantType);
        Path source = this.directory.newFolder().toPath().resolve("scalar.proto");
        Files.writeString(source, proto);
        GrpcProtoResource resource = new GrpcProtoResource(source.toUri().toURL());
        ServiceDomain<T> service = new ServiceDomain<>(type);
        service.setServiceType(MetServiceType.Provider);
        service.setSerializeType("Protobuf");
        service.putAliasName("grpc", "scalar.Values");
        ProtocolConfig config = ProtocolConfig.newConfig("grpc", "grpc", Map.of());
        ProtocolMeta meta = new ProtocolMeta(service, null, resource, Map.of());
        return GrpcServiceBinding.prepare(config, meta, "Protobuf");
    }

    interface Values {
        ScalarData echo(ScalarData input);
    }

    interface ForeignValues {
        ForeignData echo(ForeignData input);
    }

    interface DateKeyValues {
        DateKeyData echo(DateKeyData input);
    }

    public static class ScalarData {
        public BigDecimal              amount;
        public Date                    instant;
        public List<BigDecimal>        amounts;
        public Map<String, BigDecimal> amountByName;
        public List<Date>              instants;
        public Map<String, Date>       instantByName;
        public Double                  floating;
    }

    public static class ForeignData {
        public ForeignValue amount;
    }

    public static class ForeignValue {
        public String text;
    }

    public static class DateKeyData {
        public Map<Date, String> dateKeys;
    }
}
