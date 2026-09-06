package com.agilityhub.core.identity.application;

import com.agilityhub.core.clubs.messaging.application.SystemNotificationService;
import com.agilityhub.core.identity.domain.Email;
import com.agilityhub.core.identity.domain.IdentityEvent;
import com.agilityhub.core.identity.persistence.*;
import com.agilityhub.core.shared.application.EventPublisher;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantHostResolver;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Service;

@Service
public class MagicLinkService {
    public static final String GRANT = "urn:agilityhub:grant:magic-link";
    private final AccountRepository accounts;
    private final MembershipRepository memberships;
    private final MagicLinkTokenRepository tokens;
    private final IdentityTransactions transactions;
    private final AuthSettings settings;
    private final EventPublisher events;
    private final SystemNotificationService notifications;
    private final RegisteredClientRepository clients;
    private final TenantHostResolver hosts;
    private final Executor executor;
    private final Clock clock;
    private final URI issuer;
    public MagicLinkService(AccountRepository accounts, MembershipRepository memberships, MagicLinkTokenRepository tokens,
            IdentityTransactions transactions, AuthSettings settings, EventPublisher events, SystemNotificationService notifications,
            RegisteredClientRepository clients, TenantHostResolver hosts, @Qualifier("magicLinkExecutor") Executor executor,
            Clock clock, @Value("${identity.issuer}") String issuer) {
        this.accounts = accounts; this.memberships = memberships; this.tokens = tokens; this.transactions = transactions;
        this.settings = settings; this.events = events; this.notifications = notifications; this.clients = clients;
        this.hosts = hosts; this.executor = executor; this.clock = clock; this.issuer = URI.create(issuer);
    }
    /** Identical request path for known/unknown emails; lookup, persistence and delivery run after enqueueing. */
    public void request(String email, MagicLinkToken.Purpose purpose, String clientId, String redirectUri, String host, String ip, String agent) {
        String normalized = Email.normalize(email);
        String clubId = TenantContext.current();
        try {
            executor.execute(() -> {
                try (var scope = clubId == null ? (TenantContext.Scope) () -> { } : TenantContext.open(clubId)) {
                    createAndSend(normalized, purpose, clientId, redirectUri, host, ip, agent);
                } catch (RuntimeException failure) {
                    // Never log the request, URL, address, token, or exception message.
                    LoggerFactory.getLogger(MagicLinkService.class).warn("Magic link delivery failed ({})", failure.getClass().getSimpleName());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException overloaded) {
            throw new ApiException(ErrorCode.RATE_LIMITED, Map.of("retryAfter", 60));
        }
    }
    public void createAndSend(String email, MagicLinkToken.Purpose purpose, String clientId, String redirectUri, String host, String ip, String agent) {
        var client = clients.findByClientId(clientId);
        if (client == null || !client.getAuthorizationGrantTypes().contains(new AuthorizationGrantType(GRANT))) { return; }
        String clubId = TenantContext.current();
        String base;
        if (clientId.startsWith("clubs-")) {
            if (clubId == null || !hosts.resolve(host).filter(clubId::equals).isPresent()) { return; }
            base = "https://" + URI.create("https://" + host).getHost() + "/activacio";
        } else {
            if (clubId != null || !issuer.getHost().equalsIgnoreCase(host)) { return; }
            base = issuer.resolve("/magic-link").toString();
        }
        // A caller cannot turn a login link into an open redirect; only registered callback URIs are retained.
        if (redirectUri != null && !redirectUri.equals(base) && !client.getRedirectUris().contains(redirectUri)) { return; }
        String value = TokenService.opaque();
        String accountId = transactions.run(() -> {
            var account = accounts.findByEmail(email).orElse(null);
            if (account == null || account.status() != Account.Status.ACTIVE) { return null; }
            if (clubId != null && memberships.findByAccountId(account.id()).filter(m -> m.status() == Membership.Status.ACTIVE).isEmpty()) { return null; }
            accounts.touchSessions(account.id());
            var duration = purpose == MagicLinkToken.Purpose.WELCOME || purpose == MagicLinkToken.Purpose.ACCESS_RESEND
                    ? Duration.ofDays(settings.integer("auth.welcomeLinkDays")) : Duration.ofMinutes(settings.integer("auth.magicLinkMinutes"));
            var token = new MagicLinkToken(UUID.randomUUID().toString(), TokenService.digest(value), account.id(), clubId, clientId,
                    purpose, redirectUri, clock.instant(), clock.instant().plus(duration), null,
                    ip == null ? null : TokenService.digest(ip), TokenService.device(agent));
            tokens.insert(token);
            tokens.trim(account.id(), purpose, clock.instant(), token.id());
            events.publish(new IdentityEvent(IdentityEvent.Kind.MagicLinkRequested, clubId, account.id(), clock.instant(),
                    Map.of("accountId", account.id(), "clientId", clientId, "purpose", purpose.name())));
            return account.id();
        });
        if (accountId != null) {
            notifications.send(purpose == MagicLinkToken.Purpose.ACCESS_RESEND ? "N-27" : "N-25", accountId, Map.of("link", base + "?t=" + value));
        }
    }
}
