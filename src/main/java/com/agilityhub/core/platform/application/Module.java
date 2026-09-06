package com.agilityhub.core.platform.application;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Public module contract from CATALEG_MODULS.md, shared by all consuming contexts. */
public enum Module {
    FREE_TRAINING(false, false, true),
    BILLING(false, false, true),
    PACKS(false, false, true, BILLING),
    SINGLE_CLASS(false, false, false, BILLING),
    ACTIVITIES(false, false, true),
    FAMILY_GROUP(false, false, true),
    WAITLIST(false, true, true),
    TASKS(false, false, true),
    FAQ(true, true, true),
    SMS(false, false, true),
    PUSH(true, true, true),
    INACTIVITY(false, false, true),
    COURSES(false, false, true),
    LEARN_LINK(true, false, true),
    STATS(false, false, false),
    SOCIAL_LEAGUE(false, false, false, STATS);

    private final boolean selfService;
    private final boolean minimEnabled;
    private final boolean canicEnabled;
    private final List<Module> dependsOn;

    Module(boolean selfService, boolean minimEnabled, boolean canicEnabled, Module... dependsOn) {
        this.selfService = selfService;
        this.minimEnabled = minimEnabled;
        this.canicEnabled = canicEnabled;
        this.dependsOn = List.of(dependsOn);
    }

    public List<Module> dependsOn() { return dependsOn; }
    public boolean selfService() { return selfService; }
    public boolean defaultEnabled(Preset preset) {
        return switch (preset) {
            case MINIM -> minimEnabled;
            case CANIC -> canicEnabled;
        };
    }

    public static Set<Module> defaults(Preset preset) {
        return Arrays.stream(values()).filter(module -> module.defaultEnabled(preset))
                .collect(Collectors.toUnmodifiableSet());
    }

    public enum Preset { MINIM, CANIC }
}
