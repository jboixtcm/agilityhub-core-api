package com.agilityhub.core.clubs.followup.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Function;

/** The answer a keyed follow-up write stores with its `Idempotency-Key` (E6-T03 round 5): the JSON its handler returns. */
final class KeyedAnswers {
    private KeyedAnswers() { }

    static <T> Function<T, byte[]> json(ObjectMapper mapper) {
        return value -> {
            try { return mapper.writeValueAsBytes(value); }
            catch (JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
        };
    }
}
