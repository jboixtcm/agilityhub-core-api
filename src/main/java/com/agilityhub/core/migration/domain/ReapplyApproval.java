package com.agilityhub.core.migration.domain;

import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.time.*;
import java.util.*;

/** Written operator approval. Only its content digest is persisted; no free text enters CLI or incident logs. */
public record ReapplyApproval(String clubId, String priorRunId, String inputManifestSha256, LocalDate cutover,
        MigrationSteps steps, String approvedBy, Instant approvedAt, String reason, String runbookReference, String digest) {
    private static final Set<String> FIELDS=Set.of("version","clubId","priorRunId","inputManifestSha256","cutover","steps",
            "approvedBy","approvedAt","reason","runbookReference");

    public static ReapplyApproval read(Path path) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)) { throw refused(); }
            Path real=path.toRealPath();
            for(Path parent=real.getParent();parent!=null;parent=parent.getParent()) {
                if (Files.exists(parent.resolve(".git")) || parent.getFileName()!=null
                        && parent.getFileName().toString().toLowerCase(Locale.ROOT).contains("dropbox")) { throw refused(); }
            }
            var permissions=Files.getPosixFilePermissions(real);
            if (permissions.stream().anyMatch(permission->!Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE).contains(permission))) { throw refused(); }
            byte[] bytes;
            try(var stream=Files.newInputStream(real)) { bytes=stream.readNBytes(16_385); }
            if (bytes.length==0 || bytes.length>16_384) { throw refused(); }
            var json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            if (json==null || !json.isObject()) { throw refused(); }
            var names=new HashSet<String>(); json.fieldNames().forEachRemaining(names::add);
            if (!names.equals(FIELDS) || !json.path("version").isIntegralNumber() || !json.path("version").canConvertToInt() || json.path("version").intValue()!=1
                    || !json.path("steps").isArray() || json.path("steps").isEmpty()) { throw refused(); }
            var selected=new TreeSet<Integer>();
            for(var value:json.path("steps")) {
                if (!value.isIntegralNumber() || !value.canConvertToInt() || !selected.add(value.intValue())) { throw refused(); }
            }
            String manifest=text(json,"inputManifestSha256");
            if (!manifest.matches("[a-f0-9]{64}")) { throw refused(); }
            return new ReapplyApproval(text(json,"clubId"),text(json,"priorRunId"),manifest,LocalDate.parse(text(json,"cutover")),
                    new MigrationSteps(selected),text(json,"approvedBy"),Instant.parse(text(json,"approvedAt")),text(json,"reason"),
                    text(json,"runbookReference"),sha256(bytes));
        } catch (IOException | IllegalArgumentException | UnsupportedOperationException | DateTimeException failure) { throw refused(); }
    }

    public boolean matches(String club, String previous, InputManifest manifest, LocalDate date, MigrationSteps selection, Instant now, Instant priorStartedAt) {
        return clubId.equals(club) && priorRunId.equals(previous) && inputManifestSha256.equals(manifestSha256(manifest))
                && cutover.equals(date) && steps.equals(selection) && !approvedAt.isAfter(now)
                && (priorStartedAt==null || !approvedAt.isBefore(priorStartedAt));
    }

    /** Stable across manifest file ordering; binds filenames, rows, file bytes and the complete mapping fingerprint. */
    public static String manifestSha256(InputManifest manifest) {
        if (manifest==null || manifest.mappingSha256()==null) { throw refused(); }
        try {
            var bytes=new ByteArrayOutputStream();
            try(var output=new DataOutputStream(bytes)) {
                write(output,"agilityhub-migration-manifest-v1"); write(output,manifest.mappingSha256());
                var files=manifest.files().stream().sorted(Comparator.comparing(InputManifest.File::name)).toList();
                output.writeInt(files.size());
                for(var file:files) { write(output,file.name()); write(output,file.sha256()); output.writeInt(file.rows()); }
            }
            return sha256(bytes.toByteArray());
        } catch(IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void write(DataOutputStream output,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8); output.writeInt(bytes.length); output.write(bytes);
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static String text(JsonNode json,String field) {
        var value=json.path(field);
        if (!value.isTextual() || value.textValue().isBlank() || value.textValue().length()>2_000
                || value.textValue().codePoints().anyMatch(Character::isISOControl)) { throw refused(); }
        return value.textValue();
    }
    private static ApiException refused() { return new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION); }
}
