package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.Hidden
@RestController
public class SignupFilesController {
    private final AttachmentService attachments;
    public SignupFilesController(AttachmentService attachments) { this.attachments=attachments; }
    @PutMapping("/api/v1/signup/uploads")
    @PreAuthorize("isAnonymous() or hasRole('MEMBER')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(@RequestParam String fileKey,@RequestParam long expires,@RequestParam String signature,HttpServletRequest request) throws IOException {
        attachments.putSignupLocal(fileKey,expires,signature,request.getContentType(),request.getInputStream());
    }
    @GetMapping("/api/v1/signup/files")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<InputStreamResource> download(@RequestParam String fileKey,@RequestParam long expires,@RequestParam String signature) throws IOException {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).header("Content-Disposition","attachment")
                .header("Cache-Control","no-store").body(new InputStreamResource(attachments.openLocal(fileKey,expires,signature)));
    }
}
