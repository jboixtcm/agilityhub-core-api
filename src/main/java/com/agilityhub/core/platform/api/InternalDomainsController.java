package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.PlatformDomains;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class InternalDomainsController {
    private final PlatformDomains domains;
    public InternalDomainsController(PlatformDomains domains) { this.domains = domains; }

    @GetMapping("/internal/domains/allowed")
    @SecurityRequirements
    @ContractErrors(value={VALIDATION_ERROR,FORBIDDEN,NOT_FOUND},omit={401,409,422})
    @Operation(summary="isDomainAllowed", description="S17 R-17-03, WP-17-D. Caddy's on-demand TLS ask (internal network), no bearer or tenant. Public ingress blocks /internal/*. "
            + "The request address must be loopback or RFC1918 under the existing trusted-proxy configuration; untrusted forwarded headers grant no access (403 FORBIDDEN). "
            + "200 (no body) only for a VERIFIED domain of a club, the host compared normalized; a PENDING, BROKEN or unknown host, or a product host, is 404 NOT_FOUND, "
            + "so Caddy issues no certificate for it.",
            responses=@ApiResponse(responseCode="200",description="Domain allowed",content=@Content))
    public void allowed(@RequestParam @NotBlank @Parameter(description="DNS host") String host) {
        if (!domains.allowed(host)) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
}
