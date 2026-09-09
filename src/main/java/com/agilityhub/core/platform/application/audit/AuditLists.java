package com.agilityhub.core.platform.application.audit;

import com.agilityhub.core.platform.domain.audit.AuditReadMasker;
import com.agilityhub.core.platform.persistence.audit.AuditListProjection;
import com.agilityhub.core.shared.application.contract.ApiContracts.*;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.shared.application.lists.ListDefinition.Type.*;

@Service
public class AuditLists implements ListProvider {
    private final AuditListProjection projection;
    public AuditLists(AuditListProjection projection) { this.projection = projection; }
    @Override public Set<String> keys() { return Set.of("audit-entries"); }
    @Override public ListDataset dataset(String key) { return dataset(false, null); }
    public ListDataset dataset(boolean detail, String member) {
        ListAccess.account();
        if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        var filters = new LinkedHashMap<String, ListDefinition.Field>();
        filters.put("at", new ListDefinition.Field("at", INSTANT, Set.of(FilterOperator.between)));
        for (String field : List.of("action", "entityType", "entityId", "memberId", "actorAccountId", "actorRole", "impersonatedMemberId", "origin")) {
            filters.put(field, new ListDefinition.Field(field, TEXT));
        }
        var columns = List.of("at", "action", "entityLabel", "actorName", "impersonatedName", "changes", "origin", "details");
        var fields = new LinkedHashMap<String, Object>();
        for (String field : List.of("clubId", "at", "actorAccountId", "actorName", "actorRole", "impersonatedMemberId", "impersonatedName",
                "origin", "entityType", "entityId", "entityLabel", "memberId", "action", "changes", "reason", "details")) { fields.put(field, "$" + field); }
        if (detail) { for (String field : List.of("ip", "userAgent", "traceId", "eventIds")) { fields.put(field, "$" + field); } }
        var selectable = new HashSet<>(fields.keySet()); selectable.add("id");
        var definition = new ListDefinition("audit-entries", filters, Map.of("at", "at"), List.of("entityLabel", "actorName", "reason"),
                columns, columns.subList(0, 7), List.of("at,desc"), selectable);
        var stages = new ArrayList<Document>();
        if (member != null) {
            projection.requireMember(member);
            stages.add(new Document("$match", new Document("$or", List.of(new Document("memberId", member), new Document("impersonatedMemberId", member)))));
        }
        stages.addAll(projection.stages());
        return new ListDataset(definition, "audit_entries", stages, fields, Set.of("changes[].before", "changes[].after"),
                (field, value) -> String.valueOf(value), AuditReadMasker::sanitize);
    }
}
