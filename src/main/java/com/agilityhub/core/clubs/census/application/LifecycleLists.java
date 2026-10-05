package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.platform.application.Module;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;

@Service
public class LifecycleLists implements ListProvider {
    private final CensusAccess census;
    public LifecycleLists(CensusAccess census) { this.census = census; }
    @Override public Set<String> keys() { return Set.of("inactivity-periods", "leave-requests"); }
    @Override public ListDataset dataset(String key) {
        ListAccess.account(); if (!ListAccess.admin()) { throw new ApiException(ErrorCode.FORBIDDEN); }
        boolean period = key.equals("inactivity-periods"); if (period) { census.require(Module.INACTIVITY); }
        var definition = period ? LifecycleContractAccess.INACTIVITY_PERIODS : LifecycleContractAccess.LEAVE_REQUESTS;
        var stages = List.of(new Document("$lookup", new Document("from", "members").append("localField", "memberId").append("foreignField", "_id")
                .append("pipeline", List.of(new Document("$match", new Document("clubId", TenantContext.require())),
                        new Document("$project", new Document("id", "$_id").append("memberNumber", 1).append("lastName", "$lastName1")
                                .append("fullName", new Document("$trim", new Document("input", new Document("$concat", List.of("$firstName", " ", "$lastName1", " ", new Document("$ifNull", List.of("$lastName2", ""))))))))))
                .append("as", "lifecycleMember")), new Document("$set", new Document("member", new Document("$first", "$lifecycleMember"))));
        var projection = new LinkedHashMap<String, Object>();
        for (String field : period ? List.of("member", "fromMonth", "toMonth", "state", "origin", "requestedAt", "comments", "feeSnapshot", "decision")
                : List.of("member", "source", "state", "requestedAt", "requestedDate", "reasonKey", "nps", "comment")) { projection.put(field, "$" + field); }
        if (!period) { projection.put("effectiveDate", "$decision.effectiveDate"); }
        return new ListDataset(definition, period ? "inactivity_periods" : "leave_requests", stages, projection,
                period ? Set.of("toMonth", "comments", "feeSnapshot", "decision", "member.memberNumber") : Set.of("effectiveDate", "reasonKey", "nps", "comment", "member.memberNumber"),
                (field, value) -> String.valueOf(value)).withScope(query -> query.filters().stream().anyMatch(f -> f.field().equals("state")) ? null
                        : period ? new Document("state", new Document("$in", List.of("REQUESTED", "APPROVED", "ACTIVE"))) : new Document("state", "PENDING"));
    }
}
