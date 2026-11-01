package net.hasor.meteor.test.nxn;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 同一测试类或方法可声明多个协议与版本。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface NxnRpcs {
    NxnRpc[] value();
}
