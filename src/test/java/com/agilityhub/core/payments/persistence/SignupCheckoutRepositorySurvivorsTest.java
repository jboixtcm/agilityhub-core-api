package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link SignupCheckoutRepository} (S12 R-12-21/22, T-12-16, T-12-31): the stored provider session
 * id is read back, and whether a session is a standalone card setup is what Mongo answers.
 */
class SignupCheckoutRepositorySurvivorsTest {
    final MongoTemplate mongo = mock(MongoTemplate.class);
    final SignupCheckoutRepository sessions = new SignupCheckoutRepository(mongo);

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open("club-a"); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_12_16_theStoredProviderSessionIsReadBack() {
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("checkout_sessions")))
                .thenReturn(new Document("_id", "session-1").append("providerSessionId", "cs_fake_1"), (Document) null);

        assertThat(sessions.providerSession("session-1")).contains("cs_fake_1");
        // An unknown session has none (and is no failure).
        assertThat(sessions.providerSession("session-unknown")).isEmpty();
    }

    @Test void T_12_31_aStandaloneCardSetupIsWhatMongoAnswers() {
        when(mongo.exists(any(Query.class), eq(SignupCheckoutSession.class))).thenReturn(true, false);

        assertThat(sessions.standaloneCardSetup("session-1")).isTrue();
        assertThat(sessions.standaloneCardSetup("session-2")).isFalse();
    }
}
