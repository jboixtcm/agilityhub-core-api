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
    /** S15 P9 cleanup of orphan signup uploads; deleting a missing object is not an error. */
    void delete(String key);
}
