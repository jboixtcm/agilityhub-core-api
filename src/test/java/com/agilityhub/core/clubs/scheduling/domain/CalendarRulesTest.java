package com.agilityhub.core.clubs.scheduling.domain;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CalendarRulesTest {
    @Test void T_06_06_riskBoundariesAndAllRecipientLocales() throws Exception {
        var evaluator=new RiskEvaluator(new IcuMessageSource()::format); var now=Instant.parse("2026-08-24T16:00:00Z"); var tomorrow=LocalDate.of(2026,8,25);
        for(String tag:List.of("ca","es","en")) for(boolean auto:List.of(true,false)) {
            var policy=new RiskEvaluator.Policy(2,LocalTime.of(7,30),2,auto,ZoneId.of("Europe/Madrid")); var locale=Locale.forLanguageTag(tag);
            for(int count:List.of(0,1)) {
                var result=evaluator.evaluate(new RiskEvaluator.Input(ClassState.ACTIVE,false,count,tomorrow),policy,now,locale);
                assertThat(result.atRisk()).isTrue(); assertThat(result.text()).doesNotContain("{", "}").contains("07:30");
                assertThat(result.text()).contains(switch(tag) {case "ca" -> "alumne";case "es" -> "alumno";default -> "student";});
            }
            for(var input:List.of(new RiskEvaluator.Input(ClassState.ACTIVE,false,2,tomorrow),new RiskEvaluator.Input(ClassState.ACTIVE,true,1,tomorrow),
                    new RiskEvaluator.Input(ClassState.DRAFT,false,1,tomorrow),new RiskEvaluator.Input(ClassState.ACTIVE,false,1,tomorrow.plusDays(2)),
                    new RiskEvaluator.Input(ClassState.ACTIVE,false,1,tomorrow.minusDays(1)))) {
                assertThat(evaluator.evaluate(input,policy,now,locale).atRisk()).isFalse();
            }
            assertThat(evaluator.evaluate(new RiskEvaluator.Input(ClassState.ACTIVE,false,1,tomorrow),policy,Instant.parse("2026-08-25T05:30:00Z"),locale).atRisk()).isFalse();
            assertThat(evaluator.evaluate(new RiskEvaluator.Input(ClassState.ACTIVE,false,1,tomorrow),policy,Instant.parse("2026-08-25T06:00:00Z"),locale).atRisk()).isFalse();
        }
    }
    @Test void T_06_07_localInstantsAndReviewTimesAcrossBothDstTransitionsAndZones() {
        for(String name:List.of("Europe/Madrid","America/Argentina/Buenos_Aires")) {
            var zone=ZoneId.of(name);
            for(var date:List.of(LocalDate.of(2026,10,24),LocalDate.of(2026,10,26),LocalDate.of(2026,3,28),LocalDate.of(2026,3,30))) {
                assertThat(WeekCalendarRules.resolve(date,LocalTime.of(8,30),zone).instant()).isEqualTo(date.atTime(8,30).atZone(zone).toInstant());
                assertThat(WeekCalendarRules.reviewAt(date,LocalTime.of(7,30),zone)).isEqualTo(date.atTime(7,30).atZone(zone).toInstant());
            }
        }
        var madrid=ZoneId.of("Europe/Madrid");
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026,10,24),LocalTime.of(8,30),madrid).instant()).hasToString("2026-10-24T06:30:00Z");
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026,10,26),LocalTime.of(8,30),madrid).instant()).hasToString("2026-10-26T07:30:00Z");
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026,3,29),LocalTime.of(2,30),madrid).shifted()).isTrue();
        assertThat(WeekCalendarRules.resolve(LocalDate.of(2026,10,25),LocalTime.of(2,30),madrid).instant()).hasToString("2026-10-25T00:30:00Z");
    }
    @Test void T_06_08_exhaustiveStateMachinesAndTerminalNotesOnly() {
        for(var from:ClassState.values()) for(var to:ClassState.values()) for(var reason:ClassCancellationReason.values()) {
            boolean allowed=from==ClassState.DRAFT && (to==ClassState.ACTIVE || to==ClassState.CANCELLED && reason==ClassCancellationReason.DELETED)
                    || from==ClassState.ACTIVE && (to==ClassState.FINISHED || to==ClassState.CANCELLED);
            if(allowed) ClassSessionRules.transition(from,to,reason); else invalid(() -> ClassSessionRules.transition(from,to,reason));
        }
        invalid(() -> ClassSessionRules.transition(ClassState.ACTIVE,ClassState.CANCELLED,null));
        for(var from:WeekState.values()) for(var to:WeekState.values()) {
            boolean allowed=from==WeekState.PENDING && (to==WeekState.GENERATED || to==WeekState.VALIDATED) || from==WeekState.GENERATED && to==WeekState.VALIDATED;
            if(allowed) ClassSessionRules.transition(from,to); else invalid(() -> ClassSessionRules.transition(from,to));
        }
        for(var state:ClassState.values()) { ClassSessionRules.editable(state,Set.of("notes")); if(state==ClassState.CANCELLED || state==ClassState.FINISHED) invalid(() -> ClassSessionRules.editable(state,Set.of("ringId"))); else ClassSessionRules.editable(state,Set.of("ringId")); }
    }
    @Test void T_06_14_smsUsesAtMost160BasicGsmSeptetsIncludingTruncation() {
        assertThat(SchedulingSms.compact("Àgil … anul·lada!" )).isEqualTo("Agil ... anul lada!");
        assertThat(SchedulingSms.compact("é".repeat(200))).hasSize(160).endsWith("...").matches("[a-z.]+");
        assertThat(SchedulingSms.compact("Normal text")).isEqualTo("Normal text");
    }
    private void invalid(Runnable action) { assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE)); }
}
