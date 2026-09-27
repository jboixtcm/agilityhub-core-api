package com.agilityhub.core.clubs.messaging.application.ports;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A person the notification engine can address (S11 R-11-02): a member of the club, with every contact address, every
 * phone in E.164 and the stored `Member.notificationPreferences` block (the engine reads it as `NotificationPreference`),
 * or an administrator's account that has no member record in the club (`memberId = null`, the account's address as the
 * only e-mail). Never logged (R-14-18).
 *
 * @param locale      the member's own language (the signup one) — the engine prefers the account's (R-11-01)
 * @param preferences the stored preference block, as the census keeps it (`null` = product defaults)
 * @param status      the member's census status (`ACTIVE`, `LEFT`, …), `null` for an account-only contact
 * @param dogs        the member's dogs (name and sex for `dog_name` / `dog_name_article`)
 */
public record MemberContact(String memberId, String accountId, String displayName, String firstName, String gender, String locale,
        List<Email> emails, List<String> phones, Map<String, Object> preferences, String status, List<DogContact> dogs) {
    public MemberContact {
        emails = emails == null ? List.of() : List.copyOf(emails);
        phones = phones == null ? List.of() : List.copyOf(phones);
        preferences = preferences == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(preferences));
        dogs = dogs == null ? List.of() : List.copyOf(dogs);
        displayName = displayName == null ? "" : displayName;
    }
    /** One contact address; a `bounced` one is never written to (R-11-08). */
    public record Email(String address, boolean bounced) {
        public Email { java.util.Objects.requireNonNull(address); }
        @Override public String toString() { return "Email[redacted]"; }
    }
    /** @param sex `FEMALE`, `MALE` or `null` (unknown, a migrated dog) */
    public record DogContact(String dogId, String name, String sex, String status) { }

    public Optional<DogContact> dog(String dogId) { return dogs.stream().filter(dog -> dog.dogId().equals(dogId)).findFirst(); }
    public boolean left() { return "LEFT".equals(status); }
    @Override public String toString() { return "MemberContact[memberId=" + memberId + ", accountId=" + accountId + "]"; }
}
