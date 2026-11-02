/*
 * Copyright 2015-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0.
 * See the LICENSE.txt file for the full license.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package net.hasor.meteor.protocol.thrift.codec;
import org.apache.thrift.TConfiguration;
import org.apache.thrift.transport.TEndpointTransport;
import org.apache.thrift.transport.TTransportException;

/** The budget covers a complete message; unavailable bytes remain distinguishable from invalid lengths. */
final class ThriftInputTransport extends TEndpointTransport {
    private byte[] bytes;
    private int    start;
    private int    end;
    private int    position;

    ThriftInputTransport(byte[] bytes, int offset, int length, //
            TConfiguration configuration) throws TTransportException {
        super(configuration);
        this.bytes = bytes;
        this.start = offset;
        this.position = offset;
        this.end = offset + length;
    }

    void update(byte[] bytes, int offset, int length) {
        this.position += offset - this.start;
        this.start = offset;
        this.bytes = bytes;
        this.end = offset + length;
    }

    int position() {
        return this.position;
    }

    long remainingBudget() {
        return this.remainingMessageSize;
    }

    void restore(int position, long remaining) {
        this.position = position;
        this.remainingMessageSize = remaining;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void open() {
    }

    @Override
    public void close() {
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws TTransportException {
        this.checkReadBytesAvailable(length);
        System.arraycopy(this.bytes, this.position, buffer, offset, length);
        this.position += length;
        this.countConsumedMessageBytes(length);
        return length;
    }

    @Override
    public void checkReadBytesAvailable(long bytes) throws TTransportException {
        super.checkReadBytesAvailable(bytes);
        if (bytes > this.end - this.position) {
            throw new TTransportException(TTransportException.END_OF_FILE, "Incomplete Thrift message");
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws TTransportException {
        throw new TTransportException("Read-only Thrift transport");
    }
}
