package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;

/** Local presigned URLs require a bearer token, like the local export adapter. */
public class LocalAttachmentStorage implements AttachmentStorage {
    private final Path root; private final byte[] key; private final Clock clock;
    
    public LocalAttachmentStorage(Path root, byte[] key, Clock clock) {
        this.root = root.toAbsolutePath().normalize(); this.key = key.clone(); this.clock = clock;
        try { Files.createDirectories(this.root); Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwx------")); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
    private Path path(String id) {
        if (!id.matches("[0-9a-f-]{36}")) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return root.resolve(id);
    }
    private String signature(String id, long expires, String method) {
        try {
            var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((id + "|" + expires + "|" + method).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException failure) { throw new IllegalStateException(failure); }
    }
    public void authorize(String id, long expires, String token, String method) {
        if (expires <= clock.instant().getEpochSecond() || token == null || !MessageDigest.isEqual(signature(id, expires, method).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
    }
    @Override public String uploadUrl(String id, String type, long size, Instant expires) {
        return url(id, expires, "uploads", "PUT");
    }
    @Override public String downloadUrl(String id, String name, Instant expires) { return url(id, expires, "files", "GET"); }
    private String url(String id, Instant expires, String route, String method) {
        return "/api/v1/attachments/" + route + "/" + id + "?expires=" + expires.getEpochSecond() + "&signature=" + signature(id, expires.getEpochSecond(), method);
    }
    public void put(String id, String mimeType, long expected, InputStream input) throws IOException {
        Path target = path(id); Path temp = Files.createTempFile(root, "upload-", ".part");
        try (var output = Files.newOutputStream(temp)) {
            Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            byte[] bytes = new byte[8192]; long count = 0;
            for (int read; (read = input.read(bytes)) != -1;) {
                count += read; if (count > expected) { throw new ApiException(ErrorCode.FILE_TOO_LARGE); } output.write(bytes, 0, read);
            }
            if (count != expected) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        } catch (IOException | RuntimeException failure) { Files.deleteIfExists(temp); throw failure; }
        try { Files.createLink(target, temp);
            Files.writeString(root.resolve(id + ".mime"), mimeType, StandardOpenOption.CREATE_NEW);
            Files.setPosixFilePermissions(root.resolve(id + ".mime"), PosixFilePermissions.fromString("rw-------")); }
        finally { Files.deleteIfExists(temp); }
    }
    public InputStream open(String id) throws IOException { return Files.newInputStream(path(id)); }
    @Override public Metadata metadata(String id) {
        try {
            if (!Files.isRegularFile(path(id), LinkOption.NOFOLLOW_LINKS)) { throw new ApiException(ErrorCode.NOT_FOUND); }
            return new Metadata(Files.readString(root.resolve(id + ".mime")), Files.size(path(id)));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
