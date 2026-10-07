package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.platform.application.ClubSavedViewProvisioner;
import org.springframework.stereotype.Service;

/** S13 R-13-17: real clubs receive the system view through provisioning and startup backfill. */
@Service
public class LifecycleSavedViewSeeder implements ClubSavedViewProvisioner {
    private final SavedViewService views;
    public LifecycleSavedViewSeeder(SavedViewService views) { this.views = views; }
    @Override public void provision() { views.seedPlannedLeaves(); }
}
