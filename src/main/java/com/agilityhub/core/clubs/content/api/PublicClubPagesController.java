package com.agilityhub.core.clubs.content.api;

import com.agilityhub.core.clubs.content.application.ClubPageService;
import com.agilityhub.core.platform.application.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class PublicClubPagesController {
    private final PublicClubAccess clubs;
    private final ClubPageService pages;
    public PublicClubPagesController(PublicClubAccess clubs, ClubPageService pages) { this.clubs = clubs; this.pages = pages; }
    @GetMapping("/api/v1/public/{clubSlug}/pages/{key}")
    @SecurityRequirement(name = "clubApiKey")
    @ContractErrors({INVALID_API_KEY, CLUB_NOT_FOUND, CLUB_SUSPENDED, NOT_FOUND, RATE_LIMITED})
    @Operation(summary = "Read a published club page", description = "S05 §6. X-Api-Key authenticates the slug-selected club independently of Host. Accept-Language selects an enabled locale, falling back to the club default. Drafts return NOT_FOUND.")
    public ClubPageContracts.PublicPage get(@PathVariable String clubSlug, @PathVariable String key,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Accept-Language", required = false) String language, HttpServletResponse response) {
        var config = clubs.resolve(clubSlug, apiKey);
        try (var scope = TenantContext.open(config.club().id())) {
            var page = pages.get(key, false);
            var locale = PublicClubLocale.resolve(language, config);
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Vary", "Accept-Language"); response.addHeader("Vary", "X-Api-Key");
            response.setHeader("Content-Language", locale.toLanguageTag());
            return new ClubPageContracts.PublicPage(page.key(), page.title().withDefaultLocale(config.club().defaultLocale()).resolve(locale).value(),
                    page.body().withDefaultLocale(config.club().defaultLocale()).resolve(locale).value(), page.version(), page.publishedAt(), true);
        }
    }
}
