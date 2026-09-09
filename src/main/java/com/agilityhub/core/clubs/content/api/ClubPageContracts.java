package com.agilityhub.core.clubs.content.api;

import com.agilityhub.core.clubs.content.domain.PageContent;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ClubPageContracts {
    private ClubPageContracts() { }
    @Schema(name = "ClubPageCreate")
    public record Create(@NotBlank @Pattern(regexp = PageContent.KEY_PATTERN) String key,
            @NotNull Map<String, String> title, @NotNull Map<String, String> body, @NotNull Boolean active) { }
    @Schema(name = "ClubPagePatch")
    public record Patch(@NotNull @Min(1) Integer version,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> title,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Map<String, String> body,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) Boolean active) { }
    @Schema(name = "ClubPageLastChange")
    public record LastChange(Instant at, @Schema(types = {"string", "null"}) @JsonInclude(JsonInclude.Include.ALWAYS) String by) { }
    @Schema(name = "ClubPageHistory")
    public record History(int version, Instant publishedAt, @Schema(types = {"string", "null"}) @JsonInclude(JsonInclude.Include.ALWAYS) String by,
            Map<String, String> title, Map<String, String> body) { }
    @Schema(name = "ClubPage")
    public record Page(String key, Map<String, String> title, Map<String, String> body, int version,
            @Schema(types = {"string", "null"}, format = "date-time") @JsonInclude(JsonInclude.Include.ALWAYS) Instant publishedAt, boolean active,
            LastChange lastChange,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @JsonInclude(JsonInclude.Include.NON_NULL) List<History> history) { }
    @Schema(name = "PublicClubPage")
    public record PublicPage(String key, String title, String body, int version, Instant publishedAt, boolean active) { }
}
