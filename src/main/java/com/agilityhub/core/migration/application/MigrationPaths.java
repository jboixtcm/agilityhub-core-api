package com.agilityhub.core.migration.application;

import com.agilityhub.core.shared.domain.*;
import java.io.IOException;
import java.nio.file.*;

/** R-18-01: raw input and symlink targets must stay outside every repository and Dropbox tree. */
final class MigrationPaths {
    private MigrationPaths() { }
    static Path input(Path path) {
        try {
            Path real = path.toRealPath();
            for (Path parent = real; parent != null; parent = parent.getParent()) {
                if (Files.exists(parent.resolve(".git")) || parent.getFileName() != null
                        && parent.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("dropbox")) {
                    throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH);
                }
            }
            if (!Files.isDirectory(real)) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
            try (var entries = Files.list(real)) {
                for (Path entry : entries.toList()) {
                    if (Files.isSymbolicLink(entry)) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                }
            }
            return real;
        } catch (IOException failure) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
    }
}
