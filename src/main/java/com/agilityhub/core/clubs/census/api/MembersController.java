package com.agilityhub.core.clubs.census.api;

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
import static com.agilityhub.core.clubs.census.api.CensusResponses.*;
import static com.agilityhub.core.clubs.census.api.CensusRequests.*;

/** Contract-first endpoints; standard NOT_IMPLEMENTED until the owning E2 use case is delivered. */
@RestController
public class MembersController {
    private final com.agilityhub.core.shared.application.lists.ListEngine lists;
    public MembersController(com.agilityhub.core.shared.application.lists.ListEngine lists) { this.lists = lists; }
    @GetMapping("/api/v1/members")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {"id", "memberNumber", "lastName", "fullName(contains)", "status", "displayStatus", "planId", "priceId", "paymentMethodType", "nextInvoiceDate", "joinedAt", "leaveDate", "bookingBlocked", "familyGroupId", "imageRightsGranted", "roles", "city", "postalCode", "dogLevelId", "dogName(contains)", "hasPendingDocuments", "freeTrainingAllowed", "gender", "birthDate"}, sortable = {"lastName", "firstName", "memberNumber", "joinedAt", "leaveDate", "nextInvoiceDate", "city"},
            columns = {"fullName*", "dogs*", "plan*", "displayStatus*", "memberNumber", "contact", "paymentMethod@BILLING", "nextInvoiceDate@BILLING", "familyGroup@FAMILY_GROUP", "joinedAt", "leaveDate", "bookingBlocked", "imageRights", "roles", "city", "postalCode", "pendingDocuments", "freeTraining@FREE_TRAINING", "birthDate", "gender", "idDocument"}, paged = true, exportable = true)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "List members",
            description = "S03 §6, R-03-22/R-03-31. ADMIN gets MemberListItem; INSTRUCTOR gets MemberInstructorView without financial or internal data. Fields/filters are limited by role.",
            responses = @ApiResponse(responseCode = "200", description = "ListPage<MemberListItem>; fields selects a sparse projection", content = @Content(schema = @Schema(implementation = MemberPage.class))))
    public org.springframework.http.ResponseEntity<?> listMembers(@io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        return org.springframework.http.ResponseEntity.ok(lists.list("members", params));
    }

    @GetMapping("/api/v1/members/filter-values")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @ListContract(filterable = {"id", "memberNumber", "lastName", "fullName(contains)", "status", "displayStatus", "planId", "priceId", "paymentMethodType", "nextInvoiceDate", "joinedAt", "leaveDate", "bookingBlocked", "familyGroupId", "imageRightsGranted", "roles", "city", "postalCode", "dogLevelId", "dogName(contains)", "hasPendingDocuments", "freeTrainingAllowed", "gender", "birthDate"}, sortable = {},
            columns = {}, paged = false, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "Member filter values",
            description = "R-03-22. Top 50 facet values after q and filters on other fields; role and module restrictions apply.",
            responses = @ApiResponse(responseCode = "200", description = "FilterValues"))
    public FilterValues memberFilterValues(@RequestParam String field, @RequestParam(required = false) String q, @RequestParam(required = false) List<String> filter,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) @RequestParam org.springframework.util.MultiValueMap<String, String> params) {
        return lists.facets("members", field, params);
    }

    @GetMapping("/api/v1/members/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INSTRUCTOR') and principal.claims['imp'] != true")
    @Operation(summary = "Get member",
            description = "S03 §6, R-03-31. ADMIN: Member; INSTRUCTOR: MemberInstructorView. Other-club resources return NOT_FOUND.",
            responses = @ApiResponse(responseCode = "200", description = "Member", content = @Content(schema = @Schema(oneOf = {Member.class, MemberInstructorView.class}))))
    public Member getMember(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @GetMapping("/api/v1/members/{id}/overview")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Member overview",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "MemberOverview"))
    public MemberOverview memberOverview(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/members/{id}")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, ID_DOCUMENT_ALREADY_EXISTS, STALE_VERSION})
    @Operation(summary = "Update member",
            description = "S03 §6, R-03-08. Editable census fields only; plan, price, roles and payment method use their dedicated use cases. Version is required.",
            responses = @ApiResponse(responseCode = "200", description = "Member"))
    public Member updateMember(@PathVariable String id, @Valid @RequestBody MemberPatch request) { throw new UnsupportedOperationException(); }

    @PatchMapping("/api/v1/members/{id}/payment-method")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.BILLING)
    @ContractErrors({INVALID_IBAN, PAYMENT_PROVIDER_NOT_ENABLED})
    @Operation(summary = "Update payment method",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "PaymentMethodView"))
    public PaymentMethodView updatePaymentMethod(@PathVariable String id, @Valid @RequestBody PaymentMethodPatch request) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/members/{id}/booking-block")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({BOOKING_BLOCK_ALREADY_ACTIVE})
    @Operation(summary = "Activate booking block",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "201", description = "BookingBlock"))
    public BookingBlock activateBookingBlock(@PathVariable String id, @Valid @RequestBody BookingBlockRequest request) { throw new UnsupportedOperationException(); }

    @DeleteMapping("/api/v1/members/{id}/booking-block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({BOOKING_BLOCK_NOT_ACTIVE})
    @Operation(summary = "Remove booking block",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void removeBookingBlock(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PostMapping("/api/v1/members/{id}/access-resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE, RATE_LIMITED})
    @Operation(summary = "Resend access",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "202", description = "AccessResendResponse"))
    public AccessResendResponse resendAccess(@PathVariable String id) { throw new UnsupportedOperationException(); }

    @PutMapping("/api/v1/members/{id}/roles")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE, ROLE_MEMBER_REQUIRED, LAST_ADMIN, CANNOT_CHANGE_OWN_ADMIN_ROLE})
    @Operation(summary = "Update member roles",
            description = "Contract only; implementation is deferred. Tenant comes from the JWT. ADMIN endpoints reject impersonation.",
            responses = @ApiResponse(responseCode = "200", description = "RolesResponse"))
    public RolesResponse updateMemberRoles(@PathVariable String id, @Valid @RequestBody RolesRequest request) { throw new UnsupportedOperationException(); }

}
