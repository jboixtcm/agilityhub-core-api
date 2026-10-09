package com.agilityhub.core.identity.application;

import com.agilityhub.core.identity.persistence.MagicLinkToken;
import com.agilityhub.core.platform.application.CensusClubSettings;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link SignupLinks} (S04 R-04-22): a validated signup gets a WELCOME link to the club app's home, and a
 * member's recognition gets a RECOGNITION link to the new-dog form, both on the club's verified app host. Collaborators are mocks;
 * fictional data.
 */
class SignupLinksSurvivorsTest {
    final MagicLinkService links = mock(MagicLinkService.class);
    final CensusIdentityService accounts = mock(CensusIdentityService.class);
    final CensusClubSettings clubs = mock(CensusClubSettings.class);
    final SignupLinks signupLinks = new SignupLinks(links, accounts, clubs);
    final Map<String, Object> variables = Map.of("club_name", "Club Example");

    @BeforeEach void setUp() {
        when(accounts.accessEmail("acc-1")).thenReturn("laura@example.test");
        when(clubs.appHost()).thenReturn("club-a.example.test");
    }

    @Test void E11_T06_aWelcomeSendsAWelcomeLinkToTheClubAppHome() {
        signupLinks.send("evt-1", "acc-1", true, variables);

        verify(links).createAndSend("laura@example.test", MagicLinkToken.Purpose.WELCOME, "clubs-app", null, "club-a.example.test", null, null,
                "evt-1", variables);
    }

    @Test void E11_T06_aRecognitionSendsARecognitionLinkToTheNewDogForm() {
        signupLinks.send("evt-2", "acc-1", false, variables);

        verify(links).createAndSend("laura@example.test", MagicLinkToken.Purpose.RECOGNITION, "clubs-app", "/gossos/nou", "club-a.example.test",
                null, null, "evt-2", variables);
    }
}
