package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.LocalExportStorage;
import com.agilityhub.core.shared.application.ExportFileStore;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * {@link ExportFileStore} over the export store itself (E8-T03): the files go to the same `ExportStorage` as the list exports,
 * under their caller's own key prefix. The local store signs the caller's route with its key — the parts are the route, the
 * club, a fixed marker and the expiry — and checks that signature in constant time; S3 presigns its own URL.
 */
@Service
public class ExportFileStoreAdapter implements ExportFileStore {
    static final String ROUTE = "signed-route";
    private final ExportStorage storage; private final Clock clock;
    public ExportFileStoreAdapter(ExportStorage storage, Clock clock) { this.storage = storage; this.clock = clock; }

    @Override public void put(String key, Path file, String contentType) { storage.put(key, file, contentType); }
    @Override public void delete(String key) { storage.delete(key); }
    @Override public InputStream open(String key) { return storage.open(key); }

    @Override public String downloadUrl(String key, String fileName, String clubId, String localPath, Instant expiresAt) {
        if (storage instanceof LocalExportStorage local) {
            long expires = expiresAt.getEpochSecond();
            return localPath + "?expires=" + expires + "&signature=" + local.signature(localPath, clubId, ROUTE, expires);
        }
        return storage.downloadUrl(key, clubId, ROUTE, key, fileName, expiresAt);
    }

    @Override public void verifyLocal(String clubId, String localPath, long expires, String signature) {
        if (!(storage instanceof LocalExportStorage local)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        boolean valid = signature != null && clubId != null
                && MessageDigest.isEqual(local.signature(localPath, clubId, ROUTE, expires).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
        if (!valid || !clock.instant().isBefore(Instant.ofEpochSecond(expires))) { throw new ApiException(ErrorCode.FORBIDDEN); }
    }
}
