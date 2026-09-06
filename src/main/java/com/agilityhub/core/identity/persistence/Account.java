package com.agilityhub.core.identity.persistence;

import com.agilityhub.core.identity.domain.Email;
import com.agilityhub.core.identity.domain.LoginLockout;
import com.agilityhub.core.shared.domain.audit.Sensitive;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Internal persistence model. API views explicitly select public fields. */
@Document("accounts")
public record Account(@Id String id, String email, String name, String locale,
                      @Sensitive(Sensitive.Strategy.HIDE) @JsonIgnore String passwordHash, Set<PlatformRole> platformRoles, Status status,
                      @Sensitive(Sensitive.Strategy.HIDE) @JsonIgnore Security security, Map<String, String> externalIds,
                      boolean onboardingPending, Instant createdAt, com.agilityhub.core.shared.application.NotificationAccounts.EmailStatus emailStatus,
                      Instant emailVerifiedAt, Source createdSource, Instant lastLoginAt, String lastLoginClientId,
                      List<Consent> consents, List<ConsentPostponement> consentPostponements) {
    public Account(String id, String email, String name, String locale, String passwordHash, Set<PlatformRole> platformRoles,
                   Status status, Security security, Map<String, String> externalIds, boolean onboardingPending, Instant createdAt,
                   com.agilityhub.core.shared.application.NotificationAccounts.EmailStatus emailStatus,
                   Instant emailVerifiedAt, Source createdSource, Instant lastLoginAt, String lastLoginClientId) {
        this(id, email, name, locale, passwordHash, platformRoles, status, security, externalIds, onboardingPending, createdAt,
                emailStatus, emailVerifiedAt, createdSource, lastLoginAt, lastLoginClientId, List.of(), List.of());
    }
    public Account(String id, String email, String name, String locale, String passwordHash, Set<PlatformRole> platformRoles,
                   Status status, Security security, Map<String, String> externalIds, boolean onboardingPending, Instant createdAt) {
        this(id, email, name, locale, passwordHash, platformRoles, status, security, externalIds, onboardingPending, createdAt, null);
    }
    public Account(String id, String email, String name, String locale, String passwordHash, Set<PlatformRole> platformRoles,
                   Status status, Security security, Map<String, String> externalIds, boolean onboardingPending, Instant createdAt,
                   com.agilityhub.core.shared.application.NotificationAccounts.EmailStatus emailStatus) {
        this(id, email, name, locale, passwordHash, platformRoles, status, security, externalIds, onboardingPending, createdAt,
                emailStatus, null, null, null, null);
    }
    public Account {
        email = Email.normalize(email);
        platformRoles = Set.copyOf(platformRoles);
        externalIds = Map.copyOf(externalIds);
        consents = consents == null ? List.of() : List.copyOf(consents);
        consentPostponements = consentPostponements == null ? List.of() : List.copyOf(consentPostponements);
    }
    public long familyVersion() { return security == null ? 0 : security.tokenFamilyVersion(); }
    public LoginLockout lockout() { return security == null ? LoginLockout.empty() : new LoginLockout(security.failedLogins(),
            security.failedLoginWindowStartedAt(), security.lockedUntil(), security.lockoutLevel()); }
    @Override public String toString() { return "Account[id=" + id + "]"; }
    public enum PlatformRole { AGILITYHUB_ADMIN }
    public enum Status { ACTIVE, BLOCKED, MERGED, ERASED }
    public enum Source { SIGNUP, IMPORT_LEARN, CONSOLE, MIGRATION }
    public enum ConsentPolicy { PLATFORM, CLUB }
    /** clubId is null only for a platform policy; club acceptance never crosses tenants. */
    public record Consent(ConsentPolicy policy, String clubId, String version, Instant acceptedAt) { }
    public record ConsentPostponement(ConsentPolicy policy, String clubId, String version, int count) { }
    public record Security(int failedLogins, Instant lockedUntil, Instant passwordChangedAt, long tokenFamilyVersion,
                           Instant failedLoginWindowStartedAt, int lockoutLevel, Instant accessRevokedAt) {
        public Security(int failedLogins, Instant lockedUntil, Instant passwordChangedAt, long tokenFamilyVersion) {
            this(failedLogins, lockedUntil, passwordChangedAt, tokenFamilyVersion, null, 0, null);
        }
    }
}
