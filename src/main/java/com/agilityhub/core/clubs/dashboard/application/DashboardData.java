package com.agilityhub.core.clubs.dashboard.application;

import com.agilityhub.core.shared.application.contract.SignupWarning;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.*;
import java.util.List;

/** Immutable read models; no persistence entities or private census fields escape the adapters. */
public final class DashboardData {
    private DashboardData() { }
    public record Snapshot(Instant generatedAt, LocalDate today, Week week, Kpis kpis,
            RiskReview riskReview, PendingSignups pendingSignups, DogsByLevel dogsByLevel) { }
    public record Week(LocalDate start, LocalDate end) { }
    public record Counters(int pendingSignups, int pendingRequests, int followUpUnread) { }
    public record Kpis(ActiveMembers activeMembers, Occupancy classOccupancy, Training trainingBookings, PendingKpi pendingSignups) { }
    public record ActiveMembers(int value, int deltaThisMonth) { }
    public record Occupancy(Double percent, int booked, int capacity, int waitingTotal) { }
    public record Training(int value, int distinctMembers) { }
    public record PendingKpi(int value, int olderThanWarn, int warnDays) { }
    public record PendingSignups(int count, List<PendingItem> items) { }
    public record PendingItem(String memberId, String shortName, List<PendingDog> dogs, String planName,
            String paymentMethodType, List<SignupWarning> warnings, Instant submittedAt, int pendingDays) { }
    public record PendingDog(String name, String breed, boolean isAddDog) { }
    public record PendingSource(String memberId, String firstName, String lastName, boolean addDog,
            List<PendingDog> dogs, LocalizedText planName, String paymentMethodType, Instant submittedAt,
            boolean imageConsent, boolean accountProvided, boolean documentPending, boolean familyPending,
            boolean upfrontUnpaid, boolean readmission) { }
    public record RiskReview(String reviewTime, int lookaheadDays, boolean autoCancelSameDay, int count, List<RiskItem> items) { }
    public enum RiskStatus { CANCELLED, AT_RISK, WILL_CANCEL, PENDING_DECISION }
    public record RiskItem(String classSessionId, LocalDate date, String startTime, String displayDescription,
            String ringName, int booked, RiskStatus status, List<Notified> notified, Instant reviewAt) { }
    public record Notified(String memberFirstName, String gender, String dogName) { }
    public record DogsByLevel(int totalActiveDogs, int activeDogWeeks, List<Level> levels, int others) { }
    public record Level(String levelId, String code, String name, String color, int total, int withRecentBooking) { }
    public record LevelSource(String levelId, String code, LocalizedText name, String color, int order, boolean active) { }
    public record DogCount(String levelId, int total, int withRecentBooking) { }
}
