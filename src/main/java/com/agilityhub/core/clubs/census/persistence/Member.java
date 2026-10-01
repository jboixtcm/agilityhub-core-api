package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("members")
public class Member extends CensusEntity {
    public Map<String,Object> sourceIds;
    public Map<String,Object> externalIds;
    public String accountId;
    public Integer memberNumber;
    public Map<String,Object> idDocument;
    public String firstName;
    public String lastName1;
    public String lastName2;
    public String gender;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate birthDate;
    public List<Map<String,Object>> contactEmails;
    public List<Map<String,Object>> phones;
    public Map<String,Object> address;
    /**
     * Model §3: `{type: SEPA_DD | CARD | MANUAL}` with `sepa {iban | ibanEncrypted + ibanLast4, holderName, holderTaxId, mandateRef,
     * mandateSignedAt}` (or those keys at the top level), `card {stripeCustomerId, stripePaymentMethodId, last4, brand, invalid}`
     * or `manual {channel}`. `card.invalid` (S12 R-12-22, E8-T01) marks a card Stripe detached or declined as expired: D10's
     * «Targeta no vàlida» and the simulation's `CARD_INVALID`; absent = valid. E8-T04 writes it.
     */
    public Map<String,Object> paymentMethod;
    public String planId;
    public String priceId;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate nextInvoiceDate;
    @org.springframework.data.convert.ValueConverter(ConsentLedgerConverter.class)
    public Map<String,Object> consents;
    public Map<String,Object> signup;
    /**
     * R-04-06 (E38): a pending readmission. `submitted` holds what the applicant sent (person, contacts, address, payment
     * method, new consent entries) until validation applies it; `previous` holds what a rejection restores (status,
     * `leftAt`, `leftReason`, `leaveDate`, claim and signup block of the LEFT record). Absent otherwise.
     */
    public Map<String,Object> readmissionRequest;
    /** R-04-06 (E38): a readmission waits for its decision; its submitted values are in `readmissionRequest.submitted`. */
    public boolean readmissionPending() { return readmissionRequest != null && "PENDING".equals(status); }
    public Map<String,Object> familyGroupClaim;
    public String remarks;
    public String internalNotes;
    public String status;
    public Instant joinedAt;
    @org.springframework.data.convert.ValueConverter(CensusDateConverter.class)
    public LocalDate leaveDate;
    public Instant leftAt;
    /** `MemberLeftReason`: `SIGNUP_REJECTED` (S04), `LEAVE_REQUEST` · `ADMIN` · `PACK_EXPIRED` (S13 R-13-13), `MIGRATED` (S18). */
    public String leftReason;
    public String leaveRequestId;
    /**
     * S13 §3 (E8-T01): one entry `{leaveDate, leftAt, leftReason, reactivatedAt}` appended by each reactivation (R-13-16),
     * never rewritten; absent until the first one.
     */
    public List<Map<String,Object>> leaveHistory;
    public Map<String,Object> bookingBlock;
    @ForeignOwned public String lastDogForClass;
    @ForeignOwned public String lastDogForTraining;
    public String familyGroupId;
    public Map<String,Object> notificationPreferences;
    public Instant erasedAt;
    public String erasureRequestId;
}
