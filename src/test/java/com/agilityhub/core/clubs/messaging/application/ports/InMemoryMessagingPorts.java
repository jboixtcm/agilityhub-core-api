package com.agilityhub.core.clubs.messaging.application.ports;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E7-T02 step 1: the in-memory doubles of every port the engine reads or writes (`MemberDirectoryPort`,
 * `MemberContactsWriterPort`, `StaffDirectoryPort`, `SignupContactPort`, `BookingRelevancePort`, `WaitlistRelevancePort`),
 * with the people of the S11 examples: Laura (1 e-mail, 2 phones, Duna and Rock), Marc and Anna (one dog each), Pau on the
 * waiting list, Marta the class's instructor, Estel an instructor of another class, and two administrators — one with a member
 * record, one known only by the account. Mutable: each test adds, removes or changes what it needs.
 */
public final class InMemoryMessagingPorts implements MemberDirectoryPort, MemberContactsWriterPort, StaffDirectoryPort, SignupContactPort,
        BookingRelevancePort, WaitlistRelevancePort {
    public final Map<String, MemberContact> members = new LinkedHashMap<>();
    public final Map<String, List<String>> classInstructors = new ConcurrentHashMap<>();
    public final Map<String, String> instructorMembers = new LinkedHashMap<>();
    public final Set<String> activeInstructors = new LinkedHashSet<>();
    public final List<String> adminIds = new ArrayList<>();
    public final Map<String, ApplicantContact> applicants = new LinkedHashMap<>();
    public final Map<String, Instant> activeBookings = new ConcurrentHashMap<>();
    public final Map<String, Instant> notifiedEntries = new ConcurrentHashMap<>();
    public final List<String> unsubscribed = new ArrayList<>();

    public static InMemoryMessagingPorts s11Examples() {
        var ports = new InMemoryMessagingPorts();
        ports.put(member("member-laura", "account-laura", "Laura Serra Puig", "Laura", "FEMALE", "ca", List.of("laura@example.test"),
                List.of("+34600000001", "+34600000002"), dog("dog-duna", "Duna", "FEMALE"), dog("dog-rock", "Rock", "MALE")));
        ports.put(member("member-marc", "account-marc", "Marc Vila", "Marc", "MALE", "es", List.of("marc@example.test"), List.of("+34600000003"),
                dog("dog-ares", "Ares", "MALE")));
        ports.put(member("member-anna", "account-anna", "Anna Soler", "Anna", "FEMALE", "en", List.of("anna@example.test", "anna.work@example.test"), List.of(),
                dog("dog-lluna", "Lluna", "FEMALE")));
        ports.put(member("member-pau", "account-pau", "Pau Roca", "Pau", "MALE", "ca", List.of("pau@example.test"), List.of("+34600000004"), dog("dog-nit", "Nit", "MALE")));
        ports.put(member("member-marta", "account-marta", "Marta Instructora", "Marta", "FEMALE", "ca", List.of("marta@example.test"), List.of("+34600000005")));
        ports.put(member("member-estel", "account-estel", "Estel Instructora", "Estel", "FEMALE", "es", List.of("estel@example.test"), List.of()));
        ports.put(member("member-admin", "account-admin", "Admin Club", "Admin", null, "ca", List.of("admin@example.test"), List.of("+34600000006")));
        ports.instructorMembers.put("instructor-marta", "member-marta"); ports.instructorMembers.put("instructor-estel", "member-estel");
        ports.activeInstructors.addAll(List.of("instructor-marta", "instructor-estel"));
        ports.classInstructors.put("class-a", List.of("instructor-marta"));
        ports.adminIds.addAll(List.of("member-admin", "account:account-admin2"));
        return ports;
    }

    public static MemberContact member(String memberId, String accountId, String name, String firstName, String gender, String locale, List<String> emails,
            List<String> phones, MemberContact.DogContact... dogs) {
        return new MemberContact(memberId, accountId, name, firstName, gender, locale, emails.stream().map(e -> new MemberContact.Email(e, false)).toList(), phones,
                null, "ACTIVE", List.of(dogs));
    }
    public static MemberContact.DogContact dog(String id, String name, String sex) { return new MemberContact.DogContact(id, name, sex, "ACTIVE"); }

    public void put(MemberContact contact) { members.put(contact.memberId(), contact); }
    /** The member with another status (`LEFT` …), preferences, or bounced addresses. */
    public void update(String memberId, java.util.function.UnaryOperator<MemberContact> change) { members.put(memberId, change.apply(members.get(memberId))); }
    public static MemberContact with(MemberContact c, String status, Map<String, Object> preferences, List<MemberContact.Email> emails, List<String> phones) {
        return new MemberContact(c.memberId(), c.accountId(), c.displayName(), c.firstName(), c.gender(), c.locale(), emails == null ? c.emails() : emails,
                phones == null ? c.phones() : phones, preferences == null ? c.preferences() : preferences, status == null ? c.status() : status, c.dogs());
    }

    // ---- MemberDirectoryPort
    @Override public Optional<MemberContact> find(String memberId) { return Optional.ofNullable(memberId == null ? null : members.get(memberId)); }
    @Override public List<MemberContact> findAll(Collection<String> memberIds) { return memberIds.stream().distinct().map(members::get).filter(Objects::nonNull).toList(); }
    @Override public Optional<MemberContact> byAccount(String accountId) {
        return members.values().stream().filter(m -> accountId != null && accountId.equals(m.accountId())).findFirst();
    }
    @Override public Optional<MemberContact.DogContact> dog(String dogId) {
        return members.values().stream().flatMap(m -> m.dogs().stream()).filter(d -> d.dogId().equals(dogId)).findFirst();
    }
    @Override public List<String> membersWithEmail(String address) {
        return members.values().stream().filter(m -> m.emails().stream().anyMatch(e -> e.address().equalsIgnoreCase(address))).map(MemberContact::memberId).toList();
    }
    // ---- MemberContactsWriterPort
    @Override public boolean markEmailBounced(String memberId, String address) {
        var member = members.get(memberId);
        if (member == null || member.emails().stream().noneMatch(e -> e.address().equalsIgnoreCase(address) && !e.bounced())) { return false; }
        put(with(member, null, null, member.emails().stream().map(e -> e.address().equalsIgnoreCase(address) ? new MemberContact.Email(e.address(), true) : e).toList(), null));
        return true;
    }
    @Override public boolean unsubscribeClubNews(String memberId, Instant at) {
        if (!members.containsKey(memberId) || unsubscribed.contains(memberId)) { return false; }
        unsubscribed.add(memberId); return true;
    }
    // ---- StaffDirectoryPort
    @Override public List<MemberContact> admins() {
        return adminIds.stream().map(id -> id.startsWith("account:")
                ? new MemberContact(null, id.substring("account:".length()), "admin2@example.test", null, null, "es",
                        List.of(new MemberContact.Email("admin2@example.test", false)), List.of(), null, null, List.of())
                : members.get(id)).filter(Objects::nonNull).toList();
    }
    @Override public List<MemberContact> instructors() { return instructorsByIds(activeInstructors); }
    @Override public List<MemberContact> instructorsOf(String classSessionId) { return instructorsByIds(classInstructors.getOrDefault(classSessionId, List.of())); }
    @Override public List<MemberContact> instructorsByIds(Collection<String> instructorIds) {
        return instructorIds.stream().map(instructorMembers::get).filter(Objects::nonNull).map(members::get).filter(Objects::nonNull).toList();
    }
    // ---- SignupContactPort
    @Override public Optional<ApplicantContact> applicant(String memberId) { return Optional.ofNullable(applicants.get(memberId)); }
    // ---- BookingRelevancePort (R-11-16): active bookings by id with their start
    @Override public boolean stillActive(String bookingId, String trainingBookingId, Instant now) {
        var startsAt = activeBookings.get(Objects.requireNonNullElse(bookingId, Objects.toString(trainingBookingId, "")));
        return startsAt != null && startsAt.isAfter(now);
    }
    // ---- WaitlistRelevancePort (R-11-11): NOTIFIED entries with their confirmBy (Instant.MAX in ALL_AT_ONCE)
    @Override public boolean stillNotified(String waitlistEntryId, Instant now) {
        var confirmBy = notifiedEntries.get(waitlistEntryId);
        return confirmBy != null && confirmBy.isAfter(now);
    }
}
