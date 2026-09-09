package com.agilityhub.core.platform.application;

import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.shared.domain.*;
import org.springframework.stereotype.Service;

@Service
public class MigrationClubAccess {
    private final ClubRepository clubs;
    public MigrationClubAccess(ClubRepository clubs) { this.clubs=clubs; }
    public String resolve(String slug) { return clubs.findBySlug(slug).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).id(); }
    public void reserveNumbers(int maximum) { clubs.reserveMemberNumbers(maximum); }
}
