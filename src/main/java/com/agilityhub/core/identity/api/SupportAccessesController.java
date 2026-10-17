package com.agilityhub.core.identity.api;

import static com.agilityhub.core.shared.domain.ErrorCode.*;

import com.agilityhub.core.identity.application.SupportAccessService;
import com.agilityhub.core.shared.application.SupportRestricted;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** S17 R-17-10 «revocable» (E10-T04 round 2, E123): the club's side of the AgilityHub team's support accesses. */
@RestController
public class SupportAccessesController {
    private final SupportAccessService supports;
    public SupportAccessesController(SupportAccessService supports) { this.supports = supports; }

    @DeleteMapping("/api/v1/support-accesses/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @SupportRestricted
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({NOT_FOUND, INVALID_STATE, SUPPORT_RESTRICTED})
    @Operation(summary = "endSupportAccess", description = "S17 R-17-10. Roles: ADMIN of the club (MEMBER, INSTRUCTOR → 403; an impersonation "
            + "token → 403; a support token → 403 SUPPORT_RESTRICTED: the platform ends its own access with POST /oauth2/revoke). No module. "
            + "Ends a live support access of the AgilityHub team to this club: {id} is the grant, the entityId of its SUPPORT_ACCESS_STARTED audit "
            + "entry (GET /audit-entries?filter=action:eq:SUPPORT_ACCESS_STARTED; details.expiresAt says until when it lives). Its token answers "
            + "401 from its next request. Audited SUPPORT_ACCESS_ENDED (entityType ImpersonationGrant, details.actorAccountId = the platform "
            + "account), also listed in the platform's audit; publishes SupportAccessEnded{clubId, actorAccountId}. Another club's access, a "
            + "member impersonation or an unknown id → 404 NOT_FOUND; an access already ended or expired → 409 INVALID_STATE "
            + "{status: ENDED | EXPIRED}. No body.",
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void endSupportAccess(@PathVariable String id) { supports.end(id); }
}
