package com.agilityhub.core.clubs.activities.domain;


public enum ActivityState {
    DRAFT, PUBLISHED, FINISHED, CANCELLED;

    /**
     * S07 §6: the member's page of the activity (`GET /me/activities/{activityId}`) answers only for a PUBLISHED or FINISHED
     * one, and 404 otherwise; the history of 25 links a row to it only then (S10 §6, E6-T06).
     */
    public boolean memberPage() { return this == PUBLISHED || this == FINISHED; }
}
