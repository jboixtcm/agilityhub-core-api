package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.application.TokenService;
import com.agilityhub.core.identity.persistence.RefreshToken;
import com.agilityhub.core.identity.persistence.RefreshTokenRepository;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * E11-T11 (review #3 of E11-T02 round 2), S01 R-01-06: the grace's chain guard («a token whose child has moved on gets no grace»)
 * is a conditional write on the child inside the grace's transaction. The two calls are interleaved: the grace's transaction has
 * read its snapshot when another transaction rotates the child and commits, and only then does the grace check the child. A
 * plain read still sees the child live in its snapshot. Everything happens at one instant, so the family's retention extension
 * (`$max expiresAt`, which also writes the child when the clock has moved) is a no-op and only the guard's own write can see the
 * rotation: it conflicts, the grace runs again, finds the child moved on, and the reuse revokes the family.
 */
class RefreshGraceChainGuardIT extends IdentityIntegrationSupport {
    @MockitoSpyBean RefreshTokenRepository spied;
    @Autowired PlatformTransactionManager manager;

    @Test void R_01_06_T_01_04_aChildRotatedWhileTheGraceChecksItGivesNoSibling() throws Exception {
        var a = login();
        clock.advance(Duration.ofMinutes(1));
        var b = readTokens(refresh(refreshValue(a), HOST, "clubs-app").andExpect(status().isOk()).andReturn().getResponse());
        String childId = mongo.findAll(RefreshToken.class).stream().filter(token -> token.tokenHash().equals(TokenService.digest(refreshValue(b))))
                .findFirst().orElseThrow().id();
        var interleaved = new AtomicBoolean();
        doAnswer(call -> {
            if (interleaved.compareAndSet(false, true)) {
                // The rival: the child's holder rotates it in its own transaction, which commits before the grace checks the child.
                CompletableFuture.runAsync(() -> {
                    try (var tenant = TenantContext.open("club-a")) {
                        Boolean rotated = new TransactionTemplate(manager).execute(status -> spied.rotate(childId, "hash-grandchild", clock.instant()));
                        assertThat(rotated).isTrue();
                    }
                }).get(30, TimeUnit.SECONDS);
            }
            return call.callRealMethod();
        }).when(spied).claimLiveChild(any(), any());

        refresh(refreshValue(a), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));

        assertThat(interleaved).isTrue();
        var family = mongo.findAll(RefreshToken.class);
        assertThat(family).as("no sibling was issued").hasSize(2).allMatch(token -> token.status() == RefreshToken.Status.REVOKED);
        assertThat(mongo.findAll(com.agilityhub.core.platform.persistence.SecurityEvent.class))
                .anyMatch(event -> event.type().name().equals("REFRESH_TOKEN_REUSED"));
        // The child, rotated by the rival, is a spent token of a revoked family.
        refresh(refreshValue(b), HOST, "clubs-app").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFRESH_REUSED"));
    }
}
