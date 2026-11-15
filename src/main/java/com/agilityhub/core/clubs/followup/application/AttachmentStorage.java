package com.agilityhub.core.clubs.followup.application;

import java.io.*;
import java.time.Instant;

public interface AttachmentStorage {
    record Metadata(String mimeType, long sizeBytes) { }
    String uploadUrl(String key, String mimeType, long sizeBytes, Instant expiresAt);
    String downloadUrl(String key, String name, Instant expiresAt);
    Metadata metadata(String key);
    /** Trusted migration upload, already checked against the same purpose/type/size rules as interactive uploads. */
    default void put(String key, String mimeType, long size, InputStream input) throws IOException { throw new UnsupportedOperationException(); }
    /** The bytes of a stored object (S16 R-16-10's build sheet, E9-T05); a missing one is `404 NOT_FOUND`. */
    default byte[] read(String key) throws IOException { throw new UnsupportedOperationException(); }
    /**
     * WEB-05: the bytes of a stored object, never reading more than {@code maxBytes} + 1 of them: a larger object is
     * `FILE_TOO_LARGE`, a missing one `404 NOT_FOUND`.
     */
    default byte[] read(String key, long maxBytes) throws IOException { throw new UnsupportedOperationException(); }
    /** The bytes of {@code input} up to {@code maxBytes}; one more is `FILE_TOO_LARGE`, and nothing past it is read. */
    static byte[] bounded(InputStream input, long maxBytes) throws IOException {
        if (maxBytes < 0 || maxBytes >= Integer.MAX_VALUE - 8) { throw new IllegalArgumentException("maxBytes"); }
        byte[] bytes = input.readNBytes((int) maxBytes + 1);
        if (bytes.length > maxBytes) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.FILE_TOO_LARGE); }
        return bytes;
    }
    /** S15 P9 cleanup of orphan signup uploads; deleting a missing object is not an error. */
    void delete(String key);
}
