package com.agilityhub.core.migration.domain;

import java.util.List;

/** S18: only mapping-approved basenames, source hashes and physical data-row counts; no absolute paths. */
public record InputManifest(String mappingSha256, List<File> files) {
    public InputManifest(List<File> files) { this(null,files); }
    public InputManifest { files = List.copyOf(files); }
    public record File(String name, String sha256, int rows) { }
}
