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

/** S05 public club website projection, scoped by slug and the club public key. */
@RestController
public class PublicPlansController {
    private final com.agilityhub.core.platform.application.PublicClubAccess clubs;
    private final OfferViews views;
    public PublicPlansController(com.agilityhub.core.platform.application.PublicClubAccess clubs, OfferViews views) { this.clubs = clubs; this.views = views; }
    private java.util.Locale locale(String language, com.agilityhub.core.platform.application.ClubConfig config) {
        if (language != null) {
            try {
                var allowed = config.club().locales().stream().map(java.util.Locale::forLanguageTag).toList();
                var ranges = java.util.Locale.LanguageRange.parse(language);
                var locale = java.util.Locale.lookup(ranges, allowed);
                if (locale != null) { return locale; }
                var matches = java.util.Locale.filter(ranges, allowed);
                if (!matches.isEmpty()) { return matches.getFirst(); }
            } catch (IllegalArgumentException invalid) { /* Fall back to the club locale for malformed headers. */ }
        }
        return java.util.Locale.forLanguageTag(config.club().defaultLocale());
    }
    @GetMapping("/api/v1/public/{clubSlug}/plans")
    @io.swagger.v3.oas.annotations.security.SecurityRequirements
    @ListContract(filterable = {}, sortable = {"order"},
            columns = {"name*", "type*", "conditions", "texts", "currentPrices@BILLING"}, paged = false, exportable = false)
    @ContractErrors({INVALID_API_KEY, CLUB_NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "Public plans",
            description = "S05 §6, R-05-21. Anonymous bearer access; X-Api-Key is required. Resolve club from slug and validate its key. Prices appear only with BILLING; never require a club host.",
            responses = @ApiResponse(responseCode = "200", description = "PublicPlans"))
    public PublicPlans publicPlans(@PathVariable String clubSlug, @RequestHeader(value = "X-Api-Key", required = false) String apiKey, @RequestHeader(value = "Accept-Language", required = false) String language, jakarta.servlet.http.HttpServletResponse response) {
        var config = clubs.resolve(clubSlug, apiKey);
        try (var scope = com.agilityhub.core.shared.application.TenantContext.open(config.club().id())) {
            var locale = locale(language, config);
            var result = views.publicPlans(locale);
            response.setHeader("Cache-Control", "public, max-age=300");
            response.addHeader("Vary", "Accept-Language");
            response.addHeader("Vary", "X-Api-Key");
            response.setHeader("Content-Language", locale.toLanguageTag());
            return result;
        }
    }

}
