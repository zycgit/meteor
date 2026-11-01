package net.hasor.meteor.test.nxn;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 声明场景使用的 RPC 协议、线协议版本及参数显示名称。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(NxnRpcs.class)
public @interface NxnRpc {
    String name();

    String version() default "";

    String cases() default "*";
}
