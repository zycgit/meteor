/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.example.contract.types;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import net.hasor.cobble.StringUtils;
import net.hasor.cobble.reflect.ConstructorUtils;
import net.hasor.meteor.annotation.MetField;
import net.hasor.meteor.annotation.MetMethod;
import net.hasor.meteor.annotation.MetParam;
import net.hasor.meteor.annotation.MetService;

/** 每种类型独立发布，单个不支持的字段不会掩盖其它类型的协议映射。 */
public final class JavaTypeContracts {
    private JavaTypeContracts() {
    }

    public static Class<?> contract(String type) {
        return switch (type) {
            case "boolean" -> BooleanService.class;
            case "byte" -> ByteService.class;
            case "short" -> ShortService.class;
            case "int" -> IntegerService.class;
            case "long" -> LongService.class;
            case "float" -> FloatService.class;
            case "double" -> DoubleService.class;
            case "char" -> CharacterService.class;
            case "boxed-boolean" -> BoxedBooleanService.class;
            case "boxed-byte" -> BoxedByteService.class;
            case "boxed-short" -> BoxedShortService.class;
            case "boxed-int" -> BoxedIntegerService.class;
            case "boxed-long" -> BoxedLongService.class;
            case "boxed-float" -> BoxedFloatService.class;
            case "boxed-double" -> BoxedDoubleService.class;
            case "boxed-char" -> BoxedCharacterService.class;
            case "string" -> StringService.class;
            case "bytes" -> BytesService.class;
            case "int-array" -> IntegersService.class;
            case "string-array" -> StringsService.class;
            case "list-string" -> StringListService.class;
            case "map-string-int" -> StringMapService.class;
            case "dto" -> DtoService.class;
            case "big-integer" -> BigIntegerService.class;
            case "big-decimal" -> BigDecimalService.class;
            case "uuid" -> UuidService.class;
            case "date" -> DateService.class;
            case "set-string" -> StringSetService.class;
            case "local-date" -> LocalDateService.class;
            case "local-time" -> LocalTimeService.class;
            case "local-date-time" -> LocalDateTimeService.class;
            case "instant" -> InstantService.class;
            case "offset-date-time" -> OffsetDateTimeService.class;
            case "offset-time" -> OffsetTimeService.class;
            case "zoned-date-time" -> ZonedDateTimeService.class;
            case "duration" -> DurationService.class;
            case "period" -> PeriodService.class;
            case "year" -> YearService.class;
            case "year-month" -> YearMonthService.class;
            case "month-day" -> MonthDayService.class;
            case "zone-id" -> ZoneIdService.class;
            case "zone-offset" -> ZoneOffsetService.class;
            default -> throw new IllegalArgumentException("Unknown Java type: " + type);
        };
    }

    public static Object request(String type, Object value) throws ReflectiveOperationException {
        Class<?> requestType = contract(type).getMethods()[0].getParameterTypes()[0];
        Object request = ConstructorUtils.invokeConstructor(requestType, new Object[0]);
        for (var setter : requestType.getMethods()) {
            if (StringUtils.equals(setter.getName(), "setValue")) {
                setter.invoke(request, value);
                return request;
            }
        }
        throw new IllegalArgumentException("No typed value setter: " + requestType.getName());
    }

    public static Object value(Object request) throws ReflectiveOperationException {
        return request.getClass().getMethod("getValue").invoke(request);
    }

