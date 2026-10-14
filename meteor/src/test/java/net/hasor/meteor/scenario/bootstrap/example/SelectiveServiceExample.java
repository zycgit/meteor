/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.scenario.bootstrap.example;
import java.util.concurrent.atomic.AtomicInteger;
import net.hasor.meteor.MetIgnore;

/** 服务端发布契约与不带限制的客户端契约。 */
public class SelectiveServiceExample {
    public interface Parent {
        @MetIgnore
        default String inheritedHidden() {
            return "hidden default";
        }

        default String inheritedVisible() {
            return "visible default";
        }
    }

    public interface Published extends Parent {
        String echo(String value);

        @MetIgnore
        String echo(int value);

        @MetIgnore
        String hidden();

        default String echoint() {
            return "visible overload name";
        }

        static String helper() {
            return "static helper";
        }
    }

    /** 独立客户端契约故意不带注解，验证服务端不能只依赖客户端过滤。 */
    public interface RemoteView {
        String echo(String value);

        String echo(int value);

        String hidden();

        String inheritedHidden();

        String inheritedVisible();

        String echoint();

        String helper();

        String implementationOnly();
    }

    public static class Service implements Published {
        private final AtomicInteger hiddenCalls = new AtomicInteger();

        public int hiddenCalls() {
            return this.hiddenCalls.get();
        }

        @Override
        public String echo(String value) {
            return value;
        }

        @Override
        public String echo(int value) {
            this.hiddenCalls.incrementAndGet();
            return Integer.toString(value);
        }

        @Override
        public String hidden() {
            this.hiddenCalls.incrementAndGet();
            return "hidden";
        }

        public String implementationOnly() {
            this.hiddenCalls.incrementAndGet();
            return "implementation only";
        }
    }

}
