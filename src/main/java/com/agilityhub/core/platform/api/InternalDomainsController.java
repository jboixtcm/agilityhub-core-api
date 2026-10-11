package com.agilityhub.core.platform.api;

import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class InternalDomainsController {
    @GetMapping("/internal/domains/allowed")
    @SecurityRequirements
    @ContractErrors(value={VALIDATION_ERROR,FORBIDDEN,NOT_FOUND,NOT_IMPLEMENTED},omit={401,409,422})
    @Operation(summary="isDomainAllowed", description="S17 R-17-03. Caddy internal-network endpoint, no bearer or tenant. Public ingress blocks /internal/*. The request address must be loopback or RFC1918 under the existing trusted-proxy configuration; untrusted forwarded headers grant no access. Contract only: never authorizes a certificate yet.",
            responses=@ApiResponse(responseCode="200",description="Domain allowed"))
    public void allowed(@RequestParam @NotBlank @Parameter(description="DNS host") String host) {
        throw new UnsupportedOperationException("S17 contract");
    }
}
