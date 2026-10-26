package com.agilityhub.core.migration;

import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class MigrationReportCheckpointIT extends AbstractIntegrationTest {
    static final String CLUB="report-checkpoint", RUN="report-checkpoint-run";
    @Autowired MongoTemplate mongo;
    @Autowired MigrationRunRepository runs;
    @Autowired MigrationResetRepository reset;
    @Autowired TransactionTemplate transactions;

    @BeforeEach void seed() {
        for (String collection:List.of("migration_runs","migration_reset_guards","tenant_write_counters","members")) {
            mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)),collection);
        }
        try (var tenant=TenantContext.open(CLUB)) {
            transactions.executeWithoutResult(status->{
                reset.trackWrites();
                mongo.insert(new Document("_id","report-member").append("clubId",CLUB).append("remarks","Before"),"members");
                runs.insert(new MigrationRun(RUN,CLUB,"PLAYOFF","APPLY","PRODUCTION",2,"COMPLETED",clock.instant(),clock.instant(),Map.of(),
                        null,List.of(),null,clock.instant().plusSeconds(60),null));
                reset.checkpoint(true,clock.instant());
            });
        }
    }

    @Test void T_18_16_reportPublicationPreservesAnOtherwiseCleanImportCheckpoint() {
        try (var tenant=TenantContext.open(CLUB)) {
            assertThat(runs.reportPublished(RUN,"migration-reports/"+CLUB+"/"+RUN+"/report.xlsx",clock.instant())).isTrue();
            assertThatCode(()->transactions.executeWithoutResult(status->reset.requireNoLaterWrites())).doesNotThrowAnyException();
            assertThat(runs.reportPublished(RUN,"migration-reports/"+CLUB+"/"+RUN+"/report.xlsx",clock.instant())).isFalse();
            assertThatCode(()->transactions.executeWithoutResult(status->reset.requireNoLaterWrites())).doesNotThrowAnyException();
        }
    }

    @Test void T_18_16_reportPublicationCannotHideAPostImportMemberEdit() {
        try (var tenant=TenantContext.open(CLUB)) {
            mongo.updateFirst(Query.query(Criteria.where("clubId").is(CLUB).and("_id").is("report-member")),
                    new Update().set("remarks","After import"),"members");
            assertThat(runs.reportPublished(RUN,"migration-reports/"+CLUB+"/"+RUN+"/report.xlsx",clock.instant())).isTrue();
            assertThatThrownBy(()->transactions.executeWithoutResult(status->reset.requireNoLaterWrites()))
                    .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
            assertThat(mongo.findById("report-member",Document.class,"members")).containsEntry("remarks","After import");
        }
    }
}
