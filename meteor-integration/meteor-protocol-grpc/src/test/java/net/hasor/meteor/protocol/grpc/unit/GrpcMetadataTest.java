/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.grpc.unit;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.hasor.meteor.domain.OptionInfo;
import net.hasor.meteor.protocol.grpc.GrpcMetadata;
import org.junit.Test;
import static org.junit.Assert.*;

public class GrpcMetadataTest {
    @Test
    public void metadataRoundTripsThroughOrdinaryStringOptions() throws Exception {
        // 在普通选项上写入重复头部与二进制尾部。
        OptionInfo source = new OptionInfo();
        GrpcMetadata metadata = GrpcMetadata.from(source);
        metadata.addHeader("x-tag", "first");
        metadata.addHeader("x-tag", "second");
        metadata.addBinaryTrailer("receipt-bin", new byte[] { 0, -1 });
        source.addOption("other-protocol", "unchanged");

        // 特殊选项使用普通 JSON 字符串，复制后不依赖原来的元数据对象。
        String json = source.getOption("grpc-metadata-headers");
        JsonNode headers = new ObjectMapper().readTree(json);
        assertEquals("first", headers.get("x-tag").get(0).asText());
        assertEquals("second", headers.get("x-tag").get(1).asText());

        // 通过 options 复制还原元数据，保留重复值和二进制数据。
        OptionInfo copied = new OptionInfo();
        copied.addOptionMap(source);
        GrpcMetadata decoded = GrpcMetadata.from(copied);
        assertEquals(List.of("first", "second"), decoded.getHeader("x-tag"));
        assertArrayEquals(new byte[] { 0, -1 }, decoded.getBinaryTrailer("receipt-bin").get(0));
    }

    @Test
    public void copiedMetadataDoesNotMutateItsSource() throws Exception {
        // 准备原始元数据及另一个协议的普通选项。
        OptionInfo source = new OptionInfo();
        GrpcMetadata metadata = GrpcMetadata.from(source);
        metadata.addHeader("x-tag", "first");
        source.addOption("other-protocol", "unchanged");

        // 只修改复制后的元数据，验证源数据和无关选项保持不变。
        OptionInfo copied = new OptionInfo();
        copied.addOptionMap(source);
        GrpcMetadata.from(copied).addHeader("x-tag", "second");
        assertEquals(List.of("first"), metadata.getHeader("x-tag"));
        assertEquals("unchanged", copied.getOption("other-protocol"));
    }

    @Test
    public void retainedMetadataViewReadsLateTrailers() throws Exception {
        // 响应选项引用同一份 payload，并提前获取元数据视图。
        OptionInfo payload = new OptionInfo();
        OptionInfo response = new OptionInfo();
        response.setOptions(payload);
        GrpcMetadata view = GrpcMetadata.from(response);
        assertTrue(view.getTrailers().isEmpty());

        // 提前取得的视图每次读取当前 options，能够看到流结束后补入的尾部。
        GrpcMetadata.from(payload).addTrailer("x-result", "finished");
        assertEquals(List.of("finished"), view.getTrailer("x-result"));
    }

    @Test
    public void retainedMetadataViewObservesOptionRemoval() throws Exception {
        // 先通过元数据视图写入尾部。
        OptionInfo options = new OptionInfo();
        GrpcMetadata view = GrpcMetadata.from(options);
        view.addTrailer("x-result", "finished");

        // 移除底层选项后，既有视图不保留旧快照。
        options.removeOption("grpc-metadata-trailers");
        assertTrue(view.getTrailers().isEmpty());
    }

    @Test
    public void malformedMetadataFailsOnlyWhenItsOptionIsParsed() {
        // 将普通选项与非法的协议专用 JSON 放在同一个对象。
        OptionInfo options = new OptionInfo();
        options.addOption("ordinary", "still-readable");
        options.addOption("grpc-metadata-headers", "{invalid");
        GrpcMetadata metadata = GrpcMetadata.from(options);

        // 内核不解释 JSON；协议扩展读取该选项时保留解析失败的原因。
        assertEquals("still-readable", options.getOption("ordinary"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, metadata::getHeaders);
        assertTrue(failure.getCause() instanceof JsonProcessingException);
    }
}
