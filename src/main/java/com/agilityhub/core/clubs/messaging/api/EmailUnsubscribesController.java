package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.EmailUnsubscribeService;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * `POST /email-unsubscribes` (S11 §6, a §13 proposal published by E7-T01, served since E7-T02): the «Deixar de rebre
 * aquests comunicats» link of a CLUB_NEWS e-mail (R-11-08). Anonymous: the signed token names the club and the member; the
 * tenant comes from the host. The operation publishes `security: []` (no bearer), as `SecurityConfiguration` permits it.
 *
 * <p>COMM-08 (owner decision of 13 Nov 2026): `POST /email-unsubscribes/one-click?t=…` is the RFC 8058 target of the mail's
 * `List-Unsubscribe` header (with `List-Unsubscribe-Post: List-Unsubscribe=One-Click`): the mailbox provider posts the form
 * body `List-Unsubscribe=One-Click` with no cookie or credential, and the token in the URL authorises it exactly as the
 * page's call does. Only that POST unsubscribes: there is no GET, so a link scanner that fetches the URL changes nothing.</p>
 */
@RestController
public class EmailUnsubscribesController {
    /** RFC 8058 §3.2: the only body a one-click POST carries. */
    static final String ONE_CLICK = "One-Click";
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

    @PostMapping("/api/v1/email-unsubscribes/one-click")
    @SecurityRequirements
    @ContractErrors({VALIDATION_ERROR, UNSUBSCRIBE_TOKEN_INVALID})
    @Operation(summary = "unsubscribeEmailOneClick", description = "Roles: ANON (any caller; the token authorises). COMM-08, RFC 8058 one-click: the "
            + "URL of a CLUB_NEWS e-mail's List-Unsubscribe header, sent with List-Unsubscribe-Post: List-Unsubscribe=One-Click. The mailbox provider "
            + "posts the form body List-Unsubscribe=One-Click (application/x-www-form-urlencoded or multipart/form-data) with the signed token in the "
            + "query (t); no cookie or bearer is read. It does what POST /email-unsubscribes does with the same token: emailByCategory.CLUB_NEWS off, "
            + "EmailUnsubscribed{memberId} once; using it again changes nothing. A missing token or a body other than List-Unsubscribe=One-Click → "
            + "VALIDATION_ERROR (400); an expired, tampered or another club's token → UNSUBSCRIBE_TOKEN_INVALID (422). The club comes from the host.",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = {
                    @Content(mediaType = "application/x-www-form-urlencoded", schema = @Schema(implementation = EmailOneClickUnsubscribeForm.class)),
                    @Content(mediaType = "multipart/form-data", schema = @Schema(implementation = EmailOneClickUnsubscribeForm.class))}),
            responses = @ApiResponse(responseCode = "200", description = "EmailUnsubscribeResult", useReturnTypeSchema = true))
    public EmailUnsubscribeResult unsubscribeEmailOneClick(
            @Parameter(in = ParameterIn.QUERY, required = true, description = "The signed token of the List-Unsubscribe URL (30 days)") @RequestParam("t") String token,
            @Parameter(hidden = true) @RequestParam(name = "List-Unsubscribe", required = false) String listUnsubscribe) {
        if (token.isBlank()) { throw invalid("t"); }
        if (!ONE_CLICK.equals(listUnsubscribe)) { throw invalid("List-Unsubscribe"); }
        service.unsubscribe(token);
        return new EmailUnsubscribeResult(NotificationCategory.CLUB_NEWS);
    }
    private static ApiException invalid(String field) {
        return new ApiException(VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", field, "code", "INVALID_VALUE"))));
    }
}
