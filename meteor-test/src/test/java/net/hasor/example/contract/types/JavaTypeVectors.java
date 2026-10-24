/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import java.time.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 固定类型向量；Object 仅承载测试数据，公开 RPC 声明仍是各自具体类型。 */
public final class JavaTypeVectors {
    private JavaTypeVectors() {
    }

    public static List<Object[]> basic() {
        // 三列顺序固定为类型、向量名和值，与参数化 JUnit 名称一一对应。
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "boolean", "true", true });
        result.add(new Object[] { "boolean", "false", false });
        result.add(new Object[] { "byte", "minimum", Byte.MIN_VALUE });
        result.add(new Object[] { "byte", "maximum", Byte.MAX_VALUE });
        result.add(new Object[] { "short", "minimum", Short.MIN_VALUE });
        result.add(new Object[] { "short", "maximum", Short.MAX_VALUE });
        result.add(new Object[] { "int", "minimum", Integer.MIN_VALUE });
        result.add(new Object[] { "int", "maximum", Integer.MAX_VALUE });
        result.add(new Object[] { "long", "minimum", Long.MIN_VALUE });
        result.add(new Object[] { "long", "maximum", Long.MAX_VALUE });
        result.add(new Object[] { "float", "minimum-positive", Float.MIN_VALUE });
        result.add(new Object[] { "float", "maximum-finite", Float.MAX_VALUE });
        result.add(new Object[] { "double", "minimum-positive", Double.MIN_VALUE });
        result.add(new Object[] { "double", "maximum-finite", Double.MAX_VALUE });
        result.add(new Object[] { "char", "unicode-cjk", '\u4e2d' });
        result.add(new Object[] { "char", "null-character", '\u0000' });

        // 包装类型有独立的真实字段声明，分别验证数值与 null。
        result.add(new Object[] { "boxed-boolean", "value", true });
        result.add(new Object[] { "boxed-boolean", "null", null });
        result.add(new Object[] { "boxed-byte", "minimum", Byte.MIN_VALUE });
        result.add(new Object[] { "boxed-byte", "null", null });
        result.add(new Object[] { "boxed-short", "minimum", Short.MIN_VALUE });
        result.add(new Object[] { "boxed-short", "null", null });
        result.add(new Object[] { "boxed-int", "minimum", Integer.MIN_VALUE });
        result.add(new Object[] { "boxed-int", "null", null });
        result.add(new Object[] { "boxed-long", "minimum", Long.MIN_VALUE });
        result.add(new Object[] { "boxed-long", "null", null });
        result.add(new Object[] { "boxed-float", "minimum-positive", Float.MIN_VALUE });
        result.add(new Object[] { "boxed-float", "null", null });
        result.add(new Object[] { "boxed-double", "minimum-positive", Double.MIN_VALUE });
        result.add(new Object[] { "boxed-double", "null", null });
        result.add(new Object[] { "boxed-char", "unicode-cjk", '\u4e2d' });
        result.add(new Object[] { "boxed-char", "null", null });
        result.add(new Object[] { "string", "unicode-and-xml", "Meteor 中文🙂<&>\"'" });
        result.add(new Object[] { "string", "empty", "" });
        result.add(new Object[] { "string", "null", null });
        result.add(new Object[] { "bytes", "signed-octets", new byte[] { 0, 1, 127, -128, -1 } });
        result.add(new Object[] { "bytes", "empty", new byte[0] });
        result.add(new Object[] { "int-array", "boundaries", new int[] { Integer.MIN_VALUE, 0, Integer.MAX_VALUE } });
        result.add(new Object[] { "string-array", "unicode", new String[] { "", "中文", "🙂" } });
        result.add(new Object[] { "list-string", "unicode", new ArrayList<>(List.of("", "中文", "🙂")) });
        Map<String, Integer> map = new LinkedHashMap<>();
        map.put("最小值", Integer.MIN_VALUE);
        map.put("maximum", Integer.MAX_VALUE);
        result.add(new Object[] { "map-string-int", "typed-values", map });
        JavaTypeContracts.Customer customer = new JavaTypeContracts.Customer();
        customer.setCustomerId(9007199254740993L);
        customer.setName("客户🙂");
        result.add(new Object[] { "dto", "named-fields", customer });
        result.add(new Object[] { "big-integer", "beyond-int64", new BigInteger("92233720368547758081234567890") });
        result.add(new Object[] { "big-decimal", "decimal-scale", new BigDecimal("12345678901234567890.0012300") });
        result.add(new Object[] { "uuid", "canonical", UUID.fromString("123e4567-e89b-12d3-a456-426614174000") });
        result.add(new Object[] { "date", "before-epoch-milliseconds", new Date(-877L) });
        result.add(new Object[] { "set-string", "unique-unicode", new LinkedHashSet<>(List.of("中文", "🙂")) });
        return result;
    }

    public static List<Object[]> time() {
        // 本地时间不附加时区；带时区类型单独保留 offset 和 region，精度固定到纳秒。
        List<Object[]> result = new ArrayList<>();
        result.add(new Object[] { "local-date", "leap-day", LocalDate.of(2024, 2, 29) });
        result.add(new Object[] { "local-time", "nanoseconds", LocalTime.parse("23:59:59.123456789") });
        LocalDateTime localDateTime = LocalDateTime.parse("2024-02-29T23:59:59.123456789");
        result.add(new Object[] { "local-date-time", "nanoseconds", localDateTime });
        Instant beforeEpoch = Instant.parse("1969-12-31T23:59:59.123456789Z");
        result.add(new Object[] { "instant", "before-epoch-nanoseconds", beforeEpoch });
        OffsetDateTime offsetDateTime = OffsetDateTime.parse("2024-02-29T23:59:59.123456789+05:45");
        result.add(new Object[] { "offset-date-time", "non-hour-offset", offsetDateTime });
        result.add(new Object[] { "offset-time", "negative-offset", OffsetTime.parse("23:59:59.123456789-03:30") });
        ZonedDateTime zeroOffsetRegion = ZonedDateTime.parse("2024-01-15T12:34:56.123456789Z[Europe/London]");
        result.add(new Object[] { "zoned-date-time", "utc-region-identity", zeroOffsetRegion });
        ZonedDateTime nonZeroOffsetRegion = ZonedDateTime.parse("2024-01-15T12:34:56.123456789+01:00[Europe/Paris]");
        result.add(new Object[] { "zoned-date-time", "non-utc-region-identity", nonZeroOffsetRegion });
        result.add(new Object[] { "duration", "negative-nanosecond", Duration.ofNanos(-1) });
        result.add(new Object[] { "period", "calendar-components", Period.of(1, 2, 3) });
        result.add(new Object[] { "year", "before-common-era", Year.of(-1) });
        result.add(new Object[] { "year-month", "leap-month", YearMonth.of(2024, 2) });
        result.add(new Object[] { "month-day", "leap-day", MonthDay.of(2, 29) });
        result.add(new Object[] { "zone-id", "region-identity", ZoneId.of("Europe/Paris") });
        result.add(new Object[] { "zone-offset", "non-hour-offset", ZoneOffset.of("+05:45") });
        return result;
    }
}
