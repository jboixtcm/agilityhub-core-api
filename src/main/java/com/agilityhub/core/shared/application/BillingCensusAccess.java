package com.agilityhub.core.shared.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The census as S12's invoicing reads and moves it (E8-T02, R-12-01…06, R-12-14, R-12-27), tenant-scoped. `payments` cannot
 * reach `clubs.census.application` (the census's signup flow already calls payments: a context cycle), so the census
 * implements this port. A payment method never carries the IBAN here: only whether the member has an account, its masked
 * form and the mandate; the SEPA writer alone decrypts (`BankAccountVault`, E43).
 */
public interface BillingCensusAccess {
    /**
     * `type` SEPA_DD · CARD · MANUAL as stored; `bankAccount` = `iban` or `ibanEncrypted` present (a migrated member carries only
     * the encrypted form, E8-T01); `cardInvalid` = `card.invalid` (R-12-22); `channel` the stored manual channel as written.
     */
    record PaymentMethod(String type, boolean bankAccount, String maskedAccount, String holderName, String holderTaxId, String mandateRef,
            String last4, boolean cardInvalid, String channel) { }
    /** A member as billing sees it; `locale` is the member's own (signup) language, null when unknown. */
    record BillingMember(String id, Integer memberNumber, String firstName, String lastName1, String lastName2, String status, String planId,
            LocalDate nextInvoiceDate, LocalDate leaveDate, String familyGroupId, PaymentMethod paymentMethod, String taxId, String locale,
            Instant updatedAt) {
        /** «Laura Serra Puig»: first name and last names, blanks dropped. */
        public String fullName() {
            var name = new StringBuilder();
            for (String part : new String[] {firstName, lastName1, lastName2}) {
                if (part != null && !part.isBlank()) { if (name.length() > 0) { name.append(' '); } name.append(part.strip()); }
            }
            return name.toString();
        }
    }
    /** An `ACTIVE` family group: its holder pays (R-12-04). */
    record FamilyGroup(String id, String holderMemberId, List<String> memberIds) { }

    /** R-12-01: the members of the open club whose status is `ACTIVE` (members inside an inactivity period included). */
    List<BillingMember> activeMembers();
    Optional<BillingMember> member(String memberId);
    /** The `ACTIVE` family groups of the open club. */
    List<FamilyGroup> activeFamilyGroups();
    /** R-12-27: the `ACTIVE` family group {@code memberId} belongs to, if any. */
    Optional<FamilyGroup> familyGroupOf(String memberId);
    /**
     * R-12-06 / R-12-14: moves `nextInvoiceDate` from {@code expected} to {@code next} in the caller's transaction, a census change
     * (version and `updatedAt` move); false when the member's date is no longer {@code expected} (an admin changed it meanwhile).
     */
    boolean moveNextInvoiceDate(String memberId, LocalDate expected, LocalDate next);
    /** R-12-07: the latest change of the given members, of every `ACTIVE` member and of the family groups. */
    Optional<Instant> lastChange(Collection<String> memberIds);
    /** The names of the club's dogs among {@code dogIds} («Classe {data} — {gos}», R-12-25). */
    Map<String, String> dogNames(Collection<String> dogIds);
}
