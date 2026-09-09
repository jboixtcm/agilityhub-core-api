package com.agilityhub.core.clubs.content.api;

import com.agilityhub.core.clubs.content.application.ClubPageService;
import com.agilityhub.core.clubs.content.persistence.ClubPage;
import com.agilityhub.core.shared.application.contract.*;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.content.api.ClubPageContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
@RequestMapping("/api/v1/club-pages")
public class ClubPagesController {
    private final ClubPageService pages;
    public ClubPagesController(ClubPageService pages) { this.pages = pages; }
    private boolean admin() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }
    private Page view(ClubPage page) {
        boolean admin = admin();
        return new Page(page.key(), page.title().values(), page.body().values(), page.version(), page.publishedAt(), page.active(),
                new LastChange(page.lastChange().at(), admin ? page.lastChange().by() : null),
                admin ? page.history().stream().map(h -> new History(h.version(), h.publishedAt(), h.by(), h.title().values(), h.body().values())).toList() : null);
    }
    @GetMapping
    @PreAuthorize("hasAnyRole('MEMBER','INSTRUCTOR','ADMIN')")
    @ListContract(filterable = {"active"}, sortable = {}, columns = {"key*", "title*", "active*"}, paged = false, exportable = false)
    @ContractErrors({UNAUTHENTICATED, FORBIDDEN, TENANT_MISMATCH})
    @Operation(summary = "List club pages", description = "S05 §6. Non-admin readers see active pages regardless of the active flag; admins can filter either state. Translation maps are returned.")
    public ApiContracts.CatalogItems<Page> list(@RequestParam(required = false) Boolean active) {
        var items = pages.list(active, admin()).stream().map(this::view).toList();
        return new ApiContracts.CatalogItems<>(items, items.size());
    }
    @GetMapping("/{key}")
    @PreAuthorize("hasAnyRole('MEMBER','INSTRUCTOR','ADMIN')")
    @ContractErrors({UNAUTHENTICATED, FORBIDDEN, TENANT_MISMATCH, NOT_FOUND})
    @Operation(summary = "Read a club page", description = "S05 §6. Drafts and the last ten published snapshots are available only to ADMIN.")
    public Page get(@PathVariable String key) { return view(pages.get(key, admin())); }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({UNAUTHENTICATED, FORBIDDEN, TENANT_MISMATCH, DUPLICATE_NAME, VALIDATION_ERROR})
    @Operation(summary = "Create a club page", description = "S05 §6. Starts at version 1. Limited CommonMark: headings, paragraphs, emphasis, lists and links; no HTML, images, code or block quotes. At most 20000 body characters per locale.")
    public Page create(@Valid @RequestBody Create request) {
        return view(pages.create(request.key(), request.title(), request.body(), request.active()));
    }
    @PatchMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    @ContractErrors({UNAUTHENTICATED, FORBIDDEN, TENANT_MISMATCH, NOT_FOUND, STALE_VERSION, VALIDATION_ERROR})
    @Operation(summary = "Edit or publish a club page", description = "S05 §6. Supply the publication version. Activation or a body change while active increments it and sets publishedAt. Draft and title-only edits preserve it. Supplied translation maps replace that whole field.")
    public Page patch(@PathVariable String key, @Valid @RequestBody Patch request) {
        return view(pages.update(key, request.title(), request.body(), request.active(), request.version()));
    }
}
