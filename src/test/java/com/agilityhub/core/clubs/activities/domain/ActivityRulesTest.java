package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ActivityRulesTest {
    final ZoneId zone=ZoneId.of("Europe/Madrid");
    final LocalDate date=LocalDate.of(2026,8,7);
    final ActivityTimes times=ActivityTimes.of(date,"18:30","20:30",LocalDate.of(2026,7,1),date.minusDays(1),zone);
    void error(ErrorCode code,Runnable operation) { assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(code)); }
    @Test void T_07_01_slugsTransliterateCollideAndLockAfterFirstPublication() {
        assertThat(SlugGenerator.generate("Torneig d'Estiu 2026",s -> false)).isEqualTo("torneig-estiu-2026");
        assertThat(SlugGenerator.generate("Torneig d'Estiu 2026",s -> !s.endsWith("-3"))).isEqualTo("torneig-estiu-2026-3");
        assertThat(SlugGenerator.generate("Çarles · L’agilitat",s -> false)).isEqualTo("carles-lagilitat");
        assertThat(SlugGenerator.generate("🙂",s -> false)).hasSizeBetween(3,80);
        assertThat(SlugGenerator.generate("Long title ".repeat(50),s -> s.length()==80)).hasSizeLessThanOrEqualTo(80);
        SlugGenerator.editable("first","second",false); SlugGenerator.editable("first","first",true);
        error(ErrorCode.SLUG_LOCKED,() -> SlugGenerator.editable("first","second",true));
        for(String bad:Arrays.asList(null,"ab","A big title","x".repeat(81))) error(ErrorCode.VALIDATION_ERROR,() -> SlugGenerator.editable("first",bad,false));
    }
    @Test void T_07_02_htmlAllowlistNormalizesFormattingAndRejectsScriptUrls() {
        String clean=HtmlSanitizer.sanitize("<script>alert(1)</script><p onclick='x()' style='color:red'>Cal <b>portar</b> <i>cartilla</i><img src='x'><a href='javascript:alert(1)'>bad</a><a href='mailto:test@example.test'>email</a></p>");
        assertThat(clean).contains("<strong>portar</strong>","<em>cartilla</em>","mailto:test&#64;example.test","rel=\"noopener\"","target=\"_blank\"").doesNotContain("script","onclick","style","<img","javascript");
        assertThat(HtmlSanitizer.sanitize("<a href='https://example.test'>safe</a><iframe src='x'>bad</iframe>")).contains("https://example.test").doesNotContain("iframe");
        assertThat(HtmlSanitizer.text("<p>A &amp; B</p> <p>More</p>")).isEqualTo("A & B More");
        assertThat(HtmlSanitizer.text("a".repeat(600))).hasSize(500); assertThat(HtmlSanitizer.text(null)).isNull(); assertThat(HtmlSanitizer.sanitize(null)).isNull();
    }
    ActivityRules.Input input(LocalDate day,String start,String end,LocalDate from,LocalDate to,boolean atClub,List<String> rings) {
        return new ActivityRules.Input(new LocalizedText(Map.of("ca","Example"),"ca"),ActivityType.OTHER,atClub,atClub?null:"Elsewhere",rings,day,start,end,from,to,1,5,List.of("level"));
    }
    @Test void T_07_03_draftAndPublicationValidationUsesEnabledLocalesAndCatalogs() {
        var valid=input(date,"18:30","20:30",date.minusDays(3),date.minusDays(1),true,List.of("ring"));
        ActivityRules.validate(valid,10,Set.of("ring"),Set.of("level")); ActivityRules.publish(valid,date);
        error(ErrorCode.ACTIVITY_INCOMPLETE,() -> ActivityRules.publish(input(null,null,null,null,null,true,List.of("ring")),date));
        error(ErrorCode.ACTIVITY_IN_PAST,() -> ActivityRules.publish(valid,date.plusDays(1)));
        error(ErrorCode.VALIDATION_ERROR,() -> ActivityRules.validate(input(date,null,null,null,null,false,List.of("ring")),10,Set.of("ring"),Set.of("level")));
        error(ErrorCode.INVALID_TIME_RANGE,() -> ActivityRules.validate(input(date,"20:30","18:30",date,date.plusDays(1),true,List.of()),10,Set.of(),Set.of("level")));
        error(ErrorCode.INVALID_TIME_RANGE,() -> ActivityRules.validate(input(date,null,null,date,date.plusDays(1),true,List.of()),10,Set.of(),Set.of("level")));
        error(ErrorCode.INVALID_TIME_RANGE,() -> ActivityRules.validate(input(date,null,null,date,date.minusDays(1),true,List.of()),10,Set.of(),Set.of("level")));
        error(ErrorCode.INVALID_SLOT_GRANULARITY,() -> ActivityRules.validate(input(date,"18:37","20:30",null,null,true,List.of("ring")),10,Set.of("ring"),Set.of("level")));
        error(ErrorCode.VALIDATION_ERROR,() -> ActivityRules.validate(valid,10,Set.of(),Set.of("level")));
        error(ErrorCode.VALIDATION_ERROR,() -> ActivityRules.validate(valid,10,Set.of("ring"),Set.of()));
        assertThat(ActivityRules.text(Map.of("ca","Hello"),"title","ca",List.of("ca","en"),80,true).resolve("en").value()).isEqualTo("Hello");
        assertThat(ActivityRules.text(null,"description","ca",List.of("ca"),80,false)).isNull();
        error(ErrorCode.LOCALE_NOT_ENABLED,() -> ActivityRules.text(Map.of("en","Hello"),"title","ca",List.of("ca"),80,true));
        error(ErrorCode.VALIDATION_ERROR,() -> ActivityRules.text(Map.of("en","Hello"),"title","ca",List.of("ca","en"),80,true));
        error(ErrorCode.VALIDATION_ERROR,() -> ActivityRules.text(Map.of(),"title","ca",List.of("ca"),80,true));
        error(ErrorCode.VALIDATION_ERROR,() -> ActivityRules.text(Map.of("ca"," "),"title","ca",List.of("ca"),80,true));
        error(ErrorCode.VALIDATION_ERROR,() -> ActivityRules.text(Map.of("ca","long"),"title","ca",List.of("ca"),2,true));
        error(ErrorCode.INVALID_TIME_RANGE,() -> ActivityRules.time("99:50"));
    }
    @Test void T_07_04_ringWindowContainsActivityAndRespectsOpeningAndMinimumDuration() {
        assertThat(RingBlockWindow.of(date,"18:30","20:30",null,null,zone,LocalTime.of(7,0),LocalTime.of(22,0),30)).isEqualTo(new RingBlockWindow(Instant.parse("2026-08-07T16:30:00Z"),Instant.parse("2026-08-07T18:30:00Z")));
        assertThat(RingBlockWindow.of(date,"18:30","20:30","17:30","21:00",zone,LocalTime.of(7,0),LocalTime.of(22,0),30).from()).isEqualTo(Instant.parse("2026-08-07T15:30:00Z"));
        error(ErrorCode.INVALID_TIME_RANGE,() -> RingBlockWindow.of(date,"18:30","20:30","19:00","21:00",zone,LocalTime.MIN,LocalTime.MAX,30));
        error(ErrorCode.OUTSIDE_OPENING_HOURS,() -> RingBlockWindow.of(date,"06:00","08:00",null,null,zone,LocalTime.of(7,0),LocalTime.MAX,30));
        error(ErrorCode.OUTSIDE_OPENING_HOURS,() -> RingBlockWindow.of(date,"18:30","20:30",null,null,zone,null,null,30));
        error(ErrorCode.INVALID_TIME_RANGE,() -> RingBlockWindow.of(date,"18:30","18:40",null,null,zone,LocalTime.MIN,LocalTime.MAX,30));
        error(ErrorCode.INVALID_TIME_RANGE,() -> RingBlockWindow.of(date,null,null,null,null,zone,LocalTime.MIN,LocalTime.MAX,30));
    }
    ActivityEligibility.Member member(String status,boolean active,LocalDate leave,Map<String,Object> block,Map<String,Object> actor,List<ActivityEligibility.Inactivity> inactivity) {
        return new ActivityEligibility.Member(status,active,leave,block,actor,inactivity,List.of(new ActivityEligibility.Dog("dog-c",true,"C"),new ActivityEligibility.Dog("dog-d",true,"D")));
    }
    void check(ActivityState state,ActivityEligibility.Member m,List<String> levels,boolean enabled,boolean inactivity,boolean registered,Instant now) {
        ActivityEligibility.check(state,times,date,m,levels,enabled,inactivity,registered,null,now);
    }
    @Test void T_07_05_eligibilityChecksRunInSpecifiedOrderIncludingActorAndInactivity() {
        var m=member("ACTIVE",true,null,Map.of(),Map.of(),List.of()); var now=times.registrationOpensAt();
        check(ActivityState.PUBLISHED,m,List.of("D","E"),true,true,false,now);
        error(ErrorCode.NOT_FOUND,() -> check(ActivityState.DRAFT,m,List.of(),true,true,false,now));
        error(ErrorCode.ACTIVITY_NOT_PUBLISHED,() -> check(ActivityState.FINISHED,m,List.of(),true,true,false,now));
        for(Instant closed:List.of(now.minusSeconds(1),times.registrationClosesAt())) error(ErrorCode.REGISTRATION_CLOSED,() -> check(ActivityState.PUBLISHED,m,List.of(),true,true,false,closed));
        error(ErrorCode.MEMBER_NOT_ACTIVE,() -> check(ActivityState.PUBLISHED,member("LEFT",true,null,Map.of(),Map.of(),List.of()),List.of(),true,true,false,now));
        error(ErrorCode.MEMBER_NOT_ACTIVE,() -> check(ActivityState.PUBLISHED,member("ACTIVE",false,null,Map.of(),Map.of(),List.of()),List.of(),true,true,false,now));
        error(ErrorCode.MEMBER_NOT_ACTIVE,() -> check(ActivityState.PUBLISHED,member("ACTIVE",true,date,Map.of(),Map.of(),List.of()),List.of(),true,true,false,now));
        for(boolean actor:List.of(false,true)) error(ErrorCode.BOOKING_BLOCKED,() -> check(ActivityState.PUBLISHED,member("ACTIVE",true,null,actor?Map.of():Map.of("active",true,"reason","Unpaid"),actor?Map.of("active",true):Map.of(),List.of()),List.of(),true,true,false,now));
        var inactive=member("ACTIVE",true,null,Map.of(),Map.of(),List.of(new ActivityEligibility.Inactivity(date,date)));
        error(ErrorCode.INACTIVITY_PERIOD,() -> check(ActivityState.PUBLISHED,inactive,List.of(),true,true,false,now)); check(ActivityState.PUBLISHED,inactive,List.of(),true,false,false,now);
        error(ErrorCode.LEVEL_NOT_ALLOWED,() -> check(ActivityState.PUBLISHED,m,List.of("B"),true,true,false,now)); check(ActivityState.PUBLISHED,m,List.of("B"),false,true,false,now);
        error(ErrorCode.ALREADY_REGISTERED,() -> check(ActivityState.PUBLISHED,m,List.of(),true,true,true,now));
        assertThat(ActivityEligibility.admitted(m.dogs(),List.of("D"),true,"dog-c")).isFalse(); assertThat(ActivityEligibility.admitted(m.dogs(),List.of("D"),true,"dog-d")).isTrue();
    }
    @Test void T_07_06_cancellationUsesExclusiveDeadlineAndImpersonationReason() {
        CancellationDeadline.check(times.registrationClosesAt().minusSeconds(1),"REGISTRATION_CLOSE",RegistrationState.ACTIVE,times,false,null);
        error(ErrorCode.REGISTRATION_NOT_CANCELLABLE,() -> CancellationDeadline.check(times.registrationClosesAt(),"REGISTRATION_CLOSE",RegistrationState.ACTIVE,times,false,null));
        for(var state:List.of(RegistrationState.ACTIVE,RegistrationState.WAITLISTED)) {
            CancellationDeadline.check(times.startsAt().minusSeconds(1),"EVENT_START",state,times,false,null);
            error(ErrorCode.REGISTRATION_NOT_CANCELLABLE,() -> CancellationDeadline.check(times.startsAt(),"EVENT_START",state,times,false,null));
        }
        error(ErrorCode.VALIDATION_ERROR,() -> CancellationDeadline.check(times.registrationClosesAt(),"REGISTRATION_CLOSE",RegistrationState.ACTIVE,times,true,null));
        CancellationDeadline.check(times.registrationClosesAt(),"REGISTRATION_CLOSE",RegistrationState.ACTIVE,times,true,"Requested by member");
        error(ErrorCode.INVALID_STATE,() -> CancellationDeadline.check(times.registrationOpensAt(),"EVENT_START",RegistrationState.CANCELLED,times,false,null));
    }
    @Test void T_07_07_historyAndPlaceLabelsNeverDependOnDog() {
        assertThat(ActivityRows.historyState(RegistrationState.ACTIVE,null,ActivityState.FINISHED)).isEqualTo("DONE");
        assertThat(ActivityRows.historyState(RegistrationState.ACTIVE,null,ActivityState.PUBLISHED)).isNull();
        assertThat(ActivityRows.historyState(RegistrationState.WAITLISTED,null,ActivityState.FINISHED)).isNull();
        for(var reason:RegistrationCancelReason.values()) assertThat(ActivityRows.historyState(RegistrationState.CANCELLED,reason,ActivityState.CANCELLED)).isEqualTo(reason==RegistrationCancelReason.ACTIVITY_CANCELLED?"CANCELLED_BY_CLUB":"CANCELLED");
        var rings=Map.of("a","Central","b","Mountain");
        assertThat(ActivityRows.place(true,null,List.of("a","b"),rings,rings,"all rings")).isEqualTo("all rings");
        assertThat(ActivityRows.place(true,null,List.of("a"),rings,rings,"all rings")).isEqualTo("Central");
        assertThat(ActivityRows.place(false,"Town",List.of(),rings,rings,"all rings")).isEqualTo("Town");
    }
    @Test void T_07_08_localInstantsUseClubZoneAndDstDayLength() {
        assertThat(times.startsAt()).isEqualTo(Instant.parse("2026-08-07T16:30:00Z")); assertThat(times.registrationClosesAt()).isEqualTo(Instant.parse("2026-08-06T22:00:00Z"));
        var argentina=ActivityTimes.of(date,"18:30","20:30",date.minusDays(1),date.minusDays(1),ZoneId.of("America/Argentina/Buenos_Aires"));
        assertThat(argentina.startsAt()).isEqualTo(Instant.parse("2026-08-07T21:30:00Z")); assertThat(argentina.registrationClosesAt()).isEqualTo(Instant.parse("2026-08-07T03:00:00Z"));
        var dst=ActivityTimes.of(LocalDate.of(2026,10,25),null,null,LocalDate.of(2026,10,25),LocalDate.of(2026,10,25),zone);
        assertThat(Duration.between(dst.startsAt(),dst.endsAt())).isEqualTo(Duration.ofHours(25));
        assertThat(dst.registrationOpen(dst.registrationOpensAt())).isTrue(); assertThat(dst.registrationOpen(dst.registrationClosesAt())).isFalse();
        assertThat(ActivityTimes.of(null,null,null,null,null,zone).registrationOpen(Instant.EPOCH)).isFalse();
    }
    @Test void T_07_09_stateMachinesRejectEveryUnlistedTransition() {
        for(var before:ActivityState.values()) for(var after:ActivityState.values()) {
            boolean allowed=before==ActivityState.DRAFT && Set.of(ActivityState.PUBLISHED,ActivityState.CANCELLED).contains(after)
                    || before==ActivityState.PUBLISHED && Set.of(ActivityState.DRAFT,ActivityState.FINISHED,ActivityState.CANCELLED).contains(after);
            if(allowed) ActivityTransitions.activity(before,after); else error(ErrorCode.INVALID_STATE,() -> ActivityTransitions.activity(before,after));
        }
        for(var before:RegistrationState.values()) for(var after:RegistrationState.values()) {
            boolean allowed=before==RegistrationState.ACTIVE && after==RegistrationState.CANCELLED || before==RegistrationState.WAITLISTED && Set.of(RegistrationState.ACTIVE,RegistrationState.CANCELLED).contains(after);
            if(allowed) ActivityTransitions.registration(before,after); else error(ErrorCode.INVALID_STATE,() -> ActivityTransitions.registration(before,after));
        }
    }
}
