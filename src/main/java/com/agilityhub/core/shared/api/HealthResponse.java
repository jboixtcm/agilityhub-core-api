package com.agilityhub.core.shared.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/** The health envelope, the same for 200 UP and 503 DOWN (INC-01; E5-T27 step 5). */
public record HealthResponse(@Schema(allowableValues = {"UP", "DOWN"}) String status, String version, Instant builtAt) {
}
