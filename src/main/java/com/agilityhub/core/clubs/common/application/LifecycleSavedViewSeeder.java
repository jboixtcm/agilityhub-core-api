package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.shared.application.DemoSeedStep;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LifecycleSavedViewSeeder implements DemoSeedStep {
    private final SavedViewService views;
    public LifecycleSavedViewSeeder(SavedViewService views) { this.views = views; }
    public int order() { return 90; }
    public Map<String, Integer> apply(Input input) { views.seedPlannedLeaves(); return Map.of(); }
}
