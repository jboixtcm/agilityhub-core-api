package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.MessagingContractAccess;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * `POST /email-unsubscribes` (S11 §6, a §13 proposal published by E7-T01): the «Deixar de rebre aquests comunicats» link of
 * a CLUB_NEWS e-mail (R-11-08). Anonymous: the signed token names the club and the member; the tenant comes from the host.
 * 501 NOT_IMPLEMENTED after the validation until E7-T02.
 */
@RestController
public class EmailUnsubscribesController {
    private final MessagingContractAccess access;
    public EmailUnsubscribesController(MessagingContractAccess access) { this.access = access; }

    @PostMapping("/api/v1/email-unsubscribes")
    @ContractErrors({VALIDATION_ERROR, UNSUBSCRIBE_TOKEN_INVALID})
    @Operation(summary = "unsubscribeEmail", description = "Roles: ANON (any caller; the token authorises). A valid token (signed, 30 days) turns "
            + "emailByCategory.CLUB_NEWS off for its member and publishes EmailUnsubscribed{memberId}; an expired, tampered or another club's token → "
            + "UNSUBSCRIBE_TOKEN_INVALID (422). Transactional e-mails never carry the link. The club comes from the host." + MessageTemplatesController.STUB,
            responses = @ApiResponse(responseCode = "200", description = "EmailUnsubscribeResult", useReturnTypeSchema = true))
    public EmailUnsubscribeResult unsubscribeEmail(@Valid @RequestBody EmailUnsubscribeRequest request) {
        access.tenant();
        throw new UnsupportedOperationException();
    }
}
