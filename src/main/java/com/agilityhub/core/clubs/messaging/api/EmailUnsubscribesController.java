package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.EmailUnsubscribeService;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * `POST /email-unsubscribes` (S11 §6, a §13 proposal published by E7-T01, served since E7-T02): the «Deixar de rebre
 * aquests comunicats» link of a CLUB_NEWS e-mail (R-11-08). Anonymous: the signed token names the club and the member; the
 * tenant comes from the host. The operation publishes `security: []` (no bearer), as `SecurityConfiguration` permits it.
 */
@RestController
public class EmailUnsubscribesController {
    private final EmailUnsubscribeService service;
    public EmailUnsubscribesController(EmailUnsubscribeService service) { this.service = service; }

    @PostMapping("/api/v1/email-unsubscribes")
    @SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, UNSUBSCRIBE_TOKEN_INVALID})
    @Operation(summary = "unsubscribeEmail", description = "Roles: ANON (any caller; the token authorises). A valid token (signed, 30 days) turns "
            + "emailByCategory.CLUB_NEWS off for its member and publishes EmailUnsubscribed{memberId}; using it again changes nothing. An expired, "
            + "tampered or another club's token → UNSUBSCRIBE_TOKEN_INVALID (422). Transactional e-mails never carry the link. The club comes from the host.",
            responses = @ApiResponse(responseCode = "200", description = "EmailUnsubscribeResult", useReturnTypeSchema = true))
    public EmailUnsubscribeResult unsubscribeEmail(@Valid @RequestBody EmailUnsubscribeRequest request) {
        service.unsubscribe(request.token());
        return new EmailUnsubscribeResult(NotificationCategory.CLUB_NEWS);
    }
}
