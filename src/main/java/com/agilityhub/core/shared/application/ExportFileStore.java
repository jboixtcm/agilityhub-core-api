package com.agilityhub.core.shared.application;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;

/**
 * The api's private export store (`clubs.common`'s `ExportStorage`: S3 in staging and production, the local directory in the
 * `local` and `test` profiles) for the contexts that cannot import `clubs.common` (E8-T03: the SEPA remittance files of
 * `payments`, S12 R-12-12). It is the same store, not a second one: `clubs.common` implements this port over it.
 *
 * <p>A download is a short-lived signed URL (CONVENCIONS_API §5): on S3 the store's presigned GET answering
 * `Content-Disposition: attachment` with the file's name and its stored type; on the local store the caller's own route
 * {@code localPath}, signed with the store's key over the club, the path and the expiry, which authorises itself (A31) — the
 * route checks it with {@link #verifyLocal} before it opens anything.
 */
public interface ExportFileStore {
    void put(String key, Path file, String contentType);
    void delete(String key);
    InputStream open(String key);
    String downloadUrl(String key, String fileName, String clubId, String localPath, Instant expiresAt);
    /** The local store's check of a {@link #downloadUrl}: a wrong or expired signature is `403 FORBIDDEN` (CONVENCIONS_API §5); `404` on S3. */
    void verifyLocal(String clubId, String localPath, long expires, String signature);
}
