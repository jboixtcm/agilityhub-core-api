package com.agilityhub.core.migration.application;

import com.agilityhub.core.clubs.census.application.CensusMigrationService;
import com.agilityhub.core.identity.application.MigrationIdentityService;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.platform.application.MigrationClubAccess;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.migration.application.PlayoffImportService.*;

/** Called inside the import transaction, including identity outbox and the single summary audit. */
@Service
public class MigrationApplyService {
    private final CensusMigrationService census; private final MigrationIdentityService identity;
    private final MigrationClubAccess clubs; private final MigrationRunRepository runs;
    private final MigrationBankVault vault; private final Clock clock;
    public MigrationApplyService(CensusMigrationService census,MigrationIdentityService identity,MigrationClubAccess clubs,
            MigrationRunRepository runs,MigrationBankVault vault,Clock clock) {
        this.census=census; this.identity=identity; this.clubs=clubs; this.runs=runs; this.vault=vault; this.clock=clock;
    }
    @Audited(action=AuditAction.CATALOG_CHANGED,entityType="'MigrationRun'",entity="#result.id",reason="'MIGRATED'")
    public MigrationRun apply(Plan plan,boolean production) {
        for (var change:plan.changes()) {
            var fields=change.fields();
            switch(change.entity()) {
                case "members" -> {
                    if (change.email()!=null) {
                        String accountId=identity.apply(change.id(),change.email(),text(fields.get("firstName"))+" "+text(fields.get("lastName1")),
                                "ca","ACTIVE".equals(fields.get("status")),change.roles());
                        fields.put("accountId",accountId);
                    }
                    var payment=map(fields.get("paymentMethod"));
                    if (payment.get("iban")!=null) {
                        String iban=text(payment.remove("iban"));
                        payment.put("ibanEncrypted",vault.encrypt(iban,TenantContext.require(),change.id()));
                        payment.put("ibanLast4",iban.substring(iban.length()-4));
                    }
                    census.member(change.id(),fields);
                }
                case "dogs" -> census.dog(change.id(),fields);
                case "family_groups" -> census.group(change.id(),fields);
                default -> throw new IllegalArgumentException("Unknown migration entity");
            }
        }
        clubs.reserveNumbers(plan.maximumNumber());
        var counters=new TreeMap<String,Long>();
        for (String entity:List.of("members","dogs","familyGroups","accounts")) {
            for (String outcome:List.of("CREATED","UPDATED","SKIPPED","ERROR","WARNING")) { counters.put(entity+"."+outcome,plan.report().count(entity,outcome)); }
        }
        var run=new MigrationRun(UUID.randomUUID().toString(),TenantContext.require(),"PLAYOFF","APPLY",production?"PRODUCTION":"STAGING",
                plan.mappingVersion(),"COMPLETED",plan.startedAt(),clock.instant(),counters);
        return runs.save(run);
    }
}
