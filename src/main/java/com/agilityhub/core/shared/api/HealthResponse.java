package com.agilityhub.core.shared.api;

import java.time.Instant;

public record HealthResponse(String status, String version, Instant builtAt) {
}
