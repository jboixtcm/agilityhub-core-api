package com.agilityhub.core.clubs.catalogs.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import static com.agilityhub.core.shared.application.contract.ApiContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;
import static com.agilityhub.core.clubs.catalogs.api.CatalogResponses.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class PublicPlansController {
    @GetMapping("/api/v1/public/{clubSlug}/plans")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"name*", "type*", "conditions", "texts", "currentPrices@BILLING"}, paged = false, exportable = false)
    @ContractErrors({INVALID_API_KEY, CLUB_NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "Public plans",
            description = "S05 §6, R-05-21. Anonymous bearer access; X-Api-Key is required. Resolve club from slug and validate its key. Prices appear only with BILLING; never require a club host.",
            responses = @ApiResponse(responseCode = "200", description = "PublicPlans"))
    public PublicPlans publicPlans(@PathVariable String clubSlug, @RequestHeader("X-Api-Key") String apiKey, @RequestHeader(value = "Accept-Language", required = false) String language) { throw new UnsupportedOperationException(); }

}
