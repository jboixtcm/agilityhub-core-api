package com.agilityhub.core.migration.api;

import com.agilityhub.core.migration.application.MigrationReports;
import com.agilityhub.core.shared.application.ContentSecurityPolicies;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Only follows a private signed link issued by the audited, role-checked report endpoint. */
@RestController
@io.swagger.v3.oas.annotations.Hidden
public class MigrationFilesController {
    private final MigrationReports reports;
    public MigrationFilesController(MigrationReports reports) { this.reports = reports; }
    @com.agilityhub.core.shared.application.SupportRestricted
    @GetMapping("/api/v1/migrations/files/{clubId}/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String clubId, @PathVariable String id,
            @RequestParam long expires, @RequestParam String signature) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("report.xlsx").build().toString())
                .header("Content-Security-Policy", ContentSecurityPolicies.DOWNLOAD)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new InputStreamResource(reports.openVolume(clubId, id, expires, signature)));
    }
}
