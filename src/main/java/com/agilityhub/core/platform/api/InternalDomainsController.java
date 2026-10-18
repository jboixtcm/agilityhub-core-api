package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.PlatformDomains;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class InternalDomainsController {
    /** INC-64: the header Caddy's `forward_auth` copies onto the request to pick the SPA of a club host. */
    public static final String CLUB_APP = "X-Club-App";
    /** The app a host serves when it is not a verified `clubs-admin` domain: the members' SPA, as the catch-all always served. */
    static final String DEFAULT_APP = "clubs";
    private final PlatformDomains domains;
    private final HostTenantResolver hosts;
    public InternalDomainsController(PlatformDomains domains, HostTenantResolver hosts) { this.domains = domains; this.hosts = hosts; }

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

    /**
     * INC-64 (S17 R-17-03, WP-17-D): which SPA Caddy serves on a club host, asked by its `forward_auth` on every SPA request of the
     * `https://` catch-all. Always `200` with no body and `X-Club-App: clubs | clubs-admin`: the verified domain's own app, else
     * `clubs` (a host whose domain went `BROKEN` or was removed keeps the members' SPA until its certificate expires, as before). A
     * verification that commits is served at once: no Caddy reload, no `CLUB_ADMIN_HOSTS`. Same internal-network rule as the ask;
     * not part of the public contract (Caddy refuses `/internal/*` from the internet).
     */
    @Hidden
    @GetMapping("/internal/domains/app")
    public ResponseEntity<Void> app(@RequestParam @NotBlank String host) {
        return ResponseEntity.ok().header(CLUB_APP, hosts.app(host).orElse(DEFAULT_APP)).build();
    }
}
