package com.agilityhub.core.shared.application;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import org.springframework.core.env.Environment;

/** Explicit internal-deployment storage. The host provisions and verifies the encrypted volume mount. */
public final class PrivateFileStorageSettings {
    public static final String MARKER = ".clubs-private-storage";
    public static final String MARKER_CONTENT = "clubs-private-storage-v1";
    private final Path root;
    private final byte[] signingKey;

    private PrivateFileStorageSettings(Path root, byte[] signingKey) { this.root = root; this.signingKey = signingKey; }

    public static PrivateFileStorageSettings from(Environment environment) {
        String mode = environment.getProperty("core.storage.mode", "s3");
        if ("s3".equals(mode)) { return new PrivateFileStorageSettings(null, null); }
        if (!"filesystem".equals(mode)) { throw new IllegalStateException("FILE_STORAGE_MODE must be s3 or filesystem"); }
        if (!HostedDemoPolicy.from(environment).enabled()) {
            throw new IllegalStateException("FILE_STORAGE_MODE=filesystem requires HOSTED_DEMO_ENABLED=true");
        }
        Path root = Path.of(environment.getProperty("core.storage.root", ""));
        if (!root.isAbsolute() || !root.equals(root.normalize()) || root.getParent() == null) {
            throw new IllegalStateException("FILE_STORAGE_ROOT must be an absolute dedicated directory");
        }
        byte[] key;
        try { key = Base64.getDecoder().decode(environment.getProperty("core.storage.signing-key", "")); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("FILE_STORAGE_SIGNING_KEY must be base64", invalid); }
        if (key.length < 32) { throw new IllegalStateException("FILE_STORAGE_SIGNING_KEY requires at least 32 random bytes"); }
        var settings = new PrivateFileStorageSettings(root, key);
        settings.requireReady();
        return settings;
    }

    public byte[] signingKey() { return signingKey.clone(); }

    public Path directory(String name) {
        requireReady();
        Path directory = PrivateFiles.resolve(root, name);
        PrivateFiles.createDirectory(directory);
        return directory;
    }

    /** Also called before I/O: missing mounts/markers or permission drift never create a fallback store. */
    public void requireReady() {
        if (root == null) { throw new IllegalStateException("Filesystem storage is not enabled"); }
        try {
            for (Path path = root; path != null; path = path.getParent()) {
                if (Files.isSymbolicLink(path)) { throw unavailable(); }
            }
            Path marker = root.resolve(MARKER);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------"))
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(marker).equals(PosixFilePermissions.fromString("rw-------"))
                    || Files.size(marker) > 128 || !MARKER_CONTENT.equals(Files.readString(marker).strip())
                    || !Files.isWritable(root)) { throw unavailable(); }
        } catch (IOException failure) { throw new IllegalStateException("Private volume storage is unavailable", failure); }
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("FILE_STORAGE_ROOT requires an existing private 0700 directory and 0600 " + MARKER + " marker on the encrypted volume");
    }
}
