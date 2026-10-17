package com.agilityhub.core.shared.application;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * INC-43 (E11-T02; CONVENCIONS_API §5): the byte ranges of the api's own downloads of the local storage, as S3 answers them,
 * so that a video plays in Safari before the release (A31). Every answer says `Accept-Ranges: bytes`; one range (`bytes=a-b`,
 * `a-` or `-n`) answers `206` with that part and its `Content-Range`, and a range that starts past the file `416` with the
 * file's size in `Content-Range`. Several ranges, a malformed header or an `If-Range` (the local storage keeps no validator
 * to compare) answer the whole file, as RFC 9110 §14.2 allows.
 */
public final class ByteRanges {
    private ByteRanges() { }

    /** @param headers the whole file's headers (type, disposition, policy, cache); `content` is closed when no body is sent */
    public static ResponseEntity<InputStreamResource> answer(HttpHeaders headers, long size, InputStream content, HttpHeaders request) throws IOException {
        var answer = new HttpHeaders(); answer.addAll(headers);
        answer.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        var range = single(request);
        if (range == null) { return ResponseEntity.ok().headers(answer).contentLength(size).body(new InputStreamResource(content)); }
        long start = range.getRangeStart(size), end = range.getRangeEnd(size);
        if (start >= size) {
            content.close();
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes").header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        content.skipNBytes(start);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT).headers(answer).contentLength(end - start + 1)
                .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size).body(new InputStreamResource(new Part(content, end - start + 1)));
    }

    /** The request headers that decide the answer (`Range`, `If-Range`), for a handler that takes the servlet request. */
    public static HttpHeaders of(jakarta.servlet.http.HttpServletRequest request) {
        var headers = new HttpHeaders();
        for (String name : new String[] {HttpHeaders.RANGE, HttpHeaders.IF_RANGE}) {
            if (request.getHeader(name) != null) { headers.set(name, request.getHeader(name)); }
        }
        return headers;
    }

    /** The request's one byte range, or null when the whole file answers: no `Range`, several, a malformed one, or an `If-Range`. */
    private static HttpRange single(HttpHeaders request) {
        if (request.getFirst(HttpHeaders.RANGE) == null || request.getFirst(HttpHeaders.IF_RANGE) != null) { return null; }
        try {
            var ranges = request.getRange();
            return ranges.size() == 1 ? ranges.getFirst() : null;
        } catch (IllegalArgumentException malformed) { return null; }
    }

    /** The first {@code length} bytes of a stream: the body of a `206`. */
    private static final class Part extends FilterInputStream {
        private long left;
        Part(InputStream content, long length) { super(content); left = length; }
        @Override public int read() throws IOException {
            if (left <= 0) { return -1; }
            int next = super.read();
            if (next >= 0) { left--; }
            return next;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) { return -1; }
            int read = super.read(buffer, offset, (int) Math.min(length, left));
            if (read > 0) { left -= read; }
            return read;
        }
        @Override public long skip(long count) throws IOException {
            long skipped = super.skip(Math.min(count, left));
            left -= skipped;
            return skipped;
        }
        @Override public int available() throws IOException { return (int) Math.min(super.available(), left); }
        @Override public boolean markSupported() { return false; }
    }
}
