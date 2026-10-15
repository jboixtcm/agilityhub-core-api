package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.application.ports.CourseFileStore;
import com.agilityhub.core.courses.domain.CourseTypes.UploadPurpose;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * R-16-16 (and R-16-02 for the Smarter source): course files are uploaded by signed URL under `{clubId}/courses/`. The type must be
 * in the club's `files.allowedTypes` (a `type/*` entry matches its family) and fit the purpose — `text/plain` only for
 * `SMARTER_SOURCE` (§9), images for `COURSE_IMAGE` and `THUMBNAIL`, a PDF for `BUILD_SHEET` — else `FILE_TYPE_NOT_ALLOWED`. The size
 * is 1 byte to `files.maxSizeMb`, and at most 512 KB for a Smarter export (R-16-02), else `FILE_TOO_LARGE {maxSizeMb}` (0.5 for the
 * Smarter export; E115, the same detail as every other upload route).
 */
@Service
public class CourseFiles {
    /** R-16-02: «Mida màxima 512 KB» for the original Smarter `.txt`. */
    static final long SMARTER_MAX_BYTES = 512L * 1024;
    private final CourseFileStore store;
    private final ClubConfigService configs;
    public CourseFiles(CourseFileStore store, ClubConfigService configs) { this.store = store; this.configs = configs; }

    public CourseFileStore.Upload upload(UploadPurpose purpose, String contentType, long size) {
        var config = configs.get(TenantContext.require());
        String type = contentType.strip().toLowerCase(Locale.ROOT);
        boolean club = config.get("files.allowedTypes", List.class).stream().anyMatch(raw -> {
            String item = raw.toString().toLowerCase(Locale.ROOT); return item.endsWith("/*") ? type.startsWith(item.substring(0, item.length() - 1)) : type.equals(item);
        });
        boolean fits = switch (purpose) {
            case SMARTER_SOURCE -> type.equals("text/plain");
            case COURSE_IMAGE, THUMBNAIL -> type.startsWith("image/");
            case BUILD_SHEET -> type.equals("application/pdf");
        };
        if (!club || !fits) { throw new ApiException(FILE_TYPE_NOT_ALLOWED); }
        long max = config.get("files.maxSizeMb", Integer.class) * 1024L * 1024;
        if (purpose == UploadPurpose.SMARTER_SOURCE) { max = Math.min(max, SMARTER_MAX_BYTES); }
        if (size <= 0 || size > max) { throw new ApiException(FILE_TOO_LARGE, Map.of("maxSizeMb", max / (1024.0 * 1024))); }
        return store.upload(purpose.name(), type, size);
    }
    /** A key the course stores for the first time: the current club's upload of that purpose, bound to the course (T-16-21). */
    public void claim(String fileKey, UploadPurpose purpose, String courseId) { claim(fileKey, purpose, "Course", courseId); }
    /** The same for another S16 entity (a ring setup's photo, E9-T03). */
    public void claim(String fileKey, UploadPurpose purpose, String entityType, String id) { store.claim(fileKey, purpose.name(), entityType + ":" + id); }
    public String url(String fileKey) { return fileKey == null ? null : store.downloadUrl(fileKey); }
}
