package com.agilityhub.core.clubs.common.application;

import java.io.*;
import java.nio.file.Path;
import java.time.Instant;

/** Files are streamed from private spool files, never buffered as an unbounded byte array. */
public interface ExportStorage {
    void put(String key, Path file, String contentType);
    InputStream open(String key);
    void delete(String key);
    String downloadUrl(String id, String clubId, String owner, String key, String name, Instant expiresAt);
}
