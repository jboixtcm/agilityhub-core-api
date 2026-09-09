package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.clubs.common.application.ExportStorage;
import com.agilityhub.core.clubs.common.domain.ExportNames;
import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.security.MessageDigest;
import java.util.HexFormat;

public class LocalExportStorage implements ExportStorage {
    private final Path root; private final byte[] key; private final Clock clock;
    public LocalExportStorage(Path root, byte[] key, Clock clock) { this.root = root.toAbsolutePath().normalize(); this.key = key.clone(); this.clock = clock; }
    private Path path(String name) {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || path.equals(root)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return path;
    }
    @Override public void put(String name, Path source, String contentType) {
        Path destination = path(name);
        try {
            Files.createDirectories(destination.getParent(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Path temporary = Files.createTempFile(destination.getParent(), ".upload-", ".tmp");
            try { Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING); Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------")); Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            finally { Files.deleteIfExists(temporary); }
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    @Override public InputStream open(String name) {
        try { return Files.newInputStream(path(name)); }
        catch (NoSuchFileException ex) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    @Override public void delete(String name) {
        try { Files.deleteIfExists(path(name)); } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    @Override public String downloadUrl(String id, String club, String owner, String file, String name, Instant expiresAt) {
        long expires = expiresAt.getEpochSecond();
        return "/api/v1/exports/" + id + "/download?expires=" + expires + "&signature=" + signature(id, club, owner, expires);
    }
    private String signature(String id, String club, String owner, long expires) { return ExportNames.hmac(id + "\n" + club + "\n" + owner + "\n" + expires, key); }
    public void verify(String id, String club, String owner, Instant expiresAt, long expires, String signature) {
        if (expires != expiresAt.getEpochSecond() || !clock.instant().isBefore(Instant.ofEpochSecond(expires))) { throw new ApiException(ErrorCode.EXPORT_EXPIRED); }
        try {
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(signature(id, club, owner, expires)), HexFormat.of().parseHex(signature))) { throw new ApiException(ErrorCode.FORBIDDEN); }
        } catch (IllegalArgumentException ex) { throw new ApiException(ErrorCode.FORBIDDEN); }
    }
}
