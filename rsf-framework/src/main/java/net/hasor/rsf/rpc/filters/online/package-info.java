/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
/**
 * 优雅下线功能。
 * <p>场景：当应用准备关闭下线，或者因为升级需要应用程序需要结束运行，但是远程服务消费者可能还在请求这台机器。
 * <p>优雅下线，分为两个阶段。第一个阶段应用在下线的时候会通知注册中心，注册中心会讲准备下线的应用地址推送给其它服务消费者。
 * 其次第二个阶段，应用本身要确保没有新的服务调用请求能够进来。这个软件包的功能就是来保证第二个阶段。
 */
package net.hasor.rsf.rpc.filters.online;