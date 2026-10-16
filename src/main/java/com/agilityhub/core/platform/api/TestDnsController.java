package com.agilityhub.core.platform.api;

import com.agilityhub.core.platform.application.FakeDomainDns;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * E10-T03: publishes records in the fake resolver of a local stack (`bin/e10-smoke`: a domain verified by its CNAME, then the
 * record lost for S15 P9's re-check), as `POST /test/clock` moves its clock. Same profile expression and guard as the clock
 * (never with `staging` or `prod`), not in the OpenAPI snapshot, no tenant. `values` replaces the `type` records of `name`;
 * an empty list is an explicit «no record».
 */
@RestController
@Profile(TestClockController.PROFILES)
@Hidden
public class TestDnsController {
    private final FakeDomainDns dns;
    public TestDnsController(FakeDomainDns dns) { this.dns = dns; }

    public record DnsRequest(FakeDomainDns.Type type, String name, List<String> values) { }

    @PostMapping("/api/v1/test/dns")
    @PreAuthorize("@environment.acceptsProfiles(T(org.springframework.core.env.Profiles).of('" + TestClockController.PROFILES + "'))")
    public Map<String, Object> publish(@RequestBody DnsRequest request) {
        // A DNS name, not a host: the TXT challenge's `_agilityhub-verify.` label is not a valid host label (HostNames).
        String name = request.name() == null ? "" : request.name().strip().toLowerCase(java.util.Locale.ROOT).replaceFirst("\\.$", "");
        if (request.type() == null || !name.matches("[a-z0-9_-]+(\\.[a-z0-9_-]+)+") || request.values() == null || request.values().stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        dns.publish(request.type(), name, request.values());
        return Map.of("type", request.type().name(), "name", name, "values", List.copyOf(request.values()));
    }
}
