package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** A student named on the staff copy of a class notice. */
public record RosterStudent(String name, @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String href) { }
