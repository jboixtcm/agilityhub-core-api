package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.domain.HostNames;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.CacheLoads;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class HostTenantResolver implements com.agilityhub.core.shared.application.TenantHostResolver {
    private final ClubRepository clubs;
    private final CacheLoads<String, Optional<String>> hosts = CacheLoads.of(Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5)).build());
    private final CacheLoads<String, Optional<String>> localCorsHosts = CacheLoads.of(Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5)).build());
    public HostTenantResolver(ClubRepository clubs) { this.clubs = clubs; }
    public Optional<String> resolve(String host) {
        String normalized = HostNames.normalize(host);
        if (normalized.isEmpty()) { return Optional.empty(); }
        return hosts.get(normalized, key -> clubs.findByHost(key).map(club -> club.id()));
    }
    // Evict negative lookups as well as hosts removed or reassigned by a domain update.
    public void invalidate() { hosts.invalidateAll(); localCorsHosts.invalidateAll(); }
    /** CORS needs the owning club too, including registered pending domains in local development. */
    public Optional<String> corsClub(String host, boolean local) {
        String normalized = HostNames.normalize(host);
        if (normalized.isEmpty()) { return Optional.empty(); }
        return local ? localCorsHosts.get(normalized, key -> clubs.findByAnyHost(key).map(club -> club.id())) : resolve(normalized);
    }
    public boolean isCorsHost(String host, boolean local) {
        return corsClub(host, local).isPresent();
    }
}
