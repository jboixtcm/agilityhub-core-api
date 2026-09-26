package com.agilityhub.core.clubs.followup.api;

import com.agilityhub.core.clubs.followup.application.AttachmentService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@io.swagger.v3.oas.annotations.Hidden
@RestController
public class SignupFilesController {
    private final AttachmentService attachments;
    public SignupFilesController(AttachmentService attachments) { this.attachments=attachments; }
    /**
     * The signed upload URL of a signup file (CONVENCIONS_API §5, E5-T26): authorised by its signature alone, no bearer, for the
     * grant's club. An anonymous grant answers 422 SIGNUP_CLOSED while signup is closed; a MEMBER's (add-dog) keeps it (R-04-27).
     */
    @PutMapping("/api/v1/signup/uploads")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upload(@RequestParam String fileKey,@RequestParam long expires,@RequestParam String signature,HttpServletRequest request) throws IOException {
        attachments.putSignupLocal(fileKey,expires,signature,request.getContentType(),request.getInputStream());
    }
    /** The signed download URL of a signup file (CONVENCIONS_API §5, E5-T24): authorised by its signature alone, no bearer. */
    @GetMapping("/api/v1/signup/files")
    public ResponseEntity<InputStreamResource> download(@RequestParam String fileKey,@RequestParam long expires,@RequestParam String signature) throws IOException {
        return AttachmentsController.download(attachments.openLocal(fileKey,expires,signature));
    }
}
