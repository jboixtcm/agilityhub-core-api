package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.CourseTypes.UploadPurpose;
import com.agilityhub.core.courses.persistence.PlacementRepository;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S16 R-16-10 (E9-T05; T-16-19): a placement's build sheet. course-ui renders the PDF on the client (DECISIONS_PENDENTS S16 (b),
 * R-16-10's «decisió: client») and uploads it with `POST /courses/upload-urls {purpose: BUILD_SHEET}`; `POST /placements/{id}/build-sheet
 * {fileKey}` binds that upload to the placement — the club's own PDF of that purpose, else `404` (R-16-16) — and replaces the previous
 * sheet; `GET` answers the stored PDF, `404` without one. A sheet is not an edit of the placement: its `version` stays, so the
 * client's next `PUT` is not stale (the `PUT` keeps the sheet). A race with another write of the placement is `409 STALE_VERSION`.
 * INC-62 (E11-T02): the replaced sheet's file is released; a `PUT` that moves the placement clears and releases its sheet.
 */
@Service
public class BuildSheetService {
    private final PlacementRepository placements;
    private final PlacementService views;
    private final CourseFiles files;
    private final Clock clock;
    public BuildSheetService(PlacementRepository placements, PlacementService views, CourseFiles files, Clock clock) {
        this.placements = placements; this.views = views; this.files = files; this.clock = clock;
    }

    @Transactional
    public PlacementService.PlacementView attach(String placementId, String fileKey) {
        var placement = placements.findById(placementId).orElseThrow(() -> new ApiException(NOT_FOUND));
        files.claim(fileKey, UploadPurpose.BUILD_SHEET, PlacementService.ENTITY, placement.id());
        WriteConflicts.guard(() -> placements.attachSheet(placement.id(), fileKey, clock.instant(), CourseAccess.accountId()));
        // INC-62 (E11-T02): the sheet it replaces is released, never left bound to the placement.
        if (placement.buildSheetFileKey() != null && !placement.buildSheetFileKey().equals(fileKey)) { files.release(placement.buildSheetFileKey()); }
        return views.get(placement.id());
    }

    public byte[] sheet(String placementId) {
        var placement = placements.findById(placementId).orElseThrow(() -> new ApiException(NOT_FOUND));
        if (placement.buildSheetFileKey() == null) { throw new ApiException(NOT_FOUND); }
        return files.read(placement.buildSheetFileKey());
    }
}
