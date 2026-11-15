package com.agilityhub.core.support;

import jakarta.servlet.ServletInputStream;
import java.io.InputStream;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * CFG-03: a POST whose JSON body of {@code size} bytes (`{`, spaces, `}`) is produced while it is read, never held, and that counts
 * the bytes a handler took, so a test can prove a large body was not read whole.
 */
public final class StreamedBody extends MockHttpServletRequest {
    private final long size;
    private long read;

    public StreamedBody(long size) {
        super("POST", "/");
        this.size = size;
        setContentType("application/json");
    }

    /** The bytes handed out so far. */
    public long bytesRead() { return read; }

    @Override
    public ServletInputStream getInputStream() {
        return new DelegatingServletInputStream(new InputStream() {
            @Override public int read() {
                if (read >= size) { return -1; }
                return at(read++);
            }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (length == 0) { return 0; }
                if (read >= size) { return -1; }
                int count = (int) Math.min(length, size - read);
                for (int i = 0; i < count; i++) { buffer[offset + i] = (byte) at(read + i); }
                read += count;
                return count;
            }
        });
    }

    private int at(long position) { return position == 0 ? '{' : position == size - 1 ? '}' : ' '; }
}
