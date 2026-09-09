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
        Map<Class<?>, Supplier<DomainEvent>> samples = Map.of(
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
                "account-a", null, DomainEvent.Origin.BACKOFFICE));
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
            if (event instanceof com.agilityhub.core.clubs.catalogs.domain.CatalogEvent changed) {
                for (var kind : com.agilityhub.core.clubs.catalogs.domain.CatalogKind.values()) { assertThat(catalog).contains(kind.eventType()); }
                assertThat(changed.aggregateType()).isEqualTo("Level");
                assertThat(changed.aggregateId()).isEqualTo("level-a");
                assertThat(changed.payload()).containsKeys("id", "diff");
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
}
