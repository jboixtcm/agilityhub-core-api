package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.HostNames;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class HostTenantResolver implements com.agilityhub.core.shared.application.TenantHostResolver {
    private final ClubRepository clubs;
    private final Cache<String, Optional<String>> hosts = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    private final Cache<String, Boolean> localCorsHosts = Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5)).build();
    public HostTenantResolver(ClubRepository clubs) { this.clubs = clubs; }
    public Optional<String> resolve(String host) {
        String normalized = HostNames.normalize(host);
        if (normalized.isEmpty()) { return Optional.empty(); }
        return hosts.get(normalized, key -> clubs.findByHost(key).map(club -> club.id()));
    }
    // Evict negative lookups as well as hosts removed or reassigned by a domain update.
    public void invalidate() { hosts.invalidateAll(); localCorsHosts.invalidateAll(); }
    public boolean isCorsHost(String host, boolean local) {
        return local ? localCorsHosts.get(host, key -> clubs.findByAnyHost(key).isPresent()) : resolve(host).isPresent();
    }
}
