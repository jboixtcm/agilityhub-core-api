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
    /**
     * INC-64: the app (`clubs` or `clubs-admin`) of each verified club host Caddy asked about. Only a found host is kept: one that is
     * not verified yet is read again on its next request, so it serves its app as soon as its verification commits, even when that
     * commit happened in another process.
     */
    private final CacheLoads<String, String> apps = CacheLoads.of(Caffeine.newBuilder().maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5)).build());
    public HostTenantResolver(ClubRepository clubs) { this.clubs = clubs; }
    public Optional<String> resolve(String host) {
        String normalized = HostNames.normalize(host);
        if (normalized.isEmpty()) { return Optional.empty(); }
        return hosts.get(normalized, key -> clubs.findByHost(key).map(club -> club.id()));
    }
    /** INC-64 (S17 R-17-03): the app of a `VERIFIED` club domain; empty for any other host. */
    public Optional<String> app(String host) {
        String normalized = HostNames.normalize(host);
        if (normalized.isEmpty()) { return Optional.empty(); }
        return Optional.ofNullable(apps.get(normalized, key -> clubs.findByHost(key).flatMap(club -> club.domains().stream()
                .filter(domain -> domain.host().equals(key)).findFirst()).map(com.agilityhub.core.platform.persistence.Club.Domain::app).orElse(null)));
    }
    // Evict negative lookups as well as hosts removed or reassigned by a domain update.
    public void invalidate() { hosts.invalidateAll(); localCorsHosts.invalidateAll(); apps.invalidateAll(); }
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
