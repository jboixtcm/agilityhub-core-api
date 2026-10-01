package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.clubs.census.persistence.inactivity.InactivityPeriodRepository;
import com.agilityhub.core.clubs.census.persistence.leave.LeaveRequestRepository;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListDefinition.Field;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * Read-only tenant and ownership guards of the reserved S13 operations (E8-T01); no side effects. E8-T05 serves the
 * operations behind them. R-13-18: a member reaches only their own period or request — another member's, also one of their
 * family group, is 404 —, and another club's member, period or request is 404 (T-13-24).
 */
@Service
public class LifecycleContractAccess {
    /** `GET /inactivity-periods` (S13 §6): the universal list allowlist; `q` searches the member's name. */
    public static final ListDefinition INACTIVITY_PERIODS = new ListDefinition("inactivity-periods",
            Map.of("memberId", new Field("memberId", Type.TEXT), "state", new Field("state", Type.TEXT), "fromMonth", new Field("fromMonth", Type.TEXT),
                    "toMonth", new Field("toMonth", Type.TEXT), "origin", new Field("origin", Type.TEXT), "requestedAt", new Field("requestedAt", Type.INSTANT)),
            Map.of("fromMonth", "fromMonth", "requestedAt", "requestedAt", "memberLastName", "member.lastName"), List.of("member.fullName"),
            List.of("member", "fromMonth", "toMonth", "state", "origin", "requestedAt", "comments"), List.of("member", "fromMonth", "toMonth", "state"),
            List.of("fromMonth,asc"),
            Set.of("id", "member", "fromMonth", "toMonth", "state", "origin", "requestedAt", "comments", "feeSnapshot", "decision"));
    /** `GET /leave-requests` (S13 §6): the universal list allowlist; `q` searches the member's name. */
    public static final ListDefinition LEAVE_REQUESTS = new ListDefinition("leave-requests",
            Map.of("memberId", new Field("memberId", Type.TEXT), "state", new Field("state", Type.TEXT), "source", new Field("source", Type.TEXT),
                    "requestedDate", new Field("requestedDate", Type.DATE), "effectiveDate", new Field("decision.effectiveDate", Type.DATE),
                    "reasonKey", new Field("reasonKey", Type.TEXT), "nps", new Field("nps", Type.NUMBER)),
            Map.of("requestedAt", "requestedAt", "requestedDate", "requestedDate", "effectiveDate", "decision.effectiveDate"), List.of("member.fullName"),
            List.of("member", "source", "state", "requestedAt", "requestedDate", "effectiveDate", "reasonKey", "nps", "comment"),
            List.of("member", "requestedDate", "reasonKey", "state"), List.of("requestedAt,desc"),
            Set.of("id", "member", "source", "state", "requestedAt", "requestedDate", "effectiveDate", "reasonKey", "nps", "comment"));
    private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    private final InactivityPeriodRepository periods; private final LeaveRequestRepository requests; private final CensusRepository<Member> members;
    public LifecycleContractAccess(InactivityPeriodRepository periods, LeaveRequestRepository requests, CensusRepository<Member> members) {
        this.periods = periods; this.requests = requests; this.members = members;
    }

    public void tenant() { TenantContext.require(); }
    /** A `YYYY-MM` month (CONVENCIONS_API §5); an absent optional one is fine, anything else is `400 VALIDATION_ERROR {field}`. */
    public void month(String field, String value, boolean required) {
        if (value == null && !required) { return; }
        if (value == null || !MONTH.matcher(value).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", field, "fieldErrors", List.of(Map.of("field", field, "code", "INVALID_VALUE"))));
        }
    }
    /** A member of the open club (another club's or an unknown one → 404). */
    public void member(String id) { members.require(id); }
    /** A member a write is about to change: as {@link #member}, and an erased one → `409 MEMBER_ERASED` (S14 §5). */
    public void mutableMember(String id) { com.agilityhub.core.clubs.census.domain.CensusRules.mutable(members.require(id).erasedAt); }
    public void period(String id) { periods.findById(id).orElseThrow(LifecycleContractAccess::notFound); }
    public void ownPeriod(String id) { periods.findOwn(id, callerMember()).orElseThrow(LifecycleContractAccess::notFound); }
    public void request(String id) { requests.findById(id).orElseThrow(LifecycleContractAccess::notFound); }
    public void ownRequest(String id) { requests.findOwn(id, callerMember()).orElseThrow(LifecycleContractAccess::notFound); }
    public ListQuery periodList(MultiValueMap<String, String> params) { return ListQuery.parse(INACTIVITY_PERIODS, params); }
    public ListQuery requestList(MultiValueMap<String, String> params) { return ListQuery.parse(LEAVE_REQUESTS, params); }
    /** The member a `/me/*` call acts for: the impersonated member under impersonation (R-13-18), else the token's. */
    String callerMember() {
        var user = CurrentUser.current();
        if (user != null && user.impersonation() != null) { return user.impersonation().memberId(); }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        String memberId = authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getClaimAsString("memberId") : null;
        if (memberId == null || memberId.isBlank()) { throw notFound(); }
        return memberId;
    }
    private static ApiException notFound() { return new ApiException(ErrorCode.NOT_FOUND); }
}
