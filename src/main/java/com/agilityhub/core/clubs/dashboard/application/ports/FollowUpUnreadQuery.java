package com.agilityhub.core.clubs.dashboard.application.ports;


public interface FollowUpUnreadQuery {
    int count(String clubId, String accountId);
}
