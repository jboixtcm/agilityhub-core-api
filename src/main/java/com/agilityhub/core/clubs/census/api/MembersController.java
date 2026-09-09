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

/** S03 tenant-scoped census endpoints. */
@RestController
public class MembersController {
    private final com.agilityhub.core.clubs.census.application.CensusQuery queries;
    private final com.agilityhub.core.clubs.census.application.CensusAccess access;
    private final com.agilityhub.core.identity.application.IdentityTransactions transactions;
    private final com.agilityhub.core.shared.application.lists.ListEngine lists;
    private final com.agilityhub.core.clubs.catalogs.application.RoleAssignmentService roles;
    private final com.agilityhub.core.clubs.census.application.MemberService members;
    public MembersController(com.agilityhub.core.shared.application.lists.ListEngine lists, com.agilityhub.core.clubs.catalogs.application.RoleAssignmentService roles,
            com.agilityhub.core.clubs.census.application.CensusQuery queries, com.agilityhub.core.clubs.census.application.CensusAccess access,
            com.agilityhub.core.identity.application.IdentityTransactions transactions, com.agilityhub.core.clubs.census.application.MemberService members) {
        this.lists = lists; this.roles = roles; this.queries = queries; this.access = access; this.transactions = transactions; this.members = members;
    }
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
    public java.util.Map<String,Object> getMember(@PathVariable String id) { return queries.member(id, access.role("ADMIN")); }

    @GetMapping("/api/v1/members/{id}/overview")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @Operation(summary = "Member overview",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "MemberOverview", content = @Content(schema = @Schema(implementation = MemberOverview.class))))
    public java.util.Map<String,Object> memberOverview(@PathVariable String id) { return queries.overview(id); }

    @PatchMapping("/api/v1/members/{id}")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, ID_DOCUMENT_ALREADY_EXISTS, STALE_VERSION, MEMBER_ERASED})
    @Operation(summary = "Update member",
            description = "S03 §6, R-03-08. Editable census fields only; plan, price, roles and payment method use their dedicated use cases. Version is required.",
            responses = @ApiResponse(responseCode = "200", description = "Member", content = @Content(schema = @Schema(implementation = Member.class))))
    public java.util.Map<String,Object> updateMember(@PathVariable String id, @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = MemberPatch.class))) @RequestBody java.util.Map<String,Object> request) { return transactions.run(() -> { if ("PENDING".equals(queries.member(id, true).get("status"))) { members.patchPending(id, request); }
        else { members.patch(id, request, false); } return queries.member(id, true); }); }

    @PatchMapping("/api/v1/members/{id}/payment-method")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @RequiresModule(Module.BILLING)
    @ContractErrors({INVALID_IBAN, PAYMENT_PROVIDER_NOT_ENABLED, MEMBER_ERASED})
    @Operation(summary = "Update payment method",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "200", description = "PaymentMethodView", content = @Content(schema = @Schema(implementation = PaymentMethodView.class))))
    public Object updatePaymentMethod(@PathVariable String id, @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(schema = @Schema(implementation = PaymentMethodPatch.class))) @RequestBody java.util.Map<String,Object> request) { return transactions.run(() -> { members.payment(id, request); return queries.member(id, true).get("paymentMethod"); }); }

    @PostMapping("/api/v1/members/{id}/booking-block")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({BOOKING_BLOCK_ALREADY_ACTIVE, MEMBER_ERASED})
    @Operation(summary = "Activate booking block",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "201", description = "BookingBlock", content = @Content(schema = @Schema(implementation = BookingBlock.class))))
    public java.util.Map<String,Object> activateBookingBlock(@PathVariable String id, @Valid @RequestBody BookingBlockRequest request) { return transactions.run(() -> { members.block(id, request.reason()); return queries.block(id); }); }

    @DeleteMapping("/api/v1/members/{id}/booking-block")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({BOOKING_BLOCK_NOT_ACTIVE, MEMBER_ERASED})
    @Operation(summary = "Remove booking block",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "204", description = "Completed without a response body", content = @Content))
    public void removeBookingBlock(@PathVariable String id) { transactions.run(() -> { members.unblock(id); return null; }); }

    @PostMapping("/api/v1/members/{id}/access-resend")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE, RATE_LIMITED, MEMBER_ERASED})
    @Operation(summary = "Resend access",
            description = "Tenant comes from the JWT. ADMIN endpoints reject impersonation; erased members reject mutations.",
            responses = @ApiResponse(responseCode = "202", description = "AccessResendResponse"))
    public AccessResendResponse resendAccess(@PathVariable String id, jakarta.servlet.http.HttpServletRequest http) { return new AccessResendResponse(transactions.run(() -> members.resend(id, http.getRemoteAddr()))); }

    @PutMapping("/api/v1/members/{id}/roles")
    @ApiResponse(responseCode = "409", description = "MEMBER_ERASED: census mutations are unavailable after erasure")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({MEMBER_NOT_ACTIVE, ROLE_MEMBER_REQUIRED, LAST_ADMIN, CANNOT_CHANGE_OWN_ADMIN_ROLE, MEMBER_ERASED})
    @Operation(summary = "Update member roles",
            description = "S03 R-03-10. Synchronizes S05 profiles; MEMBER is required and ADMIN cannot be removed from oneself.",
            responses = @ApiResponse(responseCode = "200", description = "RolesResponse"))
    public RolesResponse updateMemberRoles(@PathVariable String id, @Valid @RequestBody RolesRequest request) {
        var assigned = transactions.run(() -> { access.mutableMember(id); return roles.setRoles(id, request.roles().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())); });
        return new RolesResponse(assigned.stream().map(MemberRole::valueOf).sorted().toList());
    }

}
