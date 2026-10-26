package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.migration.application.MigrationReportStore;
import com.agilityhub.core.shared.application.PrivateFiles;
import com.agilityhub.core.shared.application.PrivateFileStorageSettings;
import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** S18 R-18-15: private internal-deployment reports, purged by the existing 30-day retention job. */
public final class VolumeMigrationReportStore implements MigrationReportStore {
    private final PrivateFileStorageSettings settings;
    private final Path root;
    private final Clock clock;
    public VolumeMigrationReportStore(PrivateFileStorageSettings settings, Clock clock) {
        this.settings = settings; this.root = settings.directory("migration-reports"); this.clock = clock;
    }
    @Override public void requireReady() { settings.requireReady(); }
    private Path path(String key) {
        requireReady();
        if (key == null || !key.matches("migration-reports/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+/report\\.xlsx")) {
            throw new ApiException(ErrorCode.NOT_FOUND);
        }
        return PrivateFiles.resolve(root, key.substring("migration-reports/".length()));
    }
    @Override public void put(String key, Path file, Instant expiresAt) {
        active(expiresAt);
        Path destination = path(key);
        PrivateFiles.createDirectory(destination.getParent());
        try {
            Path temporary = Files.createTempFile(destination.getParent(), ".report-", ".tmp");
            try {
                Files.copy(file, temporary, StandardCopyOption.REPLACE_EXISTING);
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
        if (!clock.instant().isBefore(expiresAt)) { delete(key); throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
    }
    @Override public String downloadUrl(String key, Instant expiresAt) {
        active(expiresAt); path(key);
        long expires = Math.min(expiresAt.getEpochSecond(), clock.instant().plusSeconds(300).getEpochSecond());
        String[] parts = key.split("/");
        return "/api/v1/migrations/files/" + parts[1] + "/" + parts[2] + "?expires=" + expires + "&signature=" + signature(key, expires);
    }
    public void authorize(String key, long expires, String signature) {
        path(key);
        if (signature == null || !MessageDigest.isEqual(signature(key, expires).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        active(Instant.ofEpochSecond(expires));
        if (expires > clock.instant().plusSeconds(300).getEpochSecond()) { throw new ApiException(ErrorCode.FORBIDDEN); }
    }
    public InputStream open(String key) {
        try { return Files.newInputStream(path(key), LinkOption.NOFOLLOW_LINKS); }
        catch (NoSuchFileException missing) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    @Override public void delete(String key) {
        try { Files.deleteIfExists(path(key)); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private void active(Instant expires) {
        if (!clock.instant().isBefore(expires)) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
    }
    private String signature(String key, long expires) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(settings.signingKey(), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(("migration-report\n" + key + "\n" + expires).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
}
