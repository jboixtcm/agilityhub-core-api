package com.agilityhub.core.migration;

import com.agilityhub.core.migration.application.*;
import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.migration.persistence.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
class MigrationReportsIT extends AbstractIntegrationTest {
    static final String CLUB="migration-report-test",OTHER="migration-report-other",RUN="migration-report-run";
    @Autowired MongoTemplate mongo; @Autowired MigrationRunRepository runs; @Autowired MigrationReports reports; @Autowired MockMvc mvc;
    @Autowired MigrationResetRepository reset; @Autowired TransactionTemplate transactions;
    @MockitoBean MigrationReportStore store;
    Instant expiry;
    @BeforeEach void seed() {
        TenantContext.clear(); expiry=clock.instant().plus(Duration.ofDays(30));
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB,OTHER)),MigrationRun.class);
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB,OTHER)),"audit_entries");
        try(var scope=TenantContext.open(CLUB)) {
            runs.insert(new MigrationRun(RUN,CLUB,"PLAYOFF","APPLY","PRODUCTION",2,"COMPLETED",clock.instant(),clock.instant(),Map.of(),
                    new InputManifest(List.of()),List.of(),"migration-reports/"+CLUB+"/"+RUN+"/report.xlsx",expiry,null));
        }
        when(store.downloadUrl(anyString(),any())).thenReturn("https://private.example.test/report.xlsx");
    }
    String path(String club,String run) { return "/api/v1/platform/clubs/"+club+"/migrations/"+run+"/report"; }
    org.springframework.test.web.servlet.request.RequestPostProcessor actor(String club,String role) {
        return jwt().jwt(token->token.subject("fictional-operator").claim("clubId",club)).authorities(()->"ROLE_"+role);
    }
    @Test void T_18_16_onlyClubAdminOrPlatformAdminCanReceiveSignedLink() throws Exception {
        mvc.perform(get(path(CLUB,RUN))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(CLUB,RUN)).with(actor(CLUB,"MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(get(path(CLUB,RUN)).with(actor(CLUB,"INSTRUCTOR"))).andExpect(status().isForbidden());
        mvc.perform(get(path(CLUB,RUN)).with(actor(OTHER,"ADMIN"))).andExpect(status().isForbidden());
        verifyNoInteractions(store);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)),"audit_entries")).isZero();
        mvc.perform(get(path(CLUB,RUN)).with(actor(CLUB,"ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.downloadUrl").value("https://private.example.test/report.xlsx"));
        mvc.perform(get(path(CLUB,RUN)).with(actor(OTHER,"AGILITYHUB_ADMIN"))).andExpect(status().isOk());
        mvc.perform(get(path(OTHER,RUN)).with(actor(OTHER,"AGILITYHUB_ADMIN"))).andExpect(status().isNotFound());
        var audit=mongo.find(Query.query(Criteria.where("clubId").is(CLUB)),org.bson.Document.class,"audit_entries");
        assertThat(audit).hasSize(2).allMatch(entry->"DATA_EXPORTED".equals(entry.getString("action")) && RUN.equals(entry.getString("entityId")));
        assertThat(audit.toString()).doesNotContain("https://","downloadUrl","signature");
        assertThat(TenantContext.current()).isNull();
    }
    @Test void T_18_16_impersonationAndSupportPathsAreExplicitlyRestricted() throws Exception {
        mvc.perform(get(path(CLUB,RUN)).with(jwt().jwt(token->token.subject("fictional-operator").claim("clubId",CLUB).claim("imp",true))
                .authorities(()->"ROLE_ADMIN"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("IMPERSONATION_DENIED"));
        assertThat(com.agilityhub.core.identity.application.SupportAccessService.restricted("GET",path(CLUB,RUN))).isTrue();
        assertThat(com.agilityhub.core.identity.application.SupportAccessService.restricted("HEAD",path(CLUB,RUN))).isTrue();
        verifyNoInteractions(store);
    }
    @Test void T_18_16_reportDownloadAuditStillBlocksDestructiveReset() throws Exception {
        try(var scope=TenantContext.open(CLUB)) {
            transactions.executeWithoutResult(status->{ reset.trackWrites(); reset.checkpoint(true,clock.instant()); });
        }
        mvc.perform(get(path(CLUB,RUN)).with(actor(CLUB,"ADMIN"))).andExpect(status().isOk());
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("DATA_EXPORTED")),"audit_entries")).isEqualTo(1);
        try(var scope=TenantContext.open(CLUB)) {
            // A report retry must not absorb the real export audit into the import's clean checkpoint.
            assertThat(runs.reportPublished(RUN,"migration-reports/"+CLUB+"/"+RUN+"/report.xlsx",clock.instant())).isFalse();
            assertThatThrownBy(()->transactions.executeWithoutResult(status->reset.requireNoLaterWrites()))
                    .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo(ErrorCode.MIGRATION_ALREADY_APPLIED));
        }
    }
    @Test void T_18_16_exactExpiryDeniesDownloadAndTenantPurgePreservesAudit() throws Exception {
        clock.setInstant(expiry);
        mvc.perform(get(path(CLUB,RUN)).with(actor(CLUB,"ADMIN"))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EXPORT_EXPIRED"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)),"audit_entries")).isZero();
        try(var scope=TenantContext.open(OTHER)) {
            assertThat(reports.expired(clock.instant())).isEmpty(); assertThat(reports.purge(RUN,clock.instant())).isFalse();
        }
        verifyNoInteractions(store);
        try(var scope=TenantContext.open(CLUB)) {
            assertThat(reports.expired(clock.instant())).containsExactly(RUN);
            assertThat(reports.purge(RUN,clock.instant())).isTrue(); assertThat(reports.purge(RUN,clock.instant())).isFalse();
            var retained=runs.findById(RUN).orElseThrow(); assertThat(retained.status()).isEqualTo("COMPLETED");
            assertThat(retained.reportFileKey()).isNull(); assertThat(retained.reportPurgedAt()).isEqualTo(expiry);
            assertThat(retained.inputManifest()).isNotNull();
        }
        verify(store).delete("migration-reports/"+CLUB+"/"+RUN+"/report.xlsx");
    }
}
