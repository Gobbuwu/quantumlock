package com.quantumlock.security;

import java.io.IOException;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

/**
 * Wraps a request body stream, counts the bytes that are read and throws
 * {@link RequestTooLargeException} as soon as the body turns out to be larger than the limit.
 */
final class LimitedServletInputStream extends ServletInputStream {

    private final ServletInputStream delegate;
    private final long maxBytes;
    private long bytesRead;

    LimitedServletInputStream(ServletInputStream delegate, long maxBytes) {
        this.delegate = delegate;
        this.maxBytes = maxBytes;
    }

    @Override
    public int read() throws IOException {
        failIfOverLimit();
        int value = delegate.read();
        if (value != -1) {
            count(1);
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        failIfOverLimit();
        // Never ask for more than one byte past the limit, so an oversized body is not pulled into memory.
        long allowed = maxBytes - bytesRead + 1;
        int count = delegate.read(buffer, offset, (int) Math.min(length, allowed));
        if (count > 0) {
            count(count);
        }
        return count;
    }

    private void count(int count) throws IOException {
        bytesRead += count;
        failIfOverLimit();
    }

    private void failIfOverLimit() throws IOException {
        if (bytesRead > maxBytes) {
            throw new RequestTooLargeException(maxBytes);
        }
    }

    @Override
    public int available() throws IOException {
        return delegate.available();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public boolean isFinished() {
        return delegate.isFinished();
    }

    @Override
    public boolean isReady() {
        return delegate.isReady();
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        delegate.setReadListener(readListener);
    }
}
