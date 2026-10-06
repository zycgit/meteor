/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.rsf.protocol.hprose;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import hprose.io.HproseReader;
import hprose.io.HproseWriter;
import net.hasor.rsf.RsfContext;
import net.hasor.rsf.address.InterAddress;
import net.hasor.rsf.connector.ProtocolConfig;
import net.hasor.rsf.connector.transport.http.HttpRequest;
import net.hasor.rsf.connector.transport.http.HttpResponse;
import net.hasor.rsf.domain.ProtocolStatus;
import net.hasor.rsf.domain.payload.RequestPayload;
import net.hasor.rsf.domain.payload.ResponsePayload;

/** HTTP/Hprose request and response mapping. */
final class HproseCodec {
    private final String     path;
    private final RsfContext context;
    // Hprose carries no request ID; these IDs correlate inbound HTTP requests with their replies.
    private final AtomicLong incomingIds = new AtomicLong();

    public HproseCodec(ProtocolConfig config, RsfContext context) {
        this.path = config.option("contextPath", "/hprose").replaceAll("/+$", "");
        this.context = context;
    }

    public HproseInvocation receive(HttpRequest request) throws Exception {
        String uri = request.uri().split("\\?", 2)[0];
        if (!uri.equals(this.path) && !uri.startsWith(this.path + "/")) {
            return HproseInvocation.respond(new HttpResponse(404, Collections.emptyMap(), new byte[0]));
        }
        ByteArrayInputStream input = new ByteArrayInputStream(request.body());
        int tag = input.read();
        Map<String, String> headers = this.headers(request.headers().get("Origin"));
        if (tag == 'z') {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write('F');
            new HproseWriter(output).writeArray(HproseUtils.doFunction(this.context));
            output.write('z');
            return HproseInvocation.respond(new HttpResponse(200, headers, output.toByteArray()));
        }
        if (tag != 'C') {
            throw new IOException("Invalid Hprose call tag: " + tag);
        }
        RequestPayload[] calls = HproseUtils.doCall(this.context, input, request.uri(), request.headers().get("Origin"));
        if (calls.length != 1) {
            throw new IOException("Expected exactly one Hprose call");
        }
        calls[0].setRequestID(this.incomingIds.incrementAndGet());
        return HproseInvocation.dispatch(calls[0], response -> {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            HproseUtils.parseResponse(response.getRequestID(), response, output);
            return new HttpResponse(200, headers, output.toByteArray());
        });
    }

    public HttpRequest encode(InterAddress target, RequestPayload request) throws Exception {
        String uri = this.path + "/" + URLEncoder.encode(request.getServiceGroup(), "UTF-8") + "/" + URLEncoder.encode(request.getServiceName(), "UTF-8") + "/" + URLEncoder.encode(request.getServiceVersion(), "UTF-8");
        Map<String, String> headers = new LinkedHashMap<>();
        for (String key : request.getOptionKeys()) {
            headers.put(key, request.getOption(key));
        }
        headers.put("Content-Type", "application/hprose");
        return new HttpRequest("POST", uri, headers, HproseUtils.encodeRequest(this.context, request));
    }

    public ResponsePayload decode(long requestId, HttpResponse response) throws Exception {
        ResponsePayload result = new ResponsePayload();
        result.setRequestID(requestId);
        result.setReceiveTime(System.currentTimeMillis());
        result.setSerializeType("Hprose");
        result.setStatus((short) response.status());
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            result.addOption(header.getKey(), header.getValue());
        }
        if (response.status() == 200) {
            if (response.body().length > 0 && response.body()[0] == 'E') {
                ByteArrayInputStream input = new ByteArrayInputStream(response.body());
                input.read();
                String error = new HproseReader(input).readString();
                if (input.read() != 'z' || input.read() != -1) {
                    throw new IOException("Invalid Hprose error terminator");
                }
                result.setStatus(ProtocolStatus.InvokeError);
                result.addOption("message", error);
                // RSF peers carry a status and options in the Hprose error string.
                try {
                    JSONObject details = JSON.parseObject(error);
                    if (details != null && details.containsKey("status")) {
                        short status = details.getShortValue("status");
                        if (status != ProtocolStatus.OK && status != ProtocolStatus.Accept) {
                            result.setStatus(status);
                        }
                        if (details.getString("message") != null) {
                            result.addOption("message", details.getString("message"));
                        }
                    }
                } catch (RuntimeException ignored) {
                    /* Other Hprose peers send plain error strings. */
                }
            } else {
                result.setReturnData(HproseUtils.decodeResponse(new ByteArrayInputStream(response.body())));
            }
        } else {
            result.addOption("message", "HTTP status " + response.status());
        }
        return result;
    }

    public HttpResponse error(Throwable error) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write('E');
            new HproseWriter(output).writeString(String.valueOf(error.getMessage()));
            output.write('z');
            return new HttpResponse(200, this.headers(null), output.toByteArray());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private Map<String, String> headers(String origin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/hprose");
        headers.put("Access-Control-Allow-Origin", origin == null || "null".equals(origin) ? "*" : origin);
        if (origin != null && !"null".equals(origin)) {
            headers.put("Access-Control-Allow-Credentials", "true");
        }
        return headers;
    }
}
