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
        Map<Class<?>, Supplier<DomainEvent>> samples = Map.of(ClubConfigChanged.class, () -> new ClubConfigChanged(
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
            assertThat(event.aggregateType()).isEqualTo("Club");
            assertThat(event.aggregateId()).isEqualTo(event.clubId());
            assertThat(event.payload()).containsKey("diff");
            assertThat(event.actorAccountId()).isEqualTo("account-a");
            assertThat(event.impersonatedMemberId()).isNull();
            assertThat(event.origin()).isEqualTo(DomainEvent.Origin.BACKOFFICE);
            assertThat(event.occurredAt()).isNotNull();
        });
    }
}
