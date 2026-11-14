package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.ConsentLedgerConverter;
import com.agilityhub.core.clubs.census.persistence.Member;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PRIV-05 (owner's decision of 13-11-2026): `GET /me/onboarding`'s `imageConsent`, the onboarding image box's initial state, is
 * `true` only when the member's own account gave the grant in force; a grant recorded by the migration, a data operation, staff or
 * a public sign-up reads as no answer (`null`); a refusal reads `false`.
 */
class MemberOnboardingImageConsentTest {
    static final Instant AT = Instant.parse("2026-10-01T10:00:00Z");
    @SuppressWarnings("unchecked") final CensusRepository<Member> members = mock(CensusRepository.class);
    final MemberOnboardingService service = new MemberOnboardingService(members, null);

    Boolean imageConsent(Map<String, Object> consents) {
        var member = new Member(); member.id = "member-a"; member.accountId = "account-a"; member.status = "ACTIVE"; member.consents = consents;
        when(members.findById("member-a")).thenReturn(Optional.of(member));
        return service.imageConsent("member-a", "account-a");
    }
    static Map<String, Object> image(Object... keyValues) {
        var image = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keyValues.length; i += 2) { image.put((String) keyValues[i], keyValues[i + 1]); }
        return Map.of("imageRights", image);
    }
    static Map<String, Object> row(Object... keyValues) {
        var row = new LinkedHashMap<String, Object>(Map.of("type", "IMAGE_USE", "version", "v1", "acceptedAt", AT));
        for (int i = 0; i < keyValues.length; i += 2) { row.put((String) keyValues[i], keyValues[i + 1]); }
        return row;
    }
    static Map<String, Object> ledger(Map<?, ?>... rows) { return new ConsentLedgerConverter().read(List.of(rows), null); }

    @Test void PRIV_05_aGrantRecordedByTheMembersOwnAccountPreTicksTheBox() {
        assertThat(imageConsent(image("granted", true, "at", AT, "version", "v1", "byAccountId", "account-a"))).isTrue();
        assertThat(imageConsent(ledger(row("granted", true, "source", "PROFILE", "byAccountId", "account-a")))).isTrue();
        assertThat(imageConsent(ledger(row("granted", true, "source", "SIGNUP", "origin", "APP", "actorAccountId", "account-a")))).isTrue();
    }

    @Test void PRIV_05_aGrantRecordedByStaffTheMigrationOrAPublicSignupDoesNotPreTickIt() {
        assertThat(imageConsent(image("granted", true, "at", AT, "version", "v1", "byAccountId", "admin-account"))).isNull();
        assertThat(imageConsent(image("granted", true))).isNull();
        assertThat(imageConsent(image("granted", true, "version", "LEGACY", "acceptedAt", AT, "source", "MIGRATED"))).isNull();
        assertThat(imageConsent(ledger(row("granted", true, "source", "SIGNUP", "origin", "PUBLIC")))).isNull();
        assertThat(imageConsent(ledger(row("granted", true, "source", "SIGNUP", "origin", "BACKOFFICE", "actorAccountId", "admin-account")))).isNull();
        // The latest row is the grant in force: an ADMIN's grant after the member's own does not pre-tick it.
        assertThat(imageConsent(ledger(row("granted", true, "source", "PROFILE", "byAccountId", "account-a"),
                row("granted", true, "source", "PROFILE", "byAccountId", "admin-account")))).isNull();
    }

    @Test void PRIV_05_aRefusalReadsFalseAndNothingRecordedReadsNull() {
        assertThat(imageConsent(image("granted", false, "byAccountId", "admin-account"))).isFalse();
        assertThat(imageConsent(ledger(row("granted", true, "byAccountId", "account-a"), row("granted", false, "byAccountId", "account-a")))).isFalse();
        assertThat(imageConsent(Map.of("privacyPolicy", Map.of("version", "v1")))).isNull();
        assertThat(imageConsent(null)).isNull();
        when(members.findById("other")).thenReturn(Optional.empty());
        assertThat(service.imageConsent("other", "account-a")).isNull();
        assertThat(MemberOnboardingService.grantedByOwnAccount(Map.of("byAccountId", "account-a"), null)).isFalse();
    }
}
