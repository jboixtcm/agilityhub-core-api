package com.agilityhub.core.clubs.dashboard.application.ports;

import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

/** Replace individual ports from E4/E5/E6/E8; absent verticals never fabricate data. */
@org.springframework.boot.autoconfigure.AutoConfiguration
public class DashboardPortDefaults {
    @Bean @ConditionalOnMissingBean(ClassOccupancyQuery.class)
    ClassOccupancyQuery dashboardClassOccupancy() { return (club, from, until) -> List.of(); }
    @Bean @ConditionalOnMissingBean(TrainingBookingsQuery.class)
    TrainingBookingsQuery dashboardTrainingBookings() { return (club, from, until) -> List.of(); }
    @Bean @ConditionalOnMissingBean(BookingActivity.class)
    BookingActivity dashboardBookingActivity() { return (club, from, until) -> Set.of(); }
    @Bean @ConditionalOnMissingBean(PendingRequestsQuery.class)
    PendingRequestsQuery dashboardPendingRequests() { return club -> new PendingRequestsQuery.Counts(0, 0); }
    @Bean @ConditionalOnMissingBean(FollowUpUnreadQuery.class)
    FollowUpUnreadQuery dashboardFollowUpUnread() { return (club, account) -> 0; }
    @Bean @ConditionalOnMissingBean(ClassSessionsQuery.class)
    ClassSessionsQuery dashboardClassSessions() { return (club, from, through) -> List.of(); }
    @Bean @ConditionalOnMissingBean(RiskEvaluator.class)
    RiskEvaluator dashboardRiskEvaluator() { return session -> false; }
}
