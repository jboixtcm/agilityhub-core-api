package com.agilityhub.core.clubs.messaging.application.ports;

import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Null objects of the engine's ports (E7-T02 step 1): an application without the census, the catalogs or the bookings
 * still starts, and its notifications reach nobody. The owning contexts' adapters replace them.
 */
@AutoConfiguration
public class MessagingPortDefaults {
    @Bean @ConditionalOnMissingBean(MemberDirectoryPort.class)
    MemberDirectoryPort memberDirectory() {
        return new MemberDirectoryPort() {
            public Optional<MemberContact> find(String memberId) { return Optional.empty(); }
            public List<MemberContact> findAll(java.util.Collection<String> memberIds) { return List.of(); }
            public Optional<MemberContact> byAccount(String accountId) { return Optional.empty(); }
            public List<String> membersWithEmail(String address) { return List.of(); }
        };
    }
    @Bean @ConditionalOnMissingBean(MemberContactsWriterPort.class)
    MemberContactsWriterPort memberContactsWriter() {
        return new MemberContactsWriterPort() {
            public boolean markEmailBounced(String memberId, String address) { return false; }
            public boolean unsubscribeClubNews(String memberId, java.time.Instant at) { return false; }
        };
    }
    @Bean @ConditionalOnMissingBean(StaffDirectoryPort.class)
    StaffDirectoryPort staffDirectory() {
        return new StaffDirectoryPort() {
            public List<MemberContact> admins() { return List.of(); }
            public List<MemberContact> instructors() { return List.of(); }
            public List<MemberContact> instructorsOf(String classSessionId) { return List.of(); }
            public List<MemberContact> instructorsByIds(java.util.Collection<String> instructorIds) { return List.of(); }
        };
    }
    @Bean @ConditionalOnMissingBean(BookingRelevancePort.class)
    BookingRelevancePort bookingRelevance() { return (bookingId, trainingBookingId, now) -> false; }
    @Bean @ConditionalOnMissingBean(WaitlistRelevancePort.class)
    WaitlistRelevancePort waitlistRelevance() { return (entryId, now) -> false; }
    @Bean @ConditionalOnMissingBean(SignupContactPort.class)
    SignupContactPort signupContacts() { return memberId -> Optional.empty(); }
}
