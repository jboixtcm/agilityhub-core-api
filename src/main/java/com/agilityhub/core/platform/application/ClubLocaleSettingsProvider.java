package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.application.LocaleSettingsProvider;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ClubLocaleSettingsProvider implements LocaleSettingsProvider {
    private final ClubRepository clubs;
    public ClubLocaleSettingsProvider(ClubRepository clubs) { this.clubs = clubs; }

    @Override public Optional<Settings> settings(String clubId) {
        try (var scope = TenantContext.open(clubId)) {
            return clubs.findById(clubId).map(club -> new Settings(club.locales(), club.defaultLocale()));
        }
    }
}
