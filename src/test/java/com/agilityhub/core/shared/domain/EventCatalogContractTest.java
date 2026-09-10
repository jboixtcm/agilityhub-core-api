package com.agilityhub.core.shared.domain;

import com.agilityhub.core.shared.domain.events.ClubConfigChanged;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EventCatalogContractTest {
    @Test void E0_T04_everyEventImplementationEmitsACatalogName() throws Exception {
        Set<String> catalog = new HashSet<>();
        var pattern = Pattern.compile("`([A-Z][a-zA-Z]+)(?:[ `{])");
        for (String row : Files.readAllLines(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"))) {
            if (!row.startsWith("|")) { continue; }
            var matcher = pattern.matcher(row.split("\\|", -1)[1]);
            while (matcher.find()) { catalog.add(matcher.group(1)); }
        }
        Map<Class<?>, Supplier<DomainEvent>> samples = new java.util.HashMap<>(Map.of(
                com.agilityhub.core.clubs.common.domain.DataExported.class, () -> new com.agilityhub.core.clubs.common.domain.DataExported("club-a", "export-a", Instant.parse("2030-01-01T00:00:00Z"), "account-a", "members", "xlsx", 2),
                com.agilityhub.core.clubs.catalogs.domain.CatalogEvent.class, () -> new com.agilityhub.core.clubs.catalogs.domain.CatalogEvent(
                        com.agilityhub.core.clubs.catalogs.domain.CatalogKind.LEVEL, "club-a", "level-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("id", "level-a", "diff", Map.of()), "account-a"),
                com.agilityhub.core.platform.domain.events.ClubModulesChanged.class, () -> new com.agilityhub.core.platform.domain.events.ClubModulesChanged(
                        "club-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("diff", Map.of("modules", java.util.List.of("FAQ"))),
                        "account-a", null, DomainEvent.Origin.BACKOFFICE),
                com.agilityhub.core.identity.domain.ImpersonationEvent.class, () -> new com.agilityhub.core.identity.domain.ImpersonationEvent(
                        com.agilityhub.core.identity.domain.ImpersonationEvent.Kind.ImpersonationStarted, "club-a", "grant-a", Instant.parse("2030-01-01T00:00:00Z"), "account-a", "member-a"),
                com.agilityhub.core.identity.domain.IdentityEvent.class, () -> new com.agilityhub.core.identity.domain.IdentityEvent(
                        com.agilityhub.core.identity.domain.IdentityEvent.Kind.AccountCreated, null, "account-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("accountId", "account-a")),
                com.agilityhub.core.clubs.messaging.domain.NotificationEvent.class, () -> new com.agilityhub.core.clubs.messaging.domain.NotificationEvent(
                        com.agilityhub.core.clubs.messaging.domain.NotificationEvent.Kind.NotificationQueued, "club-a", "notification-a", Instant.parse("2030-01-01T00:00:00Z")),
                com.agilityhub.core.platform.domain.events.ParameterChanged.class, () -> new com.agilityhub.core.platform.domain.events.ParameterChanged(
                        "club-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("key", "signup.enabled", "before", true, "after", false),
                        "account-a", null, DomainEvent.Origin.BACKOFFICE), ClubConfigChanged.class, () -> new ClubConfigChanged(
                "club-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("diff", Map.of("name", "Example")),
                "account-a", null, DomainEvent.Origin.BACKOFFICE)));
        samples.put(com.agilityhub.core.shared.domain.events.MemberStatusChanged.class,
                () -> new com.agilityhub.core.shared.domain.events.MemberStatusChanged("club-a", "member-a", Instant.parse("2030-01-01T00:00:00Z"),
                        Map.of("memberId", "member-a", "before", "ACTIVE", "after", "LEFT", "effectiveDate", "2030-01-01"), "account-a", null, DomainEvent.Origin.BACKOFFICE));
        samples.put(com.agilityhub.core.clubs.catalogs.domain.InstructorChanged.class,
                () -> new com.agilityhub.core.clubs.catalogs.domain.InstructorChanged("club-a", "instructor-a", Instant.parse("2030-01-01T00:00:00Z"),
                        Map.of("id", "instructor-a", "diff", Map.of()), "account-a", DomainEvent.Origin.BACKOFFICE));
        samples.put(com.agilityhub.core.identity.domain.TeamMembershipChanged.class,
                () -> new com.agilityhub.core.identity.domain.TeamMembershipChanged("club-a", "membership-a", Instant.parse("2030-01-01T00:00:00Z"),
                        Map.of("accountId", "account-a", "clubId", "club-a", "rolesBefore", Set.of("MEMBER"), "rolesAfter", Set.of("MEMBER", "INSTRUCTOR")), "account-a", DomainEvent.Origin.BACKOFFICE));
        samples.put(com.agilityhub.core.clubs.catalogs.domain.OfferChanged.class,
                () -> new com.agilityhub.core.clubs.catalogs.domain.OfferChanged(com.agilityhub.core.clubs.catalogs.domain.OfferChanged.Kind.Plan,
                        "club-a", "plan-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("id", "plan-a", "diff", Map.of()), "account-a"));
        samples.put(com.agilityhub.core.clubs.census.domain.CensusEvent.class, () -> new com.agilityhub.core.clubs.census.domain.CensusEvent(
                "MemberUpdated", "club-a", "Member", "member-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("memberId", "member-a", "diff", Map.of()), "account-a", null, DomainEvent.Origin.BACKOFFICE));
        samples.put(com.agilityhub.core.clubs.followup.domain.AttachmentAdded.class, () -> new com.agilityhub.core.clubs.followup.domain.AttachmentAdded(
                "club-a", "attachment-a", Instant.parse("2030-01-01T00:00:00Z"), Map.of("attachmentId", "attachment-a", "entityType", "INSTRUCTOR_NOTE", "entityId", "dog-a"), "account-a", null, DomainEvent.Origin.BACKOFFICE));
        samples.put(com.agilityhub.core.clubs.content.domain.ClubPageChanged.class,
                () -> new com.agilityhub.core.clubs.content.domain.ClubPageChanged("club-a", "RULES", Instant.parse("2030-01-01T00:00:00Z"),
                        Map.of("key", "RULES", "version", 1, "active", true), "account-a", DomainEvent.Origin.BACKOFFICE));
        samples.put(com.agilityhub.core.migration.domain.MigrationEvent.class,
                () -> new com.agilityhub.core.migration.domain.MigrationEvent("MigrationRunStarted","run-a","club-a",
                        Instant.parse("2030-01-01T00:00:00Z"),Map.of("mode","APPLY","env","STAGING")));
        samples.put(com.agilityhub.core.payments.domain.SignupPaymentEvent.class,
                () -> new com.agilityhub.core.payments.domain.SignupPaymentEvent("UpfrontPaymentRecorded","club-a","payment-a",Instant.parse("2030-01-01T00:00:00Z"),Map.of("paymentId","payment-a","provider","MANUAL"),"account-a",null,DomainEvent.Origin.BACKOFFICE));
        var classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.agilityhub.core");
        Set<Class<?>> implementations = new HashSet<>();
        for (var type : classes) {
            if (!type.isInterface() && type.isAssignableTo(DomainEvent.class)) {
                implementations.add(type.reflect());
            }
        }
        assertThat(implementations).isNotEmpty().containsExactlyInAnyOrderElementsOf(samples.keySet());
        samples.values().forEach(factory -> {
            DomainEvent event = factory.get();
            assertThat(catalog).contains(event.type());
            if (event instanceof com.agilityhub.core.payments.domain.SignupPaymentEvent) {
                assertThat(catalog).contains("UpfrontPaymentRecorded","UpfrontPaymentSucceeded");
                assertThat(event.aggregateType()).isEqualTo("UpfrontPayment");assertThat(event.payload()).containsKeys("paymentId","provider");return;
            }
            if (event instanceof com.agilityhub.core.migration.domain.MigrationEvent) {
                assertThat(catalog).contains("MigrationRunStarted","MigrationRunCompleted","MigrationRunFailed");
                assertThat(event.aggregateType()).isEqualTo("MigrationRun");
                assertThat(event.actorAccountId()).isNull(); assertThat(event.impersonatedMemberId()).isNull();
                assertThat(event.origin()).isEqualTo(DomainEvent.Origin.SYSTEM); return;
            }
            if (event instanceof com.agilityhub.core.clubs.messaging.domain.NotificationEvent notification) {
                for (var kind : com.agilityhub.core.clubs.messaging.domain.NotificationEvent.Kind.values()) { assertThat(catalog).contains(kind.name()); }
                assertThat(notification.aggregateType()).isEqualTo("Notification");
                assertThat(notification.payload()).containsEntry("notificationId", "notification-a").containsEntry("channel", "EMAIL");
                assertThat(notification.actorAccountId()).isNull(); assertThat(notification.impersonatedMemberId()).isNull();
                assertThat(notification.origin()).isEqualTo(DomainEvent.Origin.SYSTEM); return;
            }
            if (event instanceof com.agilityhub.core.identity.domain.IdentityEvent identity) {
                for (var kind : com.agilityhub.core.identity.domain.IdentityEvent.Kind.values()) { assertThat(catalog).contains(kind.name()); }
                assertThat(identity.aggregateType()).isEqualTo("Account");
                assertThat(identity.payload()).containsEntry("accountId", "account-a");
                assertThat(identity.actorAccountId()).isNull(); assertThat(identity.impersonatedMemberId()).isNull();
                assertThat(identity.origin()).isEqualTo(DomainEvent.Origin.SYSTEM); return;
            }
            if (event instanceof com.agilityhub.core.identity.domain.ImpersonationEvent impersonation) {
                for (var kind : com.agilityhub.core.identity.domain.ImpersonationEvent.Kind.values()) { assertThat(catalog).contains(kind.name()); }
                assertThat(impersonation.aggregateType()).isEqualTo("ImpersonationGrant");
                assertThat(impersonation.payload()).containsEntry("actorAccountId", "account-a").containsEntry("memberId", "member-a");
                assertThat(impersonation.actorAccountId()).isEqualTo("account-a");
                assertThat(impersonation.impersonatedMemberId()).isEqualTo("member-a");
                assertThat(impersonation.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE); return;
            }
            if (event instanceof com.agilityhub.core.clubs.common.domain.DataExported exported) {
                assertThat(exported.aggregateType()).isEqualTo("ExportJob");
                assertThat(exported.payload()).containsEntry("rows", 2L).containsEntry("by", "account-a");
            } else if (event instanceof com.agilityhub.core.clubs.content.domain.ClubPageChanged) {
                assertThat(event.aggregateType()).isEqualTo("ClubPage"); assertThat(event.payload()).containsKeys("key", "version", "active");
            } else if (event instanceof com.agilityhub.core.clubs.census.domain.CensusEvent) {
                assertThat(event.aggregateType()).isEqualTo("Member"); assertThat(event.payload()).containsKeys("memberId", "diff");
            } else if (event instanceof com.agilityhub.core.clubs.followup.domain.AttachmentAdded) {
                assertThat(event.aggregateType()).isEqualTo("Attachment"); assertThat(event.payload()).containsKeys("attachmentId", "entityType", "entityId");
            } else if (event instanceof com.agilityhub.core.clubs.catalogs.domain.CatalogEvent changed) {
                for (var kind : com.agilityhub.core.clubs.catalogs.domain.CatalogKind.values()) { assertThat(catalog).contains(kind.eventType()); }
                assertThat(changed.aggregateType()).isEqualTo("Level");
                assertThat(changed.aggregateId()).isEqualTo("level-a");
                assertThat(changed.payload()).containsKeys("id", "diff");
            } else if (event instanceof com.agilityhub.core.clubs.catalogs.domain.OfferChanged changed) {
                for (var kind : com.agilityhub.core.clubs.catalogs.domain.OfferChanged.Kind.values()) { assertThat(catalog).contains(kind.name() + "Changed"); }
                assertThat(changed.aggregateType()).isEqualTo("Plan"); assertThat(changed.payload()).containsKeys("id", "diff");
            } else if (event instanceof com.agilityhub.core.shared.domain.events.MemberStatusChanged) {
                assertThat(event.aggregateType()).isEqualTo("Member"); assertThat(event.payload()).containsKeys("memberId", "before", "after", "effectiveDate");
            } else if (event instanceof com.agilityhub.core.clubs.catalogs.domain.InstructorChanged) {
                assertThat(event.aggregateType()).isEqualTo("Instructor"); assertThat(event.payload()).containsKeys("id", "diff");
            } else if (event instanceof com.agilityhub.core.identity.domain.TeamMembershipChanged) {
                assertThat(event.aggregateType()).isEqualTo("Membership"); assertThat(event.payload()).containsKeys("accountId", "clubId", "rolesBefore", "rolesAfter");
            } else if (event instanceof ClubConfigChanged || event instanceof com.agilityhub.core.platform.domain.events.ClubModulesChanged) {
                assertThat(event.aggregateType()).isEqualTo("Club");
                assertThat(event.aggregateId()).isEqualTo(event.clubId());
                assertThat(event.payload()).containsKey("diff");
            } else {
                assertThat(event.aggregateType()).isEqualTo("Parameter");
                assertThat(event.aggregateId()).isEqualTo("signup.enabled");
                assertThat(event.payload()).containsKeys("key", "before", "after");
            }
            assertThat(event.actorAccountId()).isEqualTo("account-a");
            assertThat(event.impersonatedMemberId()).isNull();
            assertThat(event.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
            assertThat(event.occurredAt()).isNotNull();
        });
    }
    @Test void T_04_11_T_04_17_T_04_19_T_04_21_signupEventsKeepS04PayloadContracts() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var catalog = Files.readString(Path.of("docs/specs/00-transversal/CATALEG_ESDEVENIMENTS.md"));
        var spec = Files.readString(Path.of("docs/specs/S04-alta-publica-i-validacio.md"));
        Map<String, String> expected = Map.of(
                "SignupSubmitted", "memberId,dogIds,planId,paymentMethodType,source,readmission,checkoutRequired",
                "SignupEdited", "memberId,dogId,diff",
                "MemberValidated", "memberId,memberNumber,dogs,nextInvoiceDate,upfrontPaymentIds,familyGroupId,readmission",
                "SignupRejected", "memberId,dogIds,reason,memberWasActive",
                "DogRegistered", "dogId,memberId,levelId",
                "SignupRecognitionRequested", "memberId,accountId,redirect");
        try (var input = getClass().getResourceAsStream("/fixtures/contracts/e3-events.json")) {
            var fixtures = mapper.readTree(input);
            assertThat(fixtures.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(expected.keySet());
            expected.forEach((name, fields) -> {
                assertThat(catalog).contains("`" + name);
                String row = spec.lines().filter(line -> line.startsWith("| `" + name + "`")).findFirst().orElseThrow();
                assertThat(fixtures.path(name).fieldNames()).toIterable().as(name).containsExactlyInAnyOrder(fields.split(","));
                for (String field : fields.split(",")) { assertThat(row).as(name).contains(field); }
            });
            assertThat(fixtures.at("/MemberValidated/dogs/0").fieldNames()).toIterable().containsExactly("dogId", "levelId");
            assertThat(fixtures.at("/SignupSubmitted/source").asText()).isEqualTo("PUBLIC");
        }
    }

}
