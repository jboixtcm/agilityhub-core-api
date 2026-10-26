package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Private application-owned directories only; never follow an object or directory symlink. */
public final class PrivateFiles {
    private PrivateFiles() { }
    public static Path resolve(Path root, String name) {
        Path path = root.resolve(name).normalize();
        if (!path.startsWith(root) || path.equals(root)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        for (Path component = path; component != null && component.startsWith(root); component = component.getParent()) {
            if (Files.isSymbolicLink(component)) { throw new ApiException(ErrorCode.NOT_FOUND); }
        }
        return path;
    }
    public static void createDirectory(Path directory) {
        try {
            if (Files.isSymbolicLink(directory)) { throw new ApiException(ErrorCode.NOT_FOUND); }
            Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }
}
