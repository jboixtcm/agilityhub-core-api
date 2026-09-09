package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.identity.application.MigrationIdentityService;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.shared.application.EventPublisher;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class MigrationApplyService {
    private final CensusMigrationService census;
    private final MigrationIdentityService identities;
    private final MigrationClubAccess clubs;
    private final MigrationBankVault vault;
    private final MigrationRunRepository runs;
    private final EventPublisher events;
    private final Clock clock;
    public MigrationApplyService(CensusMigrationService census, MigrationIdentityService identities,
            MigrationClubAccess clubs, MigrationBankVault vault, MigrationRunRepository runs, EventPublisher events, Clock clock) {
        this.census=census; this.identities=identities; this.clubs=clubs; this.vault=vault;
        this.runs=runs; this.events=events; this.clock=clock;
    }
    public void validate(PlayoffPlanner.Plan plan) {
        if (plan.changes().stream().anyMatch(c -> map(c.fields().get("paymentMethod")).get("iban")!=null)) { vault.requireKey(); }
    }
    public record Applied(String id, @com.agilityhub.core.shared.domain.audit.AuditField Map<String,Object> details) { }
    @Audited(action=AuditAction.MIGRATION_APPLIED,entityType="'MigrationRun'",entity="#result.id",reason="'MIGRATED'")
    public Applied apply(MigrationRun run, PlayoffPlanner.Plan plan) {
        validate(plan);
        for (var change:plan.changes()) {
            var fields=new LinkedHashMap<>(change.fields());
            if (change.entity().equals("members")) {
                var payment=new LinkedHashMap<>(map(fields.get("paymentMethod")));
                String iban=string(payment.remove("iban"));
                if (iban!=null) {
                    payment.put("ibanEncrypted",vault.encrypt(iban,run.clubId(),change.id()));
                    payment.put("ibanLast4",iban.substring(iban.length()-4));
                }
                fields.put("paymentMethod",payment);
                census.member(change.id(),fields);
            } else if (change.entity().equals("dogs")) { census.dog(change.id(),fields); }
            else { census.group(change.id(),fields); }
        }
        for (var identity:plan.identities()) {
            String account=identities.apply(identity.memberId(),identity.email(),identity.name(),identity.locale(),identity.active(),identity.roles());
            census.member(identity.memberId(),Map.of("accountId",account));
        }
        clubs.reserveNumbers(plan.maximumNumber());
        var report=new MigrationReport(false,plan.rows()); var counters=new TreeMap<String,Long>();
        for (String entity:List.of("members","dogs","familyGroups","accounts")) {
            for (String outcome:List.of("CREATED","UPDATED","SKIPPED","ERROR")) { counters.put(entity+outcome,report.count(entity,outcome)); }
        }
        var completed=new MigrationRun(run.id(),run.clubId(),run.source(),run.mode(),run.env(),run.mappingVersion(),
                "COMPLETED",run.startedAt(),clock.instant(),counters);
        runs.replace(completed);
        events.publish(new MigrationEvent("MigrationRunCompleted",run.id(),run.clubId(),clock.instant(),Map.of("counters",counters)));
        return new Applied(completed.id(), Map.of("counters", counters));
    }
}
