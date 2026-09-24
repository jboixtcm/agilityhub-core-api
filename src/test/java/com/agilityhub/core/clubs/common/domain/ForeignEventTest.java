package com.agilityhub.core.clubs.common.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E5-T05: the envelope clubs.common reads S06 `WeekValidated` into (deferred N-33). */
class ForeignEventTest {
    @Test void T_15_12_theWeekValidatedEnvelopeReadsKindOrTypeAndNeverANullPayload() throws Exception {
        var fromKind = new ForeignEvent("WeekValidated", null, "club-a", "Week", "week-a", Instant.parse("2026-10-05T07:00:00Z"), null, null, null,
                DomainEvent.Origin.BACKOFFICE);
        assertThat(fromKind.type()).isEqualTo("WeekValidated");
        assertThat(fromKind.payload()).isEmpty();
        var fromType = new ForeignEvent(null, "WeekValidated", "club-a", "Week", "week-a", Instant.parse("2026-10-05T07:00:00Z"), Map.of("weekId", "week-a"),
                "account-a", null, DomainEvent.Origin.BACKOFFICE);
        assertThat(fromType.type()).isEqualTo("WeekValidated");
        // The outbox JSON of a SchedulingEvent (`kind`, extra fields ignored) reads into the envelope.
        var json = "{\"kind\":\"WeekValidated\",\"clubId\":\"club-a\",\"aggregateId\":\"week-a\",\"occurredAt\":\"2026-10-05T07:00:00Z\","
                + "\"payload\":{\"weekId\":\"week-a\",\"classIds\":[\"c1\"]},\"origin\":\"BACKOFFICE\",\"unknown\":1}";
        var read = new ObjectMapper().registerModule(new JavaTimeModule()).readValue(json, ForeignEvent.class);
        assertThat(read.type()).isEqualTo("WeekValidated");
        assertThat(read.aggregateId()).isEqualTo("week-a");
        assertThat(read.payload()).containsEntry("weekId", "week-a");
    }
}
