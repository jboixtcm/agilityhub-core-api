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
    @GetMapping("/api/v1/saved-views")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {"listKey"}, sortable = {},
            columns = {"name*", "columns", "filters", "sort", "shared"}, paged = false, exportable = false)
    @Operation(summary = "Saved views",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "List<SavedView>"))
    public List<SavedView> savedViews(@RequestParam String listKey) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/saved-views/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @Operation(summary = "Saved view",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "200", description = "SavedView"))
    public SavedView savedView(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/saved-views")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ContractErrors({SAVED_VIEW_NAME_TAKEN})
    @Operation(summary = "Create saved view",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "201", description = "SavedView"))
    public SavedView createSavedView(@Valid @RequestBody SavedViewCreate request) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/saved-views/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ContractErrors({SAVED_VIEW_NAME_TAKEN, STALE_VERSION})
    @Operation(summary = "Update saved view",
            description = "S03 §6, R-03-23. Only the owner or ADMIN can edit a saved view. Versioned update.",
            responses = @ApiResponse(responseCode = "200", description = "SavedView"))
    public SavedView updateSavedView(@PathVariable String id, @Valid @RequestBody SavedViewUpdate request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/saved-views/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @Operation(summary = "Delete saved view",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. Role-reduced projections and ownership checks apply when implemented.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void deleteSavedView(@PathVariable String id) { throw new UnsupportedOperationException(); }

}
