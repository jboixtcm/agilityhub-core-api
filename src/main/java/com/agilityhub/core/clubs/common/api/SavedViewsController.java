package com.agilityhub.core.clubs.common.api;

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
import static com.agilityhub.core.clubs.common.api.CommonContracts.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class SavedViewsController {
    private final com.agilityhub.core.clubs.common.application.SavedViewService views;
    public SavedViewsController(com.agilityhub.core.clubs.common.application.SavedViewService views) { this.views = views; }
    private SavedView response(com.agilityhub.core.clubs.common.application.SavedViewData view) {
        return new SavedView(view.id(), view.ownerAccountId(), view.listKey(), view.name(), view.columns(), view.filters(), view.sort(), view.shared(), view.version());
    }
    @GetMapping("/api/v1/saved-views")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {"listKey"}, sortable = {},
            columns = {"name*", "columns", "filters", "sort", "shared"}, paged = false, exportable = false)
    @Operation(summary = "Saved views",
            description = "R-03-23. Tenant-scoped preferences: own and shared views are readable; only the owner or ADMIN may edit. Unknown or restricted columns are omitted on load.",
            responses = @ApiResponse(responseCode = "200", description = "List<SavedView>"))
    public List<SavedView> savedViews(@RequestParam String listKey) { return views.list(listKey).stream().map(this::response).toList(); }

    @GetMapping("/api/v1/saved-views/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @Operation(summary = "Saved view",
            description = "R-03-23. Tenant-scoped preferences: own and shared views are readable; only the owner or ADMIN may edit. Unknown or restricted columns are omitted on load.",
            responses = @ApiResponse(responseCode = "200", description = "SavedView"))
    public SavedView savedView(@PathVariable String id) { return response(views.get(id)); }

    @PostMapping("/api/v1/saved-views")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ContractErrors({SAVED_VIEW_NAME_TAKEN})
    @Operation(summary = "Create saved view",
            description = "R-03-23. Tenant-scoped preferences: own and shared views are readable; only the owner or ADMIN may edit. Unknown or restricted columns are omitted on load.",
            responses = @ApiResponse(responseCode = "201", description = "SavedView"))
    public SavedView createSavedView(@Valid @RequestBody SavedViewCreate request) { return response(views.create(request.listKey(), request.name(), request.columns(), request.filters(), request.sort(), request.shared())); }

    @PutMapping("/api/v1/saved-views/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ContractErrors({SAVED_VIEW_NAME_TAKEN, STALE_VERSION})
    @Operation(summary = "Update saved view",
            description = "S03 §6, R-03-23. Only the owner or ADMIN can edit a saved view. Versioned update.",
            responses = @ApiResponse(responseCode = "200", description = "SavedView"))
    public SavedView updateSavedView(@PathVariable String id, @Valid @RequestBody SavedViewUpdate request) { return response(views.update(id, request.listKey(), request.name(), request.columns(), request.filters(), request.sort(), request.shared(), request.version())); }

    @DeleteMapping("/api/v1/saved-views/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @Operation(summary = "Delete saved view",
            description = "R-03-23. Tenant-scoped preferences: own and shared views are readable; only the owner or ADMIN may edit. Unknown or restricted columns are omitted on load.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteSavedView(@PathVariable String id) { views.delete(id); }

}
