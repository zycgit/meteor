/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.support.connector.protocol;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 原始 HTTP 对端仅组装和读取报文，不实现 Meteor 的分块解码或 RPC 分发。 */
public final class HttpWirePeer {
    private HttpWirePeer() {
    }

    public static byte[] request(String body) {
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        String headers = "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + content.length;
        String message = headers + "\r\n\r\n" + body;
        return message.getBytes(StandardCharsets.UTF_8);
    }

    public static Message readMessage(InputStream input) throws IOException {
        // 对端只读取带长度的报文；分块报文始终交给被测 Meteor 解码。
        String firstLine = readLine(input);
        int length = -1;
        String header;
        while (!(header = readLine(input)).isEmpty()) {
            if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(header.substring(15).trim());
            }
        }
        if (length < 0) {
            throw new IOException("Missing content length");
        }

        // 精确读满正文，避免下一个流水线消息的字节被提前消费。
        byte[] body = new byte[length];
        new DataInputStream(input).readFully(body);
        return new Message(firstLine, new String(body, StandardCharsets.UTF_8));
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != '\n') {
            if (value == -1) {
                throw new EOFException();
            }
            if (value != '\r') {
                bytes.write(value);
            }
        }
        return bytes.toString(StandardCharsets.US_ASCII);
    }

    public static final class Message {
        private final String firstLine;
        private final String body;

        private Message(String firstLine, String body) {
            this.firstLine = firstLine;
            this.body = body;
        }

        public String firstLine() {
            return this.firstLine;
        }

        public String body() {
            return this.body;
        }
    }
}
