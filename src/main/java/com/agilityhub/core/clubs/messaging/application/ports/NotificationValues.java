package com.agilityhub.core.clubs.messaging.application.ports;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The raw, typed values an owning context gives the engine for the template variables; the engine's `VariableFormatter`
 * turns them into text in the recipient's language and the club's time zone (R-11-05). Besides these, a value may be a
 * `String`, a number, a `Boolean`, an enum, an `Instant`, a `LocalDate`, a `LocalTime`, a `YearMonth`, a `Money` or a
 * `LocalizedText`.
 */
public final class NotificationValues {
    private NotificationValues() { }

    /** A training slot or any interval: «8:00–8:30» in the club's time zone. */
    public record TimeRange(Instant start, Instant end) {
        public TimeRange { Objects.requireNonNull(start); Objects.requireNonNull(end); }
    }
    /** One changed field of N-08b / N-32d: a `messages_*` key rendered with its arguments («Hora: 18:50 → 19:00»). */
    public record Change(String messageKey, Map<String, Object> arguments) {
        public Change { Objects.requireNonNull(messageKey); arguments = arguments == null ? Map.of() : Map.copyOf(arguments); }
    }
    /** `changes`: the changed fields joined with « · ». */
    public record Changes(List<Change> items) {
        public Changes { items = items == null ? List.of() : List.copyOf(items); }
    }
    /**
     * A date always written in full with its weekday («dijous, 8 d’octubre de 2026»), never «ahir/avui/demà»: N-19's
     * `class_date` (S10 §8, E6-T04: «la classe de {class_date}», never «ahir»).
     */
    public record AbsoluteDate(java.time.LocalDate day) {
        public AbsoluteDate { Objects.requireNonNull(day); }
    }
    /** One dog of `dogs` («Duna (C)»); `level` may be null. */
    public record DogLabel(String name, Object level) { }
    /** `dogs`: «Duna (C), Rock (D)». */
    public record DogLabels(List<DogLabel> items) {
        public DogLabels { items = items == null ? List.of() : List.copyOf(items); }
    }
    /**
     * A value only the owner can write in a language (a class's automatic description, a resolved catalog label): rendered
     * with the recipient's locale. Never stored as such: the engine stores the resulting text.
     */
    public record Localized(Function<Locale, String> render) {
        public Localized { Objects.requireNonNull(render); }
        public String in(Locale locale) { return Objects.toString(render.apply(locale), ""); }
    }
}
