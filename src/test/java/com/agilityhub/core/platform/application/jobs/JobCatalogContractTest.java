package com.agilityhub.core.platform.application.jobs;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.EnumSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** S15 R-15-01 catalog rows, the S10 contract rows P3/P8 (R-15-13, R-15-18) and the rows still without a `Job` bean. */
class JobCatalogContractTest {
    @Test void R_15_01_catalogHasTheTenRowsInTickOrderWithUniqueRouteIds() {
        assertThat(JobCatalog.all()).extracting(JobDefinition::name).containsExactly(JobName.WEEK_OPENING, JobName.RISK_REVIEW,
                JobName.NO_SHOW_NOTICES, JobName.REMINDERS, JobName.EXPIRATIONS, JobName.WAITLIST_FIFO, JobName.PAYMENT_TIMEOUTS,
                JobName.CLASS_FINISHING, JobName.CLEANUP, JobName.BILLING_REMINDER);
        assertThat(JobCatalog.all()).extracting(JobDefinition::routeId).doesNotHaveDuplicates();
        assertThat(JobCatalog.find(JobName.TEST_NOOP)).isEmpty();
        assertThatThrownBy(() -> JobCatalog.definition(JobName.TEST_NOOP)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JobCatalog.byRoute("no-shows")).hasMessage("JOB_UNKNOWN");
    }

    @Test void T_10_26_T_15_13_noShowNoticesIsTheDailyP3RowAtMessagingNoShowNoticeTime() {
        var row = JobCatalog.byRoute("no-show-notices");
        assertThat(row.name()).isEqualTo(JobName.NO_SHOW_NOTICES);
        assertThat(row.cadence()).isEqualTo(Cadence.DAILY);
        assertThat(row.localTimeParameter()).isEqualTo("messaging.noShowNoticeTime");
        assertThat(row.dayParameter()).isNull();
        assertThat(row.module()).isNull();
        assertThat(row.catchUpWindow()).isEqualTo(CatchUpWindow.UNLIMITED);
        assertThat(row.switchParameter()).isEqualTo("jobs.noShowNotices.enabled");
    }

    @Test void T_15_18_classFinishingIsTheContinuousP8RowWithoutParameterOrModule() {
        var row = JobCatalog.byRoute("class-finishing");
        assertThat(row.name()).isEqualTo(JobName.CLASS_FINISHING);
        assertThat(row.cadence()).isEqualTo(Cadence.CONTINUOUS);
        assertThat(row.localTimeParameter()).isNull();
        assertThat(row.dayParameter()).isNull();
        assertThat(row.module()).isNull();
        assertThat(row.catchUpWindow()).isEqualTo(CatchUpWindow.CONTINUOUS);
        assertThat(row.switchParameter()).isEqualTo("jobs.classFinishing.enabled");
    }

    /**
     * E6-T01 assumption (as E5-T01): `GET /jobs` lists only rows with a registered `Job` bean and a bean-less row answers
     * `JOB_UNKNOWN` on trigger. P3 and P8 get their beans in E6-T04; P4, P5 and P10 later.
     */
    @Test void E6_T01_rowsWithoutAJobBeanAreKnownAndIncludeP3AndP8() {
        var implementations = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.agilityhub.core");
        var beans = EnumSet.noneOf(JobName.class);
        for (var type : implementations) {
            if (type.isInterface() || type.getModifiers().contains(com.tngtech.archunit.core.domain.JavaModifier.ABSTRACT) || !type.isAssignableTo(Job.class)) { continue; }
            beans.add(((Job) mock(type.reflect(), CALLS_REAL_METHODS)).name());
        }
        var missing = new TreeSet<JobName>();
        for (var row : JobCatalog.all()) { if (!beans.contains(row.name())) { missing.add(row.name()); } }
        assertThat(beans).contains(JobName.TEST_NOOP);
        assertThat(missing).isEqualTo(new TreeSet<>(Set.of(JobName.NO_SHOW_NOTICES, JobName.REMINDERS, JobName.EXPIRATIONS, JobName.CLASS_FINISHING,
                JobName.BILLING_REMINDER)));
    }
}
