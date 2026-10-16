/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.hprose.codec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hprose.io.HproseWriter;
import net.hasor.meteor.domain.ProtocolStatus;
import net.hasor.meteor.domain.payload.RequestPayload;
import net.hasor.meteor.domain.payload.ResponsePayload;

/** 编解码 Hprose 调用和响应的线协议正文。 */
final class HproseWireCodec {
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper() //
            .setSerializationInclusion(JsonInclude.Include.NON_NULL) //
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private HproseWireCodec() {
    }

    static JsonNode decodeErrorDetails(String error) throws IOException {
        return ERROR_MAPPER.readTree(error);
    }

    static void writeResponse(long requestId, ResponsePayload response, OutputStream output) throws IOException {
        boolean accepted = response.getStatus() == ProtocolStatus.Accept;
        if (response.getStatus() == ProtocolStatus.OK || accepted) {
            output.write('R');
            Object result = accepted ? null : response.getReturnData();
            new HproseWriter(output).serialize(result);
            output.write('z');
            return;
        }

        Map<String, String> errorDetails = new LinkedHashMap<>();
        String[] optionKeys = response.getOptionKeys();
        if (optionKeys != null) {
            for (String optionKey : optionKeys) {
                errorDetails.put(optionKey, response.getOption(optionKey));
            }
        }
        errorDetails.put("requestID", String.valueOf(requestId));
        errorDetails.put("status", String.valueOf(response.getStatus()));

        String jsonData = ERROR_MAPPER.writeValueAsString(errorDetails);
        output.write('E');
        new HproseWriter(output).writeString(jsonData);
        output.write('z');
    }

    static byte[] encodeRequest(String alias, RequestPayload request) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write('C');
        HproseWriter writer = new HproseWriter(frame);
        writer.writeString(alias + "_" + request.getTargetMethod());
        writer.reset();
        writer.writeArray(request.getParameterValues().toArray());
        frame.write('z');
        return frame.toByteArray();
    }

    static byte[] encodeResponseBatch(byte[][] responses) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] response : responses) {
            body.write(response);
        }
        body.write('z');
        return body.toByteArray();
    }
}
