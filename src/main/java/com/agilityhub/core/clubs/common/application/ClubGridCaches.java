package com.agilityhub.core.clubs.common.application;

/**
 * S15 R-15-11 step (1): the club's grid caches that P1 `week-opening` invalidates itself, in its own item transaction and
 * whatever `FREE_TRAINING` says (E5-T09). The only grid cache today is S09's training grid (`TrainingGridCache`); the
 * S06 day grids are read live. A context that adds a grid cache implements this interface.
 */
public interface ClubGridCaches {
    void invalidateClub(String clubId);
}
