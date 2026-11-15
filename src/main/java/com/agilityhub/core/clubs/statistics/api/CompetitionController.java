package com.agilityhub.core.clubs.statistics.api;

import com.agilityhub.core.clubs.statistics.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.DogOwnerAccess;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;

@RestController
@RequiresModule(Module.STATS)
public class CompetitionController {
    private final CompetitionImports imports; private final CompetitionStatistics statistics; private final DogOwnerAccess dogs;
    public CompetitionController(CompetitionImports imports, CompetitionStatistics statistics, DogOwnerAccess dogs) {
        this.imports = imports; this.statistics = statistics; this.dogs = dogs;
    }
    @GetMapping("/api/v1/me/dogs/{id}/competition-statistics")
    @PreAuthorize("hasRole('MEMBER')")
    @Operation(operationId = "myCompetitionStatistics", summary = "myCompetitionStatistics", description = "The member's own dog only. Explicit official FCAG net points and RSCE point classifications. Missing imports remain empty; no inferred official points.")
    public Statistics mine(@PathVariable String id, @RequestParam(required = false) Integer year, @RequestParam(required = false) String season) {
        dogs.requireDog(id, true, false); return statistics.read(id, year, season);
    }
    @GetMapping("/api/v1/dogs/{id}/competition-statistics")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR')")
    @Operation(operationId = "dogCompetitionStatistics", summary = "dogCompetitionStatistics", description = "Staff read of a dog of the authenticated club.")
    public Statistics dog(@PathVariable String id, @RequestParam(required = false) Integer year, @RequestParam(required = false) String season) {
        dogs.requireDog(id, false, false); return statistics.read(id, year, season);
    }
    @GetMapping("/api/v1/competition-imports")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(operationId = "competitionImports", summary = "competitionImports", description = "Last successfully imported snapshot per source and season, newest first. Requires STATS.")
    public List<Receipt> history() { return imports.history(); }
    @PostMapping("/api/v1/competition-imports/preview")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(operationId = "previewCompetitionImport", summary = "previewCompetitionImport", description = "FCAG public classification URL or saved HTML; FLOW_AGILITY individual run print HTML (saved or fetched using the club session provisioned on the server), or reviewed interchange CSV. Preview expires after 24 hours. Only a unique exact licence suggests a dog. No result changes until confirmation. Maximum 2 MB and 2000 rows.")
    public Preview preview(@RequestBody ImportRequest request, @RequestHeader("Idempotency-Key") UUID key) { return imports.preview(request, key.toString()); }
    @PostMapping("/api/v1/competition-imports/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(operationId = "confirmCompetitionImport", summary = "confirmCompetitionImport", description = "Atomically replace this source/season with the selected preview rows. Map result key to a licensed dog of this club; omitted rows are skipped. Replaying a confirmed preview with identical assignments returns its receipt. Stale previews cannot overwrite newer imports.")
    public Receipt confirm(@PathVariable String id, @RequestBody Confirmation request, @RequestHeader("Idempotency-Key") UUID key) {
        return imports.confirm(id, request);
    }
}
