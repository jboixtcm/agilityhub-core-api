package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.persistence.PlatformConsoleProjection;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PAY-10 (owner decision of 13 Nov 2026, S17 R-17-05): saving Stripe keys runs the connection test with the new key and refuses a key
 * that fails it, recording a passed test; on the `prod` profile, saved live keys switch to test mode only with `testModeOverride`.
 * Collaborators are mocks; fictional keys and accounts only.
 */
class PlatformProvidersStripeKeysTest {
    static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    final PlatformClubChanges changes = mock(PlatformClubChanges.class);
    final ConsolePaymentAccess payments = mock(ConsolePaymentAccess.class);
    final PlatformConsoleProjection projection = mock(PlatformConsoleProjection.class);
    final ClubRepository clubs = mock(ClubRepository.class);
    final MockEnvironment environment = new MockEnvironment();
    final PlatformProviders providers = new PlatformProviders(changes, payments, projection, payments, Clock.fixed(NOW, ZoneOffset.UTC));
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach void setUp() {
        TenantContext.open("club-a");
        ReflectionTestUtils.setField(providers, "clubs", clubs);
        ReflectionTestUtils.setField(providers, "environment", environment);
        when(payments.encrypt(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> "enc:" + call.getArgument(3));
        stored(Map.of());
    }
    @AfterEach void tearDown() { TenantContext.clear(); }

    void stored(Map<String, Object> stripe) {
        var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club("club-a", "club-a.example.test"));
        tree.set("paymentProviders", mapper.valueToTree(stripe.isEmpty() ? Map.of() : Map.of("STRIPE", stripe)));
        tree.put("version", 4);
        Club club = mapper.convertValue(tree, Club.class);
        when(changes.club()).thenReturn(club);
    }
    static Map<String, Object> liveKeys() {
        return Map.of("enabled", true, "mode", "live", "publishableKey", "pk_live_example1234", "secretKeyEnc", "enc:live-secret",
                "webhookSecretEnc", "enc:live-webhook", "accountId", "acct_example_live");
    }
    static Map<String, Object> input(String mode, boolean keys) {
        var input = new HashMap<String, Object>(Map.of("version", 4, "enabled", true, "publishableKey", "pk_" + mode + "_example4321", "mode", mode));
        if (keys) { input.put("secretKey", "sk_" + mode + "_fictional_new"); input.put("webhookSecret", "whsec_fictional_new"); }
        return input;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> written() {
        var fields = ArgumentCaptor.forClass(Map.class);
        verify(changes).write(any(), fields.capture(), eq("ClubPaymentProviderChanged"), any());
        return (Map<String, Object>) ((Map<String, Object>) fields.getValue().get("paymentProviders")).get("STRIPE");
    }
    static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    @Test void PAY_10_aNewSecretKeyIsTestedWithThatKeyAndThePassedTestIsRecordedWithTheSave() {
        var input = input("test", true);
        when(payments.ping("test", "sk_test_fictional_new")).thenReturn(new ConsolePaymentAccess.Connection(true, "acct_example_new", "Example Club", "test"));

        var tested = providers.testKey("STRIPE", input);
        providers.update("STRIPE", input, tested);

        verify(payments).ping("test", "sk_test_fictional_new");
        verify(payments, never()).ping();
        var stripe = written();
        assertThat(stripe).containsEntry("secretKeyEnc", "enc:secretKeyEnc").containsEntry("mode", "test")
                .containsEntry("lastTestAt", NOW).containsEntry("accountId", "acct_example_new");
    }

    @Test void PAY_10_aKeyTheProviderRefusesOrCannotReachIsNeverSaved() {
        var input = input("test", true);
        when(payments.ping("test", "sk_test_fictional_new")).thenThrow(new ApiException(ErrorCode.PROVIDER_CONFIG_INVALID));
        assertCode(() -> providers.testKey("STRIPE", input), ErrorCode.STRIPE_CONNECTION_FAILED);
        // Club creation passes no earlier test: the save runs it, and a refused key is not written either.
        assertCode(() -> providers.update("STRIPE", input, null), ErrorCode.STRIPE_CONNECTION_FAILED);
        doReturn(new ConsolePaymentAccess.Connection(false, null, null, "test")).when(payments).ping("test", "sk_test_fictional_new");
        assertCode(() -> providers.testKey("STRIPE", input), ErrorCode.STRIPE_CONNECTION_FAILED);
        verify(changes, never()).write(any(), any(), any(), any());
    }

    @Test void PAY_10_withoutAnEarlierTestTheSaveRunsItItself() {
        var input = input("test", true);
        when(payments.ping("test", "sk_test_fictional_new")).thenReturn(new ConsolePaymentAccess.Connection(true, "acct_example_created", "Example Club", "test"));

        providers.update("STRIPE", input, null);

        verify(payments).ping("test", "sk_test_fictional_new");
        assertThat(written()).containsEntry("accountId", "acct_example_created").containsEntry("lastTestAt", NOW);
    }

    @Test void PAY_10_nothingIsTestedWithoutANewSecretKeyOrForAnInputUpdateRefusesOnItsOwn() {
        assertThat(providers.testKey("SEPA_XML", Map.of("version", 4))).isNull();
        assertThat(providers.testKey("STRIPE", input("test", false))).isNull();
        var wrongPrefix = input("live", true); wrongPrefix.put("secretKey", "sk_test_fictional_new");
        assertThat(providers.testKey("STRIPE", wrongPrefix)).isNull();
        var noMode = input("test", true); noMode.remove("mode");
        assertThat(providers.testKey("STRIPE", noMode)).isNull();
        ReflectionTestUtils.setField(providers, "hostedDemo", true);
        assertThat(providers.testKey("STRIPE", input("live", true))).isNull();
        ReflectionTestUtils.setField(providers, "hostedDemo", false);
        verify(payments, never()).ping(any(), any());
        // A save that keeps the stored key (a new publishable key) asks the provider nothing and, as before, drops the last test.
        stored(Map.of("enabled", true, "mode", "test", "publishableKey", "pk_test_example1234", "secretKeyEnc", "enc:old", "webhookSecretEnc", "enc:old-webhook",
                "lastTestAt", NOW.minusSeconds(3600), "accountId", "acct_example_old"));
        providers.update("STRIPE", input("test", false), null);
        verify(payments, never()).ping(any(), any());
        assertThat(written()).containsEntry("secretKeyEnc", "enc:old").doesNotContainKeys("lastTestAt", "accountId");
    }

    @Test void PAY_10_onTheProdProfileSavedLiveKeysSwitchToTestModeOnlyWithTheOverride() {
        environment.setActiveProfiles("prod");
        stored(liveKeys());
        var input = input("test", true);
        when(payments.ping("test", "sk_test_fictional_new")).thenReturn(new ConsolePaymentAccess.Connection(true, "acct_example_test", "Example Club", "test"));

        for (Object override : List.of(false, "true")) {
            var refused = new HashMap<>(input); refused.put(PlatformProviders.TEST_MODE_OVERRIDE, override);
            assertThatThrownBy(() -> providers.update("STRIPE", refused, providers.testKey("STRIPE", refused))).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID);
                assertThat(e.details()).isEqualTo(Map.of("field", "mode", "reason", "LIVE_KEYS_SAVED"));
            });
        }
        assertThatThrownBy(() -> providers.update("STRIPE", input, null)).isInstanceOf(ApiException.class);
        verify(changes, never()).write(any(), any(), any(), any());

        var confirmed = new LinkedHashMap<>(input); confirmed.put(PlatformProviders.TEST_MODE_OVERRIDE, true);
        providers.update("STRIPE", confirmed, providers.testKey("STRIPE", confirmed));
        assertThat(written()).containsEntry("mode", "test").containsEntry("accountId", "acct_example_test");
    }

    @Test void PAY_10_theOverrideIsNeededOnlyOnProdAndOnlyToLeaveSavedLiveKeysForTest() {
        when(payments.ping(anyString(), anyString())).thenAnswer(call -> new ConsolePaymentAccess.Connection(true, "acct_example", "Example Club", call.getArgument(0)));
        // Staging (or any other profile): the switch needs no override.
        environment.setActiveProfiles("staging");
        stored(liveKeys());
        providers.update("STRIPE", input("test", true), null);
        // Prod: live to live (a key rotation), and a club whose saved keys are test, need none either.
        environment.setActiveProfiles("prod");
        providers.update("STRIPE", input("live", true), null);
        stored(Map.of("enabled", true, "mode", "test", "publishableKey", "pk_test_example1234", "secretKeyEnc", "enc:test", "webhookSecretEnc", "enc:test-webhook"));
        providers.update("STRIPE", input("test", true), null);
        providers.update("STRIPE", input("live", true), null);
        // Prod, nothing saved yet: test keys are configured freely.
        stored(Map.of());
        providers.update("STRIPE", input("test", true), null);
        verify(changes, times(5)).write(any(), any(), eq("ClubPaymentProviderChanged"), any());
    }
}
