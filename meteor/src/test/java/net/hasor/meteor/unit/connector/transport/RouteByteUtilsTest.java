/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.unit.connector.transport;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.hasor.meteor.connector.RouteDefinition;
import net.hasor.meteor.connector.RouteProbeResult;
import net.hasor.meteor.connector.RouteTestAccess;
import net.hasor.meteor.connector.transport.RouteByteUtils;
import net.hasor.neta.bytebuf.ByteBuf;
import org.junit.Test;
import static org.junit.Assert.*;

/** 验证字节路由聚合多个协议探针时的候选判定。 */
public class RouteByteUtilsTest {
    @Test
    public void streamWaitsUntilPendingRouteIsRejected() {
        // 一个协议已匹配，另一个协议仍可能匹配时继续等待后续字节。
        Map<String, RouteDefinition<byte[]>> routes = this.routes();
        assertNull(this.select("A", routes, 2, false));

        // 第二个字节排除待定协议后，选择唯一已经匹配的协议。
        assertEquals("immediate", this.select("AX", routes, 2, false));
    }

    @Test
    public void streamRejectsPendingRouteAtProbeLimit() {
        // 达到探测上限后仍有候选无法判定，不能固定已经匹配的另一个协议。
        Map<String, RouteDefinition<byte[]>> routes = this.routes();
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> {
            this.select("A", routes, 1, false);
        });
        assertTrue(failure.getMessage().contains("probe limit"));
    }

    @Test
    public void datagramSelectsTheCompleteMessageMatch() {
        // 数据报已经完整，不等待声明需要更多字节的其它探针。
        Map<String, RouteDefinition<byte[]>> routes = this.routes();
        assertEquals("immediate", this.select("A", routes, 1, true));
    }

    private Map<String, RouteDefinition<byte[]>> routes() {
        Map<String, RouteDefinition<byte[]>> routes = new LinkedHashMap<>();
        routes.put("immediate", this.route(prefix -> RouteProbeResult.MATCH));
        routes.put("extended", this.route(prefix -> {
            if (prefix.length < 2) {
                return RouteProbeResult.NEED_MORE;
            }
            return prefix[1] == 'B' ? RouteProbeResult.MATCH : RouteProbeResult.REJECT;
        }));
        return routes;
    }

    private RouteDefinition<byte[]> route(Function<byte[], RouteProbeResult> probe) {
        return RouteTestAccess.definition(Collections.emptyMap(), null, probe);
    }

    private String select(String value, Map<String, RouteDefinition<byte[]>> routes, int limit, boolean datagram) {
        ByteBuf buffer = ByteBuf.wrap(value.getBytes(StandardCharsets.US_ASCII));
        try {
            return RouteByteUtils.select(Collections.singletonList(buffer), routes, limit, datagram);
        } finally {
            buffer.release();
        }
    }
}