    public static Object provider(String type) {
        Class<?> contract = contract(type);
        return Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] { contract }, (proxy, method, args) -> {
            // 服务提供者接收并返回真实声明类型；不将 Java 值转成文本或开放 Object。
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(contract, args);
            }
            return method.getReturnType().cast(args[0]);
        });
    }

    public static class Customer implements Serializable {
        private static final long   serialVersionUID = 1L;
        @MetField(id = 1)
        private              long   customerId;
        @MetField(id = 2)
        private              String name;

        public long getCustomerId() {
            return this.customerId;
        }

        public void setCustomerId(long customerId) {
            this.customerId = customerId;
        }

        public String getName() {
            return this.name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    @MetService(id = 824395009L)
    public interface BooleanService {
        @MetMethod(id = 1)
        BooleanValue echo(@MetParam(id = 1, name = "request") BooleanValue request);
    }

    public static class BooleanValue implements Serializable {
        private static final long    serialVersionUID = 1L;
        @MetField(id = 1)
        private              boolean value;

        public boolean getValue() {
            return this.value;
        }

        public void setValue(boolean value) {
            this.value = value;
        }
    }

    @MetService(id = 824395010L)
    public interface ByteService {
        @MetMethod(id = 1)
        ByteValue echo(@MetParam(id = 1, name = "request") ByteValue request);
    }

    public static class ByteValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              byte value;

        public byte getValue() {
            return this.value;
        }

        public void setValue(byte value) {
            this.value = value;
        }
    }

    @MetService(id = 824395011L)
    public interface ShortService {
        @MetMethod(id = 1)
        ShortValue echo(@MetParam(id = 1, name = "request") ShortValue request);
    }

    public static class ShortValue implements Serializable {
        private static final long  serialVersionUID = 1L;
        @MetField(id = 1)
        private              short value;

        public short getValue() {
            return this.value;
        }

        public void setValue(short value) {
            this.value = value;
        }
    }

    @MetService(id = 824395012L)
    public interface IntegerService {
        @MetMethod(id = 1)
        IntegerValue echo(@MetParam(id = 1, name = "request") IntegerValue request);
    }

    public static class IntegerValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              int  value;

        public int getValue() {
            return this.value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }

    @MetService(id = 824395013L)
    public interface LongService {
        @MetMethod(id = 1)
        LongValue echo(@MetParam(id = 1, name = "request") LongValue request);
    }

    public static class LongValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              long value;

        public long getValue() {
            return this.value;
        }

        public void setValue(long value) {
            this.value = value;
        }
    }

    @MetService(id = 824395014L)
    public interface FloatService {
        @MetMethod(id = 1)
        FloatValue echo(@MetParam(id = 1, name = "request") FloatValue request);
    }

    public static class FloatValue implements Serializable {
        private static final long  serialVersionUID = 1L;
        @MetField(id = 1)
        private              float value;

        public float getValue() {
            return this.value;
        }

        public void setValue(float value) {
            this.value = value;
        }
    }

    @MetService(id = 824395015L)
    public interface DoubleService {
        @MetMethod(id = 1)
        DoubleValue echo(@MetParam(id = 1, name = "request") DoubleValue request);
    }

    public static class DoubleValue implements Serializable {
        private static final long   serialVersionUID = 1L;
        @MetField(id = 1)
        private              double value;

        public double getValue() {
            return this.value;
        }

        public void setValue(double value) {
            this.value = value;
        }
    }

    @MetService(id = 824395016L)
    public interface CharacterService {
        @MetMethod(id = 1)
        CharacterValue echo(@MetParam(id = 1, name = "request") CharacterValue request);
    }

    public static class CharacterValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              char value;

        public char getValue() {
            return this.value;
        }

        public void setValue(char value) {
            this.value = value;
        }
    }

    @MetService(id = 824395017L)
    public interface BoxedBooleanService {
        @MetMethod(id = 1)
        BoxedBooleanValue echo(@MetParam(id = 1, name = "request") BoxedBooleanValue request);
    }

    public static class BoxedBooleanValue implements Serializable {
        private static final long    serialVersionUID = 1L;
        @MetField(id = 1)
        private              Boolean value;

        public Boolean getValue() {
            return this.value;
        }

        public void setValue(Boolean value) {
            this.value = value;
        }
    }

    @MetService(id = 824395018L)
    public interface BoxedByteService {
        @MetMethod(id = 1)
        BoxedByteValue echo(@MetParam(id = 1, name = "request") BoxedByteValue request);
    }

    public static class BoxedByteValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              Byte value;

        public Byte getValue() {
            return this.value;
        }

        public void setValue(Byte value) {
            this.value = value;
        }
    }

    @MetService(id = 824395019L)
    public interface BoxedShortService {
        @MetMethod(id = 1)
        BoxedShortValue echo(@MetParam(id = 1, name = "request") BoxedShortValue request);
    }

    public static class BoxedShortValue implements Serializable {
        private static final long  serialVersionUID = 1L;
        @MetField(id = 1)
        private              Short value;

        public Short getValue() {
            return this.value;
        }

        public void setValue(Short value) {
            this.value = value;
        }
    }

    @MetService(id = 824395020L)
    public interface BoxedIntegerService {
        @MetMethod(id = 1)
        BoxedIntegerValue echo(@MetParam(id = 1, name = "request") BoxedIntegerValue request);
    }

    public static class BoxedIntegerValue implements Serializable {
        private static final long    serialVersionUID = 1L;
        @MetField(id = 1)
        private              Integer value;

        public Integer getValue() {
            return this.value;
        }

        public void setValue(Integer value) {
            this.value = value;
        }
    }

    @MetService(id = 824395021L)
    public interface BoxedLongService {
        @MetMethod(id = 1)
        BoxedLongValue echo(@MetParam(id = 1, name = "request") BoxedLongValue request);
    }

    public static class BoxedLongValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              Long value;

        public Long getValue() {
            return this.value;
        }

        public void setValue(Long value) {
            this.value = value;
        }
    }

    @MetService(id = 824395022L)
    public interface BoxedFloatService {
        @MetMethod(id = 1)
        BoxedFloatValue echo(@MetParam(id = 1, name = "request") BoxedFloatValue request);
    }

    public static class BoxedFloatValue implements Serializable {
        private static final long  serialVersionUID = 1L;
        @MetField(id = 1)
        private              Float value;

        public Float getValue() {
            return this.value;
        }

        public void setValue(Float value) {
            this.value = value;
        }
    }

    @MetService(id = 824395023L)
    public interface BoxedDoubleService {
        @MetMethod(id = 1)
        BoxedDoubleValue echo(@MetParam(id = 1, name = "request") BoxedDoubleValue request);
    }

    public static class BoxedDoubleValue implements Serializable {
        private static final long   serialVersionUID = 1L;
        @MetField(id = 1)
        private              Double value;

        public Double getValue() {
            return this.value;
        }

        public void setValue(Double value) {
            this.value = value;
        }
    }

    @MetService(id = 824395024L)
    public interface BoxedCharacterService {
        @MetMethod(id = 1)
        BoxedCharacterValue echo(@MetParam(id = 1, name = "request") BoxedCharacterValue request);
    }

    public static class BoxedCharacterValue implements Serializable {
        private static final long      serialVersionUID = 1L;
        @MetField(id = 1)
        private              Character value;

        public Character getValue() {
            return this.value;
        }

        public void setValue(Character value) {
            this.value = value;
        }
    }

    @MetService(id = 824395025L)
    public interface StringService {
        @MetMethod(id = 1)
        StringValue echo(@MetParam(id = 1, name = "request") StringValue request);
    }

    public static class StringValue implements Serializable {
        private static final long   serialVersionUID = 1L;
        @MetField(id = 1)
        private              String value;

        public String getValue() {
            return this.value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    @MetService(id = 824395026L)
    public interface BytesService {
        @MetMethod(id = 1)
        BytesValue echo(@MetParam(id = 1, name = "request") BytesValue request);
    }

    public static class BytesValue implements Serializable {
        private static final long   serialVersionUID = 1L;
        @MetField(id = 1)
        private              byte[] value;

        public byte[] getValue() {
            return this.value;
        }

        public void setValue(byte[] value) {
            this.value = value;
        }
    }

    @MetService(id = 824395027L)
    public interface IntegersService {
        @MetMethod(id = 1)
        IntegersValue echo(@MetParam(id = 1, name = "request") IntegersValue request);
    }

    public static class IntegersValue implements Serializable {
        private static final long  serialVersionUID = 1L;
        @MetField(id = 1)
        private              int[] value;

        public int[] getValue() {
            return this.value;
        }

        public void setValue(int[] value) {
            this.value = value;
        }
    }

    @MetService(id = 824395028L)
    public interface StringsService {
        @MetMethod(id = 1)
        StringsValue echo(@MetParam(id = 1, name = "request") StringsValue request);
    }

    public static class StringsValue implements Serializable {
        private static final long     serialVersionUID = 1L;
        @MetField(id = 1)
        private              String[] value;

        public String[] getValue() {
            return this.value;
        }

        public void setValue(String[] value) {
            this.value = value;
        }
    }

    @MetService(id = 824395029L)
    public interface StringListService {
        @MetMethod(id = 1)
        StringListValue echo(@MetParam(id = 1, name = "request") StringListValue request);
    }

    public static class StringListValue implements Serializable {
        private static final long         serialVersionUID = 1L;
        @MetField(id = 1)
        private              List<String> value;

        public List<String> getValue() {
            return this.value;
        }

        public void setValue(List<String> value) {
            this.value = value;
        }
    }

    @MetService(id = 824395030L)
    public interface StringMapService {
        @MetMethod(id = 1)
        StringMapValue echo(@MetParam(id = 1, name = "request") StringMapValue request);
    }

    public static class StringMapValue implements Serializable {
        private static final long                 serialVersionUID = 1L;
        @MetField(id = 1)
        private              Map<String, Integer> value;

        public Map<String, Integer> getValue() {
            return this.value;
        }

        public void setValue(Map<String, Integer> value) {
            this.value = value;
        }
    }

    @MetService(id = 824395031L)
    public interface DtoService {
        @MetMethod(id = 1)
        DtoValue echo(@MetParam(id = 1, name = "request") DtoValue request);
    }

    public static class DtoValue implements Serializable {
        private static final long     serialVersionUID = 1L;
        @MetField(id = 1)
        private              Customer value;

        public Customer getValue() {
            return this.value;
        }

        public void setValue(Customer value) {
            this.value = value;
        }
    }

    @MetService(id = 824395032L)
    public interface LocalDateService {
        @MetMethod(id = 1)
        LocalDateValue echo(@MetParam(id = 1, name = "request") LocalDateValue request);
    }

    public static class LocalDateValue implements Serializable {
        private static final long      serialVersionUID = 1L;
        @MetField(id = 1)
        private              LocalDate value;

        public LocalDate getValue() {
            return this.value;
        }

        public void setValue(LocalDate value) {
            this.value = value;
        }
    }

    @MetService(id = 824395033L)
    public interface LocalTimeService {
        @MetMethod(id = 1)
        LocalTimeValue echo(@MetParam(id = 1, name = "request") LocalTimeValue request);
    }

    public static class LocalTimeValue implements Serializable {
        private static final long      serialVersionUID = 1L;
        @MetField(id = 1)
        private              LocalTime value;

        public LocalTime getValue() {
            return this.value;
        }

        public void setValue(LocalTime value) {
            this.value = value;
        }
    }

    @MetService(id = 824395034L)
    public interface LocalDateTimeService {
        @MetMethod(id = 1)
        LocalDateTimeValue echo(@MetParam(id = 1, name = "request") LocalDateTimeValue request);
    }

    public static class LocalDateTimeValue implements Serializable {
        private static final long          serialVersionUID = 1L;
        @MetField(id = 1)
        private              LocalDateTime value;

        public LocalDateTime getValue() {
            return this.value;
        }

        public void setValue(LocalDateTime value) {
            this.value = value;
        }
    }

    @MetService(id = 824395035L)
    public interface InstantService {
        @MetMethod(id = 1)
        InstantValue echo(@MetParam(id = 1, name = "request") InstantValue request);
    }

    public static class InstantValue implements Serializable {
        private static final long    serialVersionUID = 1L;
        @MetField(id = 1)
        private              Instant value;

        public Instant getValue() {
            return this.value;
        }

        public void setValue(Instant value) {
            this.value = value;
        }
    }

    @MetService(id = 824395036L)
    public interface OffsetDateTimeService {
        @MetMethod(id = 1)
        OffsetDateTimeValue echo(@MetParam(id = 1, name = "request") OffsetDateTimeValue request);
    }

    public static class OffsetDateTimeValue implements Serializable {
        private static final long           serialVersionUID = 1L;
        @MetField(id = 1)
        private              OffsetDateTime value;

        public OffsetDateTime getValue() {
            return this.value;
        }

        public void setValue(OffsetDateTime value) {
            this.value = value;
        }
    }

    @MetService(id = 824395037L)
    public interface OffsetTimeService {
        @MetMethod(id = 1)
        OffsetTimeValue echo(@MetParam(id = 1, name = "request") OffsetTimeValue request);
    }

    public static class OffsetTimeValue implements Serializable {
        private static final long       serialVersionUID = 1L;
        @MetField(id = 1)
        private              OffsetTime value;

        public OffsetTime getValue() {
            return this.value;
        }

        public void setValue(OffsetTime value) {
            this.value = value;
        }
    }

    @MetService(id = 824395038L)
    public interface ZonedDateTimeService {
        @MetMethod(id = 1)
        ZonedDateTimeValue echo(@MetParam(id = 1, name = "request") ZonedDateTimeValue request);
    }

    public static class ZonedDateTimeValue implements Serializable {
        private static final long          serialVersionUID = 1L;
        @MetField(id = 1)
        private              ZonedDateTime value;

        public ZonedDateTime getValue() {
            return this.value;
        }

        public void setValue(ZonedDateTime value) {
            this.value = value;
        }
    }

    @MetService(id = 824395039L)
    public interface DurationService {
        @MetMethod(id = 1)
        DurationValue echo(@MetParam(id = 1, name = "request") DurationValue request);
    }

    public static class DurationValue implements Serializable {
        private static final long     serialVersionUID = 1L;
        @MetField(id = 1)
        private              Duration value;

        public Duration getValue() {
            return this.value;
        }

        public void setValue(Duration value) {
            this.value = value;
        }
    }

    @MetService(id = 824395040L)
    public interface PeriodService {
        @MetMethod(id = 1)
        PeriodValue echo(@MetParam(id = 1, name = "request") PeriodValue request);
    }

    public static class PeriodValue implements Serializable {
        private static final long   serialVersionUID = 1L;
        @MetField(id = 1)
        private              Period value;

        public Period getValue() {
            return this.value;
        }

        public void setValue(Period value) {
            this.value = value;
        }
    }

    @MetService(id = 824395041L)
    public interface YearService {
        @MetMethod(id = 1)
        YearValue echo(@MetParam(id = 1, name = "request") YearValue request);
    }

    public static class YearValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              Year value;

        public Year getValue() {
            return this.value;
        }

        public void setValue(Year value) {
            this.value = value;
        }
    }

    @MetService(id = 824395042L)
    public interface YearMonthService {
        @MetMethod(id = 1)
        YearMonthValue echo(@MetParam(id = 1, name = "request") YearMonthValue request);
    }

    public static class YearMonthValue implements Serializable {
        private static final long      serialVersionUID = 1L;
        @MetField(id = 1)
        private              YearMonth value;

        public YearMonth getValue() {
            return this.value;
        }

        public void setValue(YearMonth value) {
            this.value = value;
        }
    }

    @MetService(id = 824395043L)
    public interface MonthDayService {
        @MetMethod(id = 1)
        MonthDayValue echo(@MetParam(id = 1, name = "request") MonthDayValue request);
    }

    public static class MonthDayValue implements Serializable {
        private static final long     serialVersionUID = 1L;
        @MetField(id = 1)
        private              MonthDay value;

        public MonthDay getValue() {
            return this.value;
        }

        public void setValue(MonthDay value) {
            this.value = value;
        }
    }

    @MetService(id = 824395044L)
    public interface ZoneIdService {
        @MetMethod(id = 1)
        ZoneIdValue echo(@MetParam(id = 1, name = "request") ZoneIdValue request);
    }

    public static class ZoneIdValue implements Serializable {
        private static final long   serialVersionUID = 1L;
        @MetField(id = 1)
        private              ZoneId value;

        public ZoneId getValue() {
            return this.value;
        }

        public void setValue(ZoneId value) {
            this.value = value;
        }
    }

    @MetService(id = 824395045L)
    public interface ZoneOffsetService {
        @MetMethod(id = 1)
        ZoneOffsetValue echo(@MetParam(id = 1, name = "request") ZoneOffsetValue request);
    }

    public static class ZoneOffsetValue implements Serializable {
        private static final long       serialVersionUID = 1L;
        @MetField(id = 1)
        private              ZoneOffset value;

        public ZoneOffset getValue() {
            return this.value;
        }

        public void setValue(ZoneOffset value) {
            this.value = value;
        }
    }

    @MetService(id = 824395108L)
    public interface BigIntegerService {
        @MetMethod(id = 1)
        BigIntegerValue echo(@MetParam(id = 1, name = "request") BigIntegerValue request);
    }

    public static class BigIntegerValue implements Serializable {
        private static final long       serialVersionUID = 1L;
        @MetField(id = 1)
        private              BigInteger value;

        public BigInteger getValue() {
            return this.value;
        }

        public void setValue(BigInteger value) {
            this.value = value;
        }
    }

    @MetService(id = 824395109L)
    public interface BigDecimalService {
        @MetMethod(id = 1)
        BigDecimalValue echo(@MetParam(id = 1, name = "request") BigDecimalValue request);
    }

    public static class BigDecimalValue implements Serializable {
        private static final long       serialVersionUID = 1L;
        @MetField(id = 1)
        private              BigDecimal value;

        public BigDecimal getValue() {
            return this.value;
        }

        public void setValue(BigDecimal value) {
            this.value = value;
        }
    }

    @MetService(id = 824395110L)
    public interface UuidService {
        @MetMethod(id = 1)
        UuidValue echo(@MetParam(id = 1, name = "request") UuidValue request);
    }

    public static class UuidValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              UUID value;

        public UUID getValue() {
            return this.value;
        }

        public void setValue(UUID value) {
            this.value = value;
        }
    }

    @MetService(id = 824395111L)
    public interface DateService {
        @MetMethod(id = 1)
        DateValue echo(@MetParam(id = 1, name = "request") DateValue request);
    }

    public static class DateValue implements Serializable {
        private static final long serialVersionUID = 1L;
        @MetField(id = 1)
        private              Date value;

        public Date getValue() {
            return this.value;
        }

        public void setValue(Date value) {
            this.value = value;
        }
    }

    @MetService(id = 824395112L)
    public interface StringSetService {
        @MetMethod(id = 1)
        StringSetValue echo(@MetParam(id = 1, name = "request") StringSetValue request);
    }

    public static class StringSetValue implements Serializable {
        private static final long        serialVersionUID = 1L;
        @MetField(id = 1)
        private              Set<String> value;

        public Set<String> getValue() {
            return this.value;
        }

        public void setValue(Set<String> value) {
            this.value = value;
        }
    }
}
