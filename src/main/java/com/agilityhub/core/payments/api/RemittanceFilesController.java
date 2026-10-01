package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.RemittanceService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The local store's signed download of a remittance's pain.008 file (E8-T03; CONVENCIONS_API §5, A31): the URL of
 * `GET /remittances/{id}/file` authorises itself — no bearer is read, no tenant comes from the host
 * ({@code SignedFileRequests}) — and its service checks the signature before it opens the signed club. On S3 the link is the
 * store's own presigned URL and this route answers 404. Not published: the client only follows the link it was given.
 */
@RestController
public class RemittanceFilesController {
    private final RemittanceService remittances;
    public RemittanceFilesController(RemittanceService remittances) { this.remittances = remittances; }

    @io.swagger.v3.oas.annotations.Hidden
    @GetMapping("/api/v1/remittances/files/{clubId}/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String clubId, @PathVariable String id, @RequestParam long expires,
            @RequestParam String signature) {
        var file = remittances.download(clubId, id, expires, signature);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("Content-Security-Policy", com.agilityhub.core.shared.application.ContentSecurityPolicies.DOWNLOAD)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(new InputStreamResource(file.content()));
    }
}
